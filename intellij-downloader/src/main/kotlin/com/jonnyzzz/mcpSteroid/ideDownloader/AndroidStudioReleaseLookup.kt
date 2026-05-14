/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

/**
 * Resolves the latest stable Android Studio archive URL for ([os], [architecture]).
 *
 * Android Studio is a Google product and is NOT served by the JetBrains products API.
 * The most reliable public source is the official download page at
 * `https://developer.android.com/studio`, which lists the current direct-download URLs
 * for all supported platforms (hosted on Google's `edgedl.me.gvt1.com` CDN).
 *
 * URL filenames as they currently appear on the page (May 2026, "Panda 4 | 2025.3.4 Patch 1"):
 *
 *  | Platform | Filename suffix |
 *  |---|---|
 *  | Linux x86_64 | `-linux.tar.gz` |
 *  | macOS Intel | `-mac.dmg` |
 *  | macOS Apple Silicon | `-mac_arm.dmg` |
 *  | Windows x86_64 (installer) | `-windows.exe` |
 *  | Windows x86_64 (zip) | `-windows.zip` |
 *
 * Android Studio does NOT publish Linux ARM64 or Windows ARM64 builds. Those combos throw
 * with a clear message so callers can pick a different IDE / architecture.
 *
 * Channels other than stable (canary, beta) live on a separate page and are not yet supported.
 *
 * @param channel only `STABLE` is supported (Android Studio's `EAP` channel = canary, served
 *  from a different page; not yet implemented).
 */
fun resolveAndroidStudioArchiveUrl(
    channel: IdeChannel,
    os: HostOs,
    architecture: HostArchitecture,
    preferWindowsZip: Boolean,
): String {
    require(channel == IdeChannel.STABLE) {
        "Android Studio: only IdeChannel.STABLE is supported by this downloader; got $channel. " +
            "Canary / Beta live on a separate Google page and aren't wired up yet."
    }

    val pageUrl = "https://developer.android.com/studio"
    println("[IDE-DOWNLOAD] Fetching Android Studio downloads from $pageUrl")
    val html = readUrlText(pageUrl)

    // Each download is an absolute https URL into edgedl.me.gvt1.com/android/studio/...
    // We pull every match out of the page and pick by suffix — that's stable across
    // the marketing-name segment in the filename ("panda4-patch1"), which we can't
    // derive from the updates.xml alone.
    val allUrls = Regex("""https://[^"'\s<>]*android-studio[^"'\s<>]*\.(?:zip|tar\.gz|dmg|exe)""")
        .findAll(html)
        .map { it.value }
        .toSet()

    if (allUrls.isEmpty()) {
        error("Could not find any android-studio download URL on $pageUrl. Page format may have changed.")
    }

    val wantedSuffixes: List<String> = when (os) {
        HostOs.LINUX -> {
            require(!architecture.isArmArch) {
                "Android Studio does not publish a Linux ARM64 build (only x86_64 .tar.gz). " +
                    "Pick another product or architecture."
            }
            listOf("-linux.tar.gz")
        }
        HostOs.MAC -> if (architecture.isArmArch) listOf("-mac_arm.dmg") else listOf("-mac.dmg")
        HostOs.WINDOWS -> {
            require(!architecture.isArmArch) {
                "Android Studio does not publish a Windows ARM64 build. " +
                    "Pick x86_64 or another product."
            }
            if (preferWindowsZip) listOf("-windows.zip", "-windows.exe")
            else listOf("-windows.exe", "-windows.zip")
        }
    }

    for (suffix in wantedSuffixes) {
        val match = allUrls.firstOrNull { it.endsWith(suffix) }
        if (match != null) return match
    }

    error(
        "No Android Studio download URL ending in ${wantedSuffixes.joinToString()} found on $pageUrl. " +
            "URLs discovered: ${allUrls.sorted().joinToString()}"
    )
}
