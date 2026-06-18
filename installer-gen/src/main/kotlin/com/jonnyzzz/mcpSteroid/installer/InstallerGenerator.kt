/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The installer tool: generates the self-contained `install.sh` (macOS + Linux) and `install.ps1`
 * (Windows). The JDK coordinates come from [resolveAllJdks] — the data model already downloaded,
 * PGP-verified, and computed `url`/`sha256`/`javaHome` for every platform, so this tool just ADAPTS the
 * model into the per-platform table each script bakes in. No `--jdk` args, no local-file inspection.
 *
 * devrig coordinates: a local zip override (`--devrig-zip` + `--devrig-url`, used by tests / a pre-built
 * artifact), a pinned `--devrig-version`, or — by default — the latest published GitHub release.
 */

/** The five supported platforms, keyed `<os>-<cpu>`. The script split is by OS. */
val POSIX_PLATFORMS = listOf("macos-arm64", "linux-arm64", "linux-x64")
val WINDOWS_PLATFORMS = listOf("windows-x64", "windows-arm64")
val ALL_PLATFORMS = POSIX_PLATFORMS + WINDOWS_PLATFORMS

/** The per-platform values baked into the scripts (derived from a [JdkArtifact]). */
data class JdkScriptEntry(val url: String, val sha256: String, val format: String, val javaHome: String)

data class DevrigEntry(val url: String, val sha256: String, val format: String = "zip")

private val ghJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GhRelease(val assets: List<GhAsset> = emptyList())

@Serializable
private data class GhAsset(val name: String, val browser_download_url: String)

/** `<os>-<cpu>` key for a platform — matches the scripts' `uname` normalization (`AARCH64` -> `arm64`). */
internal fun JdkPlatform.scriptKey(): String {
    val osToken = when (os) { JdkOs.LINUX -> "linux"; JdkOs.MACOS -> "macos"; JdkOs.WINDOWS -> "windows" }
    val cpuToken = when (arch) { JdkArch.X64 -> "x64"; JdkArch.AARCH64 -> "arm64" }
    return "$osToken-$cpuToken"
}

/** Adapt the resolved [JdkModel] into the platform-keyed table the scripts bake in, and validate it. */
internal fun jdkScriptTable(model: JdkModel): Map<String, JdkScriptEntry> {
    val table = model.jdks.associate {
        it.platform.scriptKey() to JdkScriptEntry(it.url, it.sha256, it.archive.extension, it.javaHome)
    }
    validateScriptTable(table)
    return table
}

/** All 5 installer platforms present, no extras, and each entry well-formed (sha/format/javaHome). */
internal fun validateScriptTable(table: Map<String, JdkScriptEntry>) {
    val missing = ALL_PLATFORMS.filterNot { it in table.keys }
    require(missing.isEmpty()) { "JDK model is missing installer platforms: $missing (have ${table.keys})" }
    val extra = table.keys.filterNot { it in ALL_PLATFORMS }
    require(extra.isEmpty()) { "JDK model has platforms the installer does not support: $extra" }
    table.forEach { (key, e) ->
        require(e.sha256.matches(Regex("[0-9a-f]{64}"))) { "$key: sha256 must be 64 lowercase hex, got '${e.sha256}'" }
        require(e.format in setOf("zip", "tar.gz")) { "$key: unexpected archive format '${e.format}'" }
        require(e.javaHome.isNotBlank() && !e.javaHome.startsWith("/")) { "$key: bad javaHome '${e.javaHome}'" }
    }
}

/** Reject placeholder/malformed devrig coordinates so the generator never bakes a broken download URL. */
internal fun validateDevrig(e: DevrigEntry) {
    // Absolute http(s) URL, never the placeholder. http is allowed so the integration tests' nginx
    // side-car URLs pass; production records https release URLs.
    require((e.url.startsWith("https://") || e.url.startsWith("http://")) && "PLACEHOLDER" !in e.url) {
        "devrig url must be an absolute http(s) URL without PLACEHOLDER, got '${e.url}'"
    }
    require(e.sha256.matches(Regex("[0-9a-f]{64}"))) { "devrig sha256 must be 64 lowercase hex, got '${e.sha256}'" }
    require(e.format == "zip") { "devrig: unexpected format '${e.format}'" }
}

/** POSIX `case` arms for the install.sh baked table (single-quoted values; sha256/url carry no quotes). */
private fun renderShCase(table: Map<String, JdkScriptEntry>, version: String): String = buildString {
    for (key in POSIX_PLATFORMS) {
        val j = table.getValue(key)
        appendLine("  $key)")
        appendLine("    devrig_binsub='devrig-$version/bin/devrig'")
        appendLine("    jdk_url='${j.url}'")
        appendLine("    jdk_sha256='${j.sha256}'")
        appendLine("    jdk_format='${j.format}'")
        appendLine("    jdk_javahome='${j.javaHome}'")
        appendLine("    ;;")
    }
}.trimEnd('\n')

/** PowerShell hashtable literal for the install.ps1 baked table. */
private fun renderPsTable(table: Map<String, JdkScriptEntry>, version: String): String = buildString {
    for (key in WINDOWS_PLATFORMS) {
        val j = table.getValue(key)
        appendLine("  '$key' = @{")
        appendLine("    DevrigBinSub = 'devrig-$version/bin/devrig.bat'")
        appendLine("    JdkUrl = '${j.url}'")
        appendLine("    JdkSha256 = '${j.sha256}'")
        appendLine("    JdkFormat = '${j.format}'")
        appendLine("    JdkJavaHome = '${j.javaHome}'")
        appendLine("  }")
    }
}.trimEnd('\n')

private fun loadResource(name: String): String =
    InstallerGeneratorAnchor::class.java.getResource(name)?.readText()
        ?: error("missing template resource on classpath: $name")

/** Marker class so the resource loader has a stable anchor for `getResource`. */
private object InstallerGeneratorAnchor

private fun render(template: String, subs: Map<String, String>): String {
    var out = template
    for ((k, v) in subs) out = out.replace("@@$k@@", v)
    val leftover = Regex("@@[A-Z0-9_]+@@").find(out)
    require(leftover == null) { "unresolved placeholder ${leftover!!.value} in template" }
    return out
}

private fun parseFlags(argv: Array<String>): Map<String, MutableList<String>> {
    val m = LinkedHashMap<String, MutableList<String>>()
    var i = 0
    while (i < argv.size) {
        require(argv[i].startsWith("--")) { "unexpected argument '${argv[i]}'" }
        require(i + 1 < argv.size) { "missing value for ${argv[i]}" }
        m.getOrPut(argv[i].removePrefix("--")) { mutableListOf() }.add(argv[i + 1]); i += 2
    }
    return m
}

private fun resolveLatestDevrigZipUrl(http: HttpFetcher): String {
    val body = http.getBytes("https://api.github.com/repos/jonnyzzz/mcp-steroid/releases/latest").decodeToString()
    return ghJson.decodeFromString<GhRelease>(body).assets
        .firstOrNull { it.name.startsWith("devrig") && it.name.endsWith(".zip") }
        ?.browser_download_url
        ?: error("no devrig-*.zip asset on the latest jonnyzzz/mcp-steroid release")
}

/**
 * Resolve devrig coordinates. Local override (a pre-built / fixture zip): `--devrig-zip <file>` +
 * `--devrig-url <public url>`. Otherwise download a published release — pinned `--devrig-version <v>` or,
 * by default, the latest GitHub release — and compute sha from the bytes.
 */
internal fun resolveDevrig(flags: Map<String, List<String>>, http: HttpFetcher): DevrigEntry {
    flags["devrig-zip"]?.firstOrNull()?.let { zip ->
        val url = flags["devrig-url"]?.firstOrNull() ?: error("--devrig-url is required with --devrig-zip")
        val file = Path.of(zip)
        require(Files.isRegularFile(file)) { "missing devrig package: $file" }
        return DevrigEntry(url = url, sha256 = sha256Hex(Files.readAllBytes(file)))
    }
    val version = flags["devrig-version"]?.firstOrNull()
    val url = if (version != null) {
        "https://github.com/jonnyzzz/mcp-steroid/releases/download/v$version/devrig-$version.zip"
    } else {
        resolveLatestDevrigZipUrl(http)
    }
    return DevrigEntry(url = url, sha256 = sha256Hex(http.getBytes(url)))
}

fun main(argv: Array<String>) {
    val flags = parseFlags(argv)
    fun req(k: String) = flags[k]?.firstOrNull() ?: error("required --$k not provided")
    val outDir = Path.of(req("out-dir"))
    val version = req("version")
    val cacheDir = Path.of(req("cache-dir"))

    KtorHttpFetcher.use { http ->
        val model = resolveAllJdks(Cache.onDisk(cacheDir), http)
        val devrig = resolveDevrig(flags, http)
        writeInstallerScripts(outDir, jdkScriptTable(model), devrig, version)
        System.err.println("[installer-gen] wrote install.sh + install.ps1 to $outDir (version $version, devrig ${devrig.url})")
    }
}

/** The rendered installer scripts. */
data class InstallerScripts(val sh: String, val ps: String)

/**
 * Render install.sh + install.ps1 from the per-platform [table] + [devrig] coordinates. Pure: no network,
 * no disk — the seam the integration tests drive with a synthetic model + nginx-side-car URLs (so they
 * never download a real 200 MB JDK). Validates inputs (all 5 platforms present, sha/format/javaHome,
 * devrig URL) before rendering.
 */
internal fun renderInstallerScripts(table: Map<String, JdkScriptEntry>, devrig: DevrigEntry, version: String): InstallerScripts {
    validateScriptTable(table)
    validateDevrig(devrig)
    val devrigSubs = mapOf(
        "VERSION" to version,
        "DEVRIG_URL" to devrig.url,
        "DEVRIG_SHA256" to devrig.sha256,
        "DEVRIG_FORMAT" to devrig.format,
    )
    val sh = render(loadResource("/templates/install.sh.tmpl"), devrigSubs + ("PLATFORM_CASE_SH" to renderShCase(table, version)))
    val ps = render(loadResource("/templates/install.ps1.tmpl"), devrigSubs + ("PLATFORM_TABLE_PS" to renderPsTable(table, version)))
    return InstallerScripts(sh, ps)
}

/** Render the scripts and write them (newline-terminated) into [outDir]. */
internal fun writeInstallerScripts(outDir: Path, table: Map<String, JdkScriptEntry>, devrig: DevrigEntry, version: String) {
    val scripts = renderInstallerScripts(table, devrig, version)
    Files.createDirectories(outDir)
    outDir.resolve("install.sh").writeText(if (scripts.sh.endsWith("\n")) scripts.sh else scripts.sh + "\n")
    outDir.resolve("install.ps1").writeText(if (scripts.ps.endsWith("\n")) scripts.ps else scripts.ps + "\n")
}
