/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.tests

import com.jonnyzzz.mcpSteroid.installer.ALL_PLATFORMS
import com.jonnyzzz.mcpSteroid.installer.DevrigEntry
import com.jonnyzzz.mcpSteroid.installer.JdkScriptEntry
import com.jonnyzzz.mcpSteroid.installer.writeInstallerScripts
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.queryContainerIp
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoMessageInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Integration test for the generated installer: builds tiny fixtures (a fake devrig zip + a fake JDK
 * tar.gz), serves them over real HTTP from an nginx side-car, renders the install.sh from a SYNTHETIC
 * JdkModel pointing at the side-car (via the public [writeInstallerScripts] seam — no real 200 MB JDK
 * download), runs the GENERATED install.sh inside an ubuntu container, and asserts the full
 * download -> sha256-verify -> unpack-verbatim -> content-address -> launcher -> PATH-symlink ->
 * auto-install pipeline. The minimal-but-meaningful lane (glibc/ubuntu); the per-vendor model + the
 * render contract are covered by the hermetic unit tests.
 */
class InstallerBootstrapTest {
    private val version = "0.0.0-test"
    private val nginxImage = "nginx:alpine"
    private val installImage = "ubuntu:24.04"

    /** HOME with a space catches quoting bugs in install.sh / the launcher wrapper. */
    private val homeDir = "/home/tester one"

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `generated install_sh end-to-end on ubuntu (glibc)`() = runWithCloseableStack { lifetime ->
        // ── 1. build fixtures into a temp dir ──
        val fixturesDir = createWorkDir("installer-fixtures")
        val devrigZip = File(fixturesDir, "devrig.zip").also { buildFakeDevrigZip(it) }
        val jdkTarGz = File(fixturesDir, "jdk.tar.gz").also { buildFakeJdkTarGz(it) }
        val devrigSha = sha256(devrigZip)
        val jdkSha = sha256(jdkTarGz)
        makeWorldReadable(fixturesDir) // nginx runs as a different uid

        // ── 2. start the nginx side-car FIRST (we need its bridge IP before baking URLs) ──
        val nginx = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(nginxImage)
                .logPrefix("installer-nginx")
                .volumes(ContainerVolume(fixturesDir, "/usr/share/nginx/html", "ro")),
        )
        val nginxIp = nginx.queryContainerIp() ?: error("nginx side-car has no bridge IP — cannot serve fixtures")
        log("nginx side-car serving fixtures at http://$nginxIp/")

        // ── 3. render install.sh from a synthetic model: all 5 platforms point at the one fake jdk.tar.gz
        //       (javaHome="jdk"), served by the side-car. This is the new seam — no real JDK download. ──
        val genDir = createWorkDir("installer-gen-out")
        val table = ALL_PLATFORMS.associateWith { JdkScriptEntry("http://$nginxIp/jdk.tar.gz", jdkSha, "tar.gz", "jdk") }
        val devrig = DevrigEntry(url = "http://$nginxIp/devrig.zip", sha256 = devrigSha)
        writeInstallerScripts(genDir.toPath(), table, devrig, version)
        require(File(genDir, "install.sh").isFile) { "did not produce install.sh in $genDir" }
        makeWorldReadable(genDir)

        // ── 4. start the install container (no JDK, space-in-HOME) ──
        val install = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(installImage)
                .logPrefix("installer-ubuntu")
                .volumes(ContainerVolume(genDir, "/gen", "ro"))
                .entryPoint(
                    "sh", "-c",
                    "apt-get update -qq && apt-get install -y -qq curl unzip >/dev/null 2>&1; mkdir -p \"$homeDir\"; sleep 3000",
                ),
        )
        awaitToolsInstalled(install)
        verifyMockServes(install, nginxIp, "/devrig.zip")
        verifyMockServes(install, nginxIp, "/jdk.tar.gz")

        val devrigKey = "devrig-linux-x64-$version-${devrigSha.take(12)}"
        val jdkKey = "jdk-linux-x64-$version-${jdkSha.take(12)}"

        // install.sh only symlinks into a writable PATH dir UNDER $HOME; pre-create one + put it on PATH.
        val homeBin = "$homeDir/.local/bin"
        sh(install, "mkdir -p \"$homeBin\"").assertExitCode(0) { "could not create $homeBin:\n$this" }
        val runPath = "$homeBin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        // ── run #1: default (auto-install ON), clean HOME → must DOWNLOAD both + auto-run 'devrig install' ──
        runInstall(install, mapOf("HOME" to homeDir, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to "x64", "PATH" to runPath))
            .assertExitCode(0) { "install.sh run #1 failed:\n$this" }
            .assertOutputContains("DEVRIG_INSTALL_CALLED", message = "run #1 must auto-run 'devrig install'")
            .assertOutputContains("downloading devrig", "downloading jdk", message = "run #1 (clean HOME) must download both")

        // (a) content-addressed dirs exist
        sh(install, "ls -1 \"$homeDir/.mcp-steroid/binaries\"")
            .assertExitCode(0) { "could not list binaries dir:\n$this" }
            .assertOutputContains(devrigKey, jdkKey, message = "expected content-addressed dirs")

        // (b) JDK downloaded + unpacked verbatim (javaHome="jdk")
        sh(install, "test -x \"$homeDir/.mcp-steroid/binaries/$jdkKey/jdk/bin/java\" && echo JDK_JAVA_OK")
            .assertOutputContains("JDK_JAVA_OK", message = "JDK bin/java missing — not downloaded/unpacked")

        // (c) bin/devrig wrapper exists and, when run, sets DEVRIG_JAVA_HOME to the bundled jdk
        val expectedJavaHome = "$homeDir/.mcp-steroid/binaries/$jdkKey/jdk"
        sh(install, "\"$homeDir/.mcp-steroid/bin/devrig\" mcp")
            .assertExitCode(0) { "running the devrig wrapper failed:\n$this" }
            .assertOutputContains("DEVRIG_RAN mcp", message = "wrapper did not exec the real devrig")
            .assertOutputContains("DEVRIG_JAVA_HOME=$expectedJavaHome", message = "wrapper must set DEVRIG_JAVA_HOME to the bundled jdk")

        // (d) PATH symlink created under HOME + resolvable via command -v devrig
        assertSymlinkCreated(install, homeBin, runPath)

        // (e) idempotent re-run reuses the content-addressed dirs and DOWNLOADS NOTHING
        val reRun = runInstall(install, mapOf("HOME" to homeDir, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to "x64"))
            .assertExitCode(0) { "idempotent re-run failed:\n$this" }
            .assertOutputContains("already installed: $devrigKey", "already installed: $jdkKey", message = "re-run must report 'already installed'")
        reRun.assertNoMessageInOutput("downloading jdk")
        reRun.assertNoMessageInOutput("downloading devrig")

        // (f) DEVRIG_NO_AUTO_INSTALL on a fresh HOME must NOT auto-install
        val freshHome = "/home/tester two"
        sh(install, "mkdir -p \"$freshHome\"").assertExitCode(0) { "could not create fresh HOME:\n$this" }
        runInstall(install, mapOf("HOME" to freshHome, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to "x64", "DEVRIG_NO_AUTO_INSTALL" to "1"))
            .assertExitCode(0) { "DEVRIG_NO_AUTO_INSTALL run failed:\n$this" }
            .assertNoMessageInOutput("DEVRIG_INSTALL_CALLED")
            .assertOutputContains("DEVRIG_NO_AUTO_INSTALL set", "skipping", message = "must log that auto-install was skipped")

        log("ALL INSTALLER ASSERTIONS PASSED on ubuntu (glibc)")
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun runInstall(c: ContainerDriver, env: Map<String, String>): ProcessResult =
        c.startProcessInContainer {
            args("sh", "/gen/install.sh").timeoutSeconds(300).description("run generated install.sh").extraEnv(env)
        }.awaitForProcessFinish()

    private fun sh(c: ContainerDriver, script: String, env: Map<String, String> = mapOf("HOME" to homeDir)): ProcessResult =
        c.startProcessInContainer {
            args("sh", "-c", script).timeoutSeconds(120).description("sh -c").extraEnv(env)
        }.awaitForProcessFinish()

    private fun awaitToolsInstalled(c: ContainerDriver) {
        val deadline = System.currentTimeMillis() + 4 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val r = sh(c, "command -v curl >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1 && echo TOOLS_OK")
            if (r.exitCode == 0 && "TOOLS_OK" in r.stdout) { log("curl + unzip present"); return }
            Thread.sleep(2_000)
        }
        error("curl + unzip were not installed in the ubuntu container within the timeout (apt-get failed?)")
    }

    private fun verifyMockServes(install: ContainerDriver, nginxIp: String, path: String) {
        val r = sh(install, "curl -fsSL -o /dev/null http://$nginxIp$path && echo SERVED_$path")
        require(r.exitCode == 0 && "SERVED_$path" in r.stdout) {
            "nginx side-car does not serve $path:\nstdout=${r.stdout}\nstderr=${r.stderr}"
        }
        log("mock server serves $path")
    }

    private fun assertSymlinkCreated(c: ContainerDriver, homeBin: String, runPath: String) {
        sh(
            c,
            "set -e; test -L \"$homeBin/devrig\"; tgt=\$(readlink \"$homeBin/devrig\"); " +
                "[ \"\$tgt\" = \"\$HOME/.mcp-steroid/bin/devrig\" ] && echo \"SYMLINK_OK \$tgt\"; " +
                "command -v devrig >/dev/null 2>&1 && echo CMDV_OK",
            env = mapOf("HOME" to homeDir, "PATH" to runPath),
        )
            .assertExitCode(0) { "PATH symlink check failed in $homeBin:\n$this" }
            .assertOutputContains("SYMLINK_OK", "CMDV_OK", message = "PATH symlink not created / not resolvable via command -v devrig")
    }

    private fun log(msg: String) = println("[InstallerBootstrapTest] $msg")

    private fun createWorkDir(prefix: String): File {
        val d = File.createTempFile(prefix, "").let { it.delete(); File(it.absolutePath + "-dir") }
        d.mkdirs()
        return d
    }

    private fun makeWorldReadable(dir: File) {
        dir.walkTopDown().forEach {
            it.setReadable(true, false)
            if (it.isDirectory) it.setExecutable(true, false)
        }
        dir.setExecutable(true, false)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Fake devrig dist zip. Top dir `devrig-<version>/`, with an executable POSIX-sh `bin/devrig`:
     *  - `install`     → prints `DEVRIG_INSTALL_CALLED jdk=<DEVRIG_JAVA_HOME>`, exits 0.
     *  - `mcp` / other → prints `DEVRIG_RAN <args>` AND `DEVRIG_JAVA_HOME=<the env it sees>`.
     */
    private fun buildFakeDevrigZip(target: File) {
        val script = buildString {
            append("#!/bin/sh\n")
            append("case \"\$1\" in\n")
            append("  install)\n")
            append("    echo \"DEVRIG_INSTALL_CALLED jdk=\${DEVRIG_JAVA_HOME:-}\"\n")
            append("    exit 0 ;;\n")
            append("  *)\n")
            append("    echo \"DEVRIG_RAN \$*\"\n")
            append("    echo \"DEVRIG_JAVA_HOME=\${DEVRIG_JAVA_HOME:-}\"\n")
            append("    exit 0 ;;\n")
            append("esac\n")
        }
        // install.sh chmod +x's the launcher after unpack, so the archived mode bit need not survive.
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("devrig-$version/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("devrig-$version/bin/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("devrig-$version/bin/devrig")); zip.write(script.toByteArray()); zip.closeEntry()
        }
    }

    /** Fake JDK tar.gz. Top dir `jdk/` (matches javaHome="jdk"), with an executable `bin/java` sh stub. */
    private fun buildFakeJdkTarGz(target: File) {
        val javaStub = "#!/bin/sh\necho 'java-stub 25'\nexit 0\n".toByteArray()
        GZIPOutputStream(FileOutputStream(target)).use { gz ->
            TarWriter(gz).use { tar ->
                tar.putDir("jdk/")
                tar.putDir("jdk/bin/")
                tar.putFile("jdk/bin/java", javaStub, mode = 0b111_101_101) // rwxr-xr-x
            }
        }
    }
}

/**
 * Minimal POSIX (ustar) tar writer — the JDK has no built-in tar. Enough for a few small files with
 * stored unix permission bits so the unpacked `bin/java` keeps its +x bit (install.sh checks `-x`).
 */
private class TarWriter(private val out: java.io.OutputStream) : AutoCloseable {
    fun putDir(name: String) = writeEntry(name, ByteArray(0), typeFlag = '5', mode = 0b111_101_101)
    fun putFile(name: String, data: ByteArray, mode: Int) = writeEntry(name, data, typeFlag = '0', mode = mode)

    private fun writeEntry(name: String, data: ByteArray, typeFlag: Char, mode: Int) {
        val header = ByteArray(512)
        putString(header, 0, name, 100)
        putOctal(header, 100, mode.toLong(), 8)
        putOctal(header, 108, 0, 8)
        putOctal(header, 116, 0, 8)
        putOctal(header, 124, data.size.toLong(), 12)
        putOctal(header, 136, 0, 12)
        header[156] = typeFlag.code.toByte()
        putString(header, 257, "ustar", 6)
        header[263] = '0'.code.toByte(); header[264] = '0'.code.toByte()
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0
        for (b in header) sum += (b.toInt() and 0xff)
        putOctal(header, 148, sum.toLong(), 7)
        header[155] = ' '.code.toByte()
        out.write(header)
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        if (pad > 0) out.write(ByteArray(pad))
    }

    private fun putString(buf: ByteArray, off: Int, s: String, max: Int) {
        val bytes = s.toByteArray()
        System.arraycopy(bytes, 0, buf, off, minOf(bytes.size, max - 1))
    }

    private fun putOctal(buf: ByteArray, off: Int, value: Long, len: Int) {
        val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
        System.arraycopy(s.toByteArray(), 0, buf, off, len - 1)
        buf[off + len - 1] = 0
    }

    override fun close() {
        out.write(ByteArray(1024)) // two zero blocks terminate the archive
        out.flush()
    }
}
