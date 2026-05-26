/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.slf4j.LoggerFactory
import java.io.File

private val localIdeProvisionerLog = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.LocalIdeProvisioner")

/**
 * Resolves an [IdeTarget] (from `McpSteroidIdeTargets`) to an on-disk IDE root
 * directory suitable for IntelliJ Platform Gradle Plugin's `local(file)`
 * selector. Used by `ij-plugin/build.gradle.kts` to feed both the main build
 * dependency and `pluginVerification.ides { }` — the consolidated entry point
 * for "fetch and stage an IDE through `intellij-downloader`" instead of letting
 * IPGP's `useInstaller` flow do it.
 *
 * Filesystem layout:
 * ```
 * <downloadDir>/idea-<channel>-<arch>.tar.gz            — archive cache (IdeDownloader)
 * <unpackBaseDir>/IU-<full-build>-<os>-<arch>/...       — unpacked tree
 * <returned File>                                       — IDE root (the dir IPGP's local() consumes;
 *                                                          contains product-info.json or
 *                                                          Resources/product-info.json on macOS)
 * ```
 *
 * The folder name carries the **actual** build number (resolved through the
 * products API) plus OS and architecture, so:
 *  - the same working copy can be safely shared across OSes on the same disk;
 *  - bumping a per-major snapshot tag (e.g. when a fresh 262 EAP drops) yields
 *    a new folder name rather than mutating an existing one in place.
 *
 * Caching: `resolveAndDownload` skips the network when the archive file exists
 * and matches the published SHA-256 (where available). `unpackIdeArchive`
 * skips when the target directory is already populated. First call per
 * [target] is slow (downloads ~1 GB); subsequent calls hit the filesystem
 * cache and return immediately.
 */
fun resolveAndUnpackLocally(
    target: IdeTarget,
    downloadDir: File,
    unpackBaseDir: File,
    os: HostOs = resolveHostOs(),
    arch: HostArchitecture = resolveHostArchitecture(),
    product: IdeProduct = IdeProduct.IntelliJIdea,
): File {
    val channel = inferChannel(target.version)
    val namedMajorTag = parseNamedMajorEapTag(target.version)
    val resolution = resolveArchive(
        product = product,
        channel = channel,
        os = os,
        architecture = arch,
        // Named per-major tags (e.g. "262-EAP-SNAPSHOT") don't equal any
        // public version/build, so we route through the buildPrefix filter
        // instead and let the products API pick the latest matching the major.
        version = if (namedMajorTag != null) null else target.version,
        buildPrefix = namedMajorTag?.let { "$it." },
    )

    // Single network round-trip: feed the resolved URL to FromUrl so the
    // download path doesn't re-resolve via the products API.
    val distribution = IdeDistribution.FromUrl(
        product = product,
        url = resolution.url,
        checksumUrl = resolution.checksumUrl,
    )
    val archive = distribution.resolveAndDownload(downloadDir, os)

    val unpackDir = File(unpackBaseDir, ideRootFolderName(resolution.build, os, arch))
    unpackIdeArchive(archive, unpackDir)

    val ideRoot = findIdeRoot(unpackDir)
    localIdeProvisionerLog.info(
        "[LOCAL-IDE] {} ({}) -> {}",
        target,
        resolution.build,
        ideRoot,
    )
    return ideRoot
}

/**
 * If [version] is a named per-major snapshot tag (e.g. `"262-EAP-SNAPSHOT"`
 * or `"262-SNAPSHOT"`), returns the major prefix (e.g. `"262"`). Returns
 * null for stable version strings and exact build numbers — those flow
 * through [resolveArchive]'s exact `version` filter instead.
 */
internal fun parseNamedMajorEapTag(version: String): String? {
    val match = Regex("^(\\d{3})-(EAP-)?SNAPSHOT$").matchEntire(version) ?: return null
    return match.groupValues[1]
}

/**
 * Infers the products-API channel from a [version] string. Snapshot or EAP tags
 * imply the EAP channel; everything else is stable.
 *
 * `262-EAP-SNAPSHOT`, `262-SNAPSHOT`, anything containing `EAP` → EAP.
 * `2026.1`, `262.6228.19` → STABLE (note: an exact build inside an EAP channel
 * would still need an explicit override; the matrix encodes that via the
 * per-major tag spelling).
 */
internal fun inferChannel(version: String): IdeChannel {
    if (version.endsWith("-SNAPSHOT") || version.contains("EAP")) return IdeChannel.EAP
    return IdeChannel.STABLE
}

/**
 * Conventional folder name: `IU-<full-build>-<os>-<arch>`. Both [os] and
 * [arch] are included so the same `<unpackBaseDir>` can host parallel
 * mac/linux/win and ARM/x86 trees without collision — which lets a single
 * working copy be shared across hosts on networked storage.
 */
internal fun ideRootFolderName(buildNumber: String, os: HostOs, arch: HostArchitecture): String {
    val osTag = when (os) {
        HostOs.LINUX -> "linux"
        HostOs.MAC -> "mac"
        HostOs.WINDOWS -> "windows"
    }
    val archTag = if (arch.isArmArch) "aarch64" else "x86_64"
    // Product code is fixed to IU for IntelliJIdea targets; expand when we add other products.
    return "IU-$buildNumber-$osTag-$archTag"
}

/**
 * After [unpackIdeArchive] populates [unpackDir], locate the directory IPGP's
 * `local(file)` expects: the one containing `product-info.json` (Linux/Windows
 * tar.gz/zip) or `Resources/product-info.json` (macOS `.app/Contents/`).
 *
 * Strategy: check direct hit, then descend one level. Goes no deeper so a
 * malformed archive surfaces fast rather than searching the whole tree.
 */
internal fun findIdeRoot(unpackDir: File): File {
    require(unpackDir.isDirectory) { "Expected unpacked IDE under $unpackDir" }

    // Direct: unpackDir IS already the IDE root.
    if (hasProductInfo(unpackDir)) return unpackDir

    val children = unpackDir.listFiles().orEmpty().filter { it.isDirectory }
    for (child in children) {
        // Linux/Windows: child IS the IDE root (idea-IU-<build>/ or similar).
        if (hasProductInfo(child)) return child
        // macOS: child is the `.app`, and `.app/Contents/` is the IDE root.
        val contents = File(child, "Contents")
        if (hasProductInfo(contents)) return contents
    }

    error(
        "Could not find IDE root under $unpackDir — no product-info.json in " +
            "$unpackDir or any first-level child directory. The archive may be " +
            "malformed, or the unpack step exited early."
    )
}

private fun hasProductInfo(dir: File): Boolean {
    if (!dir.isDirectory) return false
    return File(dir, "product-info.json").isFile ||
        File(dir, "Resources/product-info.json").isFile
}
