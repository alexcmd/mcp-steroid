/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists
import kotlin.io.path.readText

internal fun isWindows(): Boolean =
    System.getProperty("os.name").lowercase().contains("windows")

/**
 * Undocumented escape hatch governing the on-each-start launcher self-heal ([ensureBinLauncher]).
 * Intentionally NOT mentioned in `devrig --help` / docs — it exists for tests and for power users who
 * manage `~/.mcp-steroid/bin/devrig` themselves.
 */
internal const val ENV_BIN_NO_AUTO_REGISTER = "DEVRIG_BIN_NO_AUTO_REGISTER"

/**
 * Whether the binary should own (write + PATH-link) `~/.mcp-steroid/bin/devrig` on this start.
 *
 *  - [ENV_BIN_NO_AUTO_REGISTER] = `yes`/`true`/`1`/`on`  → OFF (explicit opt-out).
 *  - [ENV_BIN_NO_AUTO_REGISTER] = `no`/`false`/`0`/`off` → ON  (explicit opt-in — overrides the default,
 *    which is how the integration test enables it on a SNAPSHOT build).
 *  - unset / unrecognized → ON for release builds, OFF for SNAPSHOT/dev builds (and thus for tests,
 *    whose devrig is always a SNAPSHOT) so a dev build never clobbers the user's real launcher.
 *
 * The build version is read straight from the generated [DevrigVersionMetadata]; only the env value is a
 * parameter, so the env-override branches stay unit-testable without faking the version.
 */
internal fun binAutoRegisterEnabled(envValue: String? = System.getenv(ENV_BIN_NO_AUTO_REGISTER)): Boolean {
    parseBinAutoRegisterOptOut(envValue)?.let { optedOut -> return !optedOut }
    return !DevrigVersionMetadata.getDevrigVersion().contains("SNAPSHOT", ignoreCase = true)
}

/** `true` = opt-out (disable), `false` = opt-in (enable), `null` = unset/unrecognized (use the default). */
private fun parseBinAutoRegisterOptOut(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
    "yes", "true", "1", "on" -> true
    "no", "false", "0", "off" -> false
    else -> null
}

/**
 * Self-registration of the user-facing `~/.mcp-steroid/bin` launcher AND its reachability on the user's
 * PATH. **The devrig binary owns both** — the install script does neither.
 *
 * On EVERY devrig start this ensures:
 *  1. `~/.mcp-steroid/bin/devrig` (POSIX) / `~/.mcp-steroid/bin/devrig.cmd` (Windows) exists and points
 *     at devrig's OWN current install tree and the JDK it is running under — so the launcher self-heals
 *     if it is missing or stale (e.g. after an upgrade repointed the install tree).
 *  2. that launcher is reachable on PATH — POSIX symlinks it into a writable PATH dir under `$HOME`
 *     (pure Java, no subprocess); Windows cannot persist the user PATH from pure Java and must not spawn
 *     a process on the startup path, so it only checks membership and prints a one-time manual hint when
 *     the bin dir is absent (agents launch the wrapper by absolute path, so MCP works regardless).
 *
 * The launcher is rewritten ONLY when its content actually changed (normalized comparison), never on
 * every launch — and writes are atomic (temp file + atomic move), so a concurrent agent that is mid-read
 * of the file (the contract: it can change WHILE the binary runs) never sees a torn launcher.
 *
 * Best-effort: any failure to resolve devrig's root, write the launcher, or touch PATH is logged to
 * stderr and swallowed — it must never prevent `devrig mcp` from serving. All output goes to stderr;
 * stdout is the MCP JSON-RPC channel.
 */
fun ensureBinLauncher(home: HomePaths) {
    if (!binAutoRegisterEnabled()) {
        return
    }
    try {
        ensureBinLauncher(
            home = home,
            isWin = isWindows(),
            ownRoot = DevrigRoot.path,
            ownJava = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize(),
            userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
            pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar),
        )
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not (re)write the devrig launcher: $e")
    }
}

/**
 * Testable core — every input is explicit (no environment lookups), so the OS branch and path logic are
 * unit-testable without touching `os.name` / `user.home` / `java.home` / `PATH`.
 *
 * There is NO "only when installed under ~/.mcp-steroid" guard: the contract is that the launcher is
 * (re)written on EVERY start, pointing at wherever this binary currently runs from. The safety against a
 * dev build clobbering a real launcher is the SNAPSHOT/env gate in [binAutoRegisterEnabled], not a
 * location check — so the wrapper records ABSOLUTE paths and works from any install location (including
 * the `/tmp/devrig` the integration test copies the dist to).
 */
internal fun ensureBinLauncher(
    home: HomePaths,
    isWin: Boolean,
    ownRoot: Path,
    ownJava: Path,
    userHome: Path,
    pathDirs: List<String>,
) {
    val ownBin = ownRoot.resolve("bin").resolve(if (isWin) "devrig.bat" else "devrig").toAbsolutePath().normalize()
    val jdkHome = ownJava.toAbsolutePath().normalize()
    if (isWin) {
        // CMD-only launcher: a single self-contained devrig.cmd. No PowerShell at launch — PS is only
        // needed by the install SCRIPT, not the launcher.
        val cmd = home.binDir.resolve("devrig.cmd")
        writeIfChanged(home.binDir, cmd, renderWindowsCmd(ownBin, jdkHome), executable = false, ownBin = ownBin)
        ensureWindowsPathEntry(home.binDir, pathDirs)
    } else {
        val devrig = home.binDir.resolve("devrig")
        writeIfChanged(home.binDir, devrig, renderPosixLauncher(ownBin, jdkHome), executable = true, ownBin = ownBin)
        ensurePosixPathSymlink(home.binDir, devrig, userHome, pathDirs)
    }
}

/** The POSIX wrapper: pins the bundled JDK via DEVRIG_JAVA_HOME, then execs the install-tree launcher. */
internal fun renderPosixLauncher(launcher: Path, jdkHome: Path): String =
    "#!/bin/sh\n" +
        "# devrig launcher — managed by the devrig binary. Writes nothing to stdout (MCP stdio channel).\n" +
        "# Always pins the bundled JDK via DEVRIG_JAVA_HOME (the bundled runtime is the supported one),\n" +
        "# then hands off to the devrig binary.\n" +
        "DEVRIG_JAVA_HOME=\"$jdkHome\"; export DEVRIG_JAVA_HOME\n" +
        "exec \"$launcher\" \"\$@\"\n"

/**
 * The self-contained Windows launcher: pure batch, NO PowerShell at launch. It ALWAYS pins the bundled
 * JDK via DEVRIG_JAVA_HOME (the bundled runtime is the supported one), then `call`s the bundled
 * devrig.bat. STDOUT cleanliness (the MCP JSON-RPC channel): `@echo off` + `set`/`call` emit nothing to
 * stdout; only the inner devrig.bat → java does. The agent invokes this via `cmd.exe /d /c` — see
 * [DevrigUserLauncher.invocation].
 */
internal fun renderWindowsCmd(launcher: Path, jdkHome: Path): String =
    "@echo off\r\n" +
        "set \"DEVRIG_JAVA_HOME=$jdkHome\"\r\n" +
        "call \"$launcher\" %*\r\n"

private fun writeIfChanged(dir: Path, target: Path, desired: String, executable: Boolean, ownBin: Path) {
    // An unreadable existing launcher (non-UTF-8 bytes, wrong file type, transient IO) counts as
    // "changed" so a corrupt launcher self-heals rather than being left in place.
    val current = if (!target.exists()) null else try {
        target.readText()
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] existing launcher $target is unreadable ($e); rewriting it")
        null
    }
    if (current != null && normalizeLauncher(current) == normalizeLauncher(desired)) return
    writeAtomically(dir, target, desired, executable)
    System.err.println("[mcp-steroid] (re)wrote $target -> $ownBin")
}

/** Tolerant of CRLF↔LF and trailing-newline differences so we rewrite ONLY on a real content change. */
internal fun normalizeLauncher(s: String): String = s.replace("\r\n", "\n").trimEnd('\n')

/**
 * Make `bin/devrig` reachable on PATH by symlinking it into the first writable PATH directory under
 * `$HOME`. Mirrors the install.sh logic that this now replaces: never edits shell profiles, never
 * touches system dirs, never clobbers a `devrig` that is not already our own symlink, and never
 * self-links the bin dir. Best-effort — a missing PATH dir is fine; the launcher still works by full path.
 */
internal fun ensurePosixPathSymlink(binDir: Path, binDevrig: Path, userHome: Path, pathDirs: List<String>) {
    val target = binDevrig.toAbsolutePath().normalize()
    val binDirNorm = binDir.toAbsolutePath().normalize()
    val homeNorm = userHome.toAbsolutePath().normalize()
    for (entry in pathDirs) {
        if (entry.isBlank()) continue
        val dir = try {
            Path.of(entry).toAbsolutePath().normalize()
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] skipping malformed PATH entry '$entry': $e")
            continue
        }
        if (dir == binDirNorm) continue                 // never self-link
        if (!dir.startsWith(homeNorm)) continue          // only directories under your home
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) continue
        val link = dir.resolve("devrig")
        // Never clobber a `devrig` we did not create: a real (non-symlink) file, or a symlink pointing
        // elsewhere. readSymbolicLink is only called once we know it IS a symlink, so it cannot throw
        // NotLinkException; a rare IO error bubbles to ensureBinLauncher's best-effort catch (logged).
        if (Files.isSymbolicLink(link)) {
            // Already OUR symlink → nothing to do; return silently so we don't churn the FS or log on
            // every start (the on-each-start contract means this is the steady-state path).
            if (Files.readSymbolicLink(link).toAbsolutePath().normalize() == target) return
            continue // foreign symlink — do not clobber
        }
        if (link.exists()) continue // foreign real file — do not clobber
        try {
            // We only reach here when nothing exists at `link`, so no deleteIfExists is needed.
            Files.createSymbolicLink(link, target)
            System.err.println("[mcp-steroid] linked $link -> $target")
            return
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not symlink $link -> $target ($e); trying the next PATH dir")
        }
    }
    System.err.println(
        "[mcp-steroid] devrig is installed at $target but no writable PATH dir under \$HOME was found; " +
            "add it to PATH manually: export PATH=\"\$HOME/.mcp-steroid/bin:\$PATH\"",
    )
}

/**
 * Make `bin/devrig.cmd` discoverable for INTERACTIVE Windows use. We do **NOT** spawn anything from the
 * startup path (no PowerShell, no `setx`) and never touch the registry — the JDK cannot persist the user
 * PATH in pure Java, and spawning a process on every `devrig mcp` start is exactly what we must avoid.
 * Agents launch the wrapper by ABSOLUTE path (see [DevrigUserLauncher.invocation]), so PATH membership is
 * only a human convenience, never a correctness requirement. So this just CHECKS membership against the
 * process PATH (`System.getenv("PATH")` split by [File.pathSeparatorChar], passed in as [pathDirs]) and,
 * if the bin dir is absent, prints a one-time manual hint to stderr (guarded by a marker so it is not
 * repeated on every start). The POSIX side actively symlinks (pure Java, no subprocess); Windows cannot,
 * so it informs instead.
 */
internal fun ensureWindowsPathEntry(binDir: Path, pathDirs: List<String>) {
    val binDirNorm = binDir.toAbsolutePath().normalize()
    if (pathDirs.any { it.isNotBlank() && pathEntryEquals(it, binDirNorm) }) return
    val marker = binDirNorm.resolve(".path-hint-shown")
    if (Files.exists(marker)) return
    System.err.println(
        "[mcp-steroid] $binDirNorm is not on your PATH. To run 'devrig' directly from a terminal, add it " +
            "via System Properties -> Environment Variables (User PATH). Agents use the full path, so MCP " +
            "works regardless.",
    )
    try {
        Files.writeString(marker, binDirNorm.toString())
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not write the PATH-hint marker $marker: $e")
    }
}

private fun pathEntryEquals(entry: String, dir: Path): Boolean =
    runCatching { Path.of(entry).toAbsolutePath().normalize() }.getOrNull() == dir

private fun writeAtomically(dir: Path, target: Path, content: String, executable: Boolean) {
    Files.createDirectories(dir)
    val tmp = dir.resolve(".tmp.${ProcessHandle.current().pid()}.${target.fileName}")
    try {
        Files.writeString(tmp, content)
        // Set the executable bit on the staging file BEFORE the move, so the final launcher is never
        // momentarily non-executable (mirrors install.sh: chmod +x then mv).
        if (executable) setExecutable(tmp)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            System.err.println("[mcp-steroid] atomic move unsupported (${e.message}); using a plain move")
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        // On success the move consumed tmp (this is a no-op); on any failure above, sweep the orphan
        // staging file instead of leaking a `.tmp.<pid>.devrig` into the bin dir.
        if (Files.exists(tmp)) {
            try {
                Files.deleteIfExists(tmp)
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not remove staging file $tmp: $e")
            }
        }
    }
}

private fun setExecutable(file: Path) {
    try {
        val perms = Files.getPosixFilePermissions(file).toMutableSet()
        perms += PosixFilePermission.OWNER_EXECUTE
        perms += PosixFilePermission.GROUP_EXECUTE
        perms += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(file, perms)
    } catch (e: UnsupportedOperationException) {
        // Non-POSIX filesystem (e.g. Windows): fall back to the File API.
        System.err.println("[mcp-steroid] POSIX permissions unsupported (${e.message}); using File.setExecutable")
        file.toFile().setExecutable(true, false)
    }
}
