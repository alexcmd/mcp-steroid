/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IdeReleaseLookupTest {

    // ---------- existing IntelliJ IDEA Ultimate (IIU) sanity ----------

    @Test
    fun `resolves IDEA Ultimate stable archive URL for Linux`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
        assertTrue("Expected download URL, got: $url", url.contains("download"))
    }

    @Test
    fun `resolves IDEA Ultimate EAP archive URL for Linux`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.EAP, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
    }

    @Test
    fun `resolves IDEA Ultimate stable archive URL for Mac`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.MAC)
        assertTrue("Expected .dmg URL, got: $url", url.endsWith(".dmg"))
    }

    @Test
    fun `resolves IDEA Ultimate stable archive URL for Windows`() {
        val url = resolveArchiveUrl(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.WINDOWS)
        assertTrue("Expected .exe URL, got: $url", url.endsWith(".exe"))
    }

    @Test
    fun `resolves Rider stable archive URL for Linux`() {
        val url = resolveArchiveUrl(IdeProduct.Rider, IdeChannel.STABLE, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
        assertTrue("Expected download URL, got: $url", url.contains("download"))
    }

    // ---------- new: IntelliJ Community (IIC) on every OS × arch ----------

    @Test
    fun `IntelliJ Community resolves for every OS-arch combo`() {
        for (os in HostOs.values()) {
            for (arch in HostArchitecture.values()) {
                val url = resolveArchiveUrl(
                    IdeProduct.IntelliJIdeaCommunity,
                    IdeChannel.STABLE,
                    os = os,
                    architecture = arch
                )
                assertExpectedExtension(IdeProduct.IntelliJIdeaCommunity, os, arch, url)
            }
        }
    }

    // ---------- new: PyCharm Community (PCC) on every OS × arch ----------

    @Test
    fun `PyCharm Community resolves for every OS-arch combo`() {
        for (os in HostOs.values()) {
            for (arch in HostArchitecture.values()) {
                val url = resolveArchiveUrl(
                    IdeProduct.PyCharmCommunity,
                    IdeChannel.STABLE,
                    os = os,
                    architecture = arch,
                )
                assertExpectedExtension(IdeProduct.PyCharmCommunity, os, arch, url)
            }
        }
    }

    // ---------- Android Studio (Google) ----------

    @Test
    fun `Android Studio resolves stable URLs for supported OS-arch combos`() {
        val cases = listOf(
            Triple(HostOs.LINUX, HostArchitecture.X86_64, "-linux.tar.gz"),
            Triple(HostOs.MAC, HostArchitecture.X86_64, "-mac.dmg"),
            Triple(HostOs.MAC, HostArchitecture.ARM64, "-mac_arm.dmg"),
        )
        for ((os, arch, suffix) in cases) {
            val url = resolveArchiveUrl(IdeProduct.AndroidStudio, IdeChannel.STABLE, os = os, architecture = arch)
            assertTrue("Expected $os/$arch URL to end with $suffix, got: $url", url.endsWith(suffix))
            assertTrue("Expected gvt1.com URL, got: $url", url.contains("gvt1.com") || url.contains("googleusercontent"))
        }
    }

    @Test
    fun `Android Studio on Windows x64 yields exe`() {
        val url = resolveArchiveUrl(
            IdeProduct.AndroidStudio, IdeChannel.STABLE,
            os = HostOs.WINDOWS, architecture = HostArchitecture.X86_64,
        )
        assertTrue("Expected .exe URL, got: $url", url.endsWith("-windows.exe"))
    }

    @Test
    fun `Android Studio version can be inferred from current install URL path`() {
        val url = "https://edgedl.me.gvt1.com/android/studio/install/2025.3.4.7/android-studio-panda4-patch1-mac_arm.dmg"

        assertEquals("2025.3.4.7", inferAndroidStudioVersion(url))
    }

    @Test
    fun `Android Studio rejects unsupported Linux ARM64`() {
        val ex = expectError {
            resolveArchiveUrl(IdeProduct.AndroidStudio, IdeChannel.STABLE,
                os = HostOs.LINUX, architecture = HostArchitecture.ARM64)
        }
        assertTrue("expected 'Linux ARM64' message, got: ${ex.message}",
            ex.message!!.contains("Linux ARM64"))
    }

    @Test
    fun `Android Studio rejects unsupported Windows ARM64`() {
        val ex = expectError {
            resolveArchiveUrl(IdeProduct.AndroidStudio, IdeChannel.STABLE,
                os = HostOs.WINDOWS, architecture = HostArchitecture.ARM64)
        }
        assertTrue("expected 'Windows ARM64' message, got: ${ex.message}",
            ex.message!!.contains("Windows ARM64"))
    }

    @Test
    fun `Android Studio rejects EAP channel (canary not wired up)`() {
        val ex = expectError {
            resolveArchiveUrl(IdeProduct.AndroidStudio, IdeChannel.EAP, os = HostOs.MAC)
        }
        assertTrue("expected channel message, got: ${ex.message}",
            ex.message!!.contains("only IdeChannel.STABLE is supported"))
    }

    @Test
    fun `Android Studio aliases resolve via fromString`() {
        assertTrue(IdeProduct.fromString("android-studio") === IdeProduct.AndroidStudio)
        assertTrue(IdeProduct.fromString("studio") === IdeProduct.AndroidStudio)
        assertTrue(IdeProduct.fromString("AI") === IdeProduct.AndroidStudio)
        assertTrue(IdeProduct.fromString("android") === IdeProduct.AndroidStudio)
        assertEquals(LicenseTier.Free, IdeProduct.AndroidStudio.licenseTier)
    }

    // ---------- download-key matrix ----------

    @Test
    fun `resolveDownloadKey maps correctly`() {
        assertEquals("linux", resolveDownloadKey(HostOs.LINUX, HostArchitecture.X86_64))
        assertEquals("linuxARM64", resolveDownloadKey(HostOs.LINUX, HostArchitecture.ARM64))
        assertEquals("mac", resolveDownloadKey(HostOs.MAC, HostArchitecture.X86_64))
        assertEquals("macM1", resolveDownloadKey(HostOs.MAC, HostArchitecture.ARM64))
        assertEquals("windows", resolveDownloadKey(HostOs.WINDOWS, HostArchitecture.X86_64))
        assertEquals("windowsARM64", resolveDownloadKey(HostOs.WINDOWS, HostArchitecture.ARM64))
    }

    // ---------- product enum / aliases ----------

    @Test
    fun `IdeProduct fromString maps known aliases`() {
        assertEquals("idea-ultimate", IdeProduct.IntelliJIdea.id)
        assertEquals("pycharm-pro", IdeProduct.PyCharm.id)
        assertTrue(IdeProduct.fromString("idea") === IdeProduct.IntelliJIdea)
        assertTrue(IdeProduct.fromString("idea-ultimate") === IdeProduct.IntelliJIdea)
        assertTrue(IdeProduct.fromString("idea-community") === IdeProduct.IntelliJIdeaCommunity)
        assertTrue(IdeProduct.fromString("IIC") === IdeProduct.IntelliJIdeaCommunity)
        assertTrue(IdeProduct.fromString("community") === IdeProduct.IntelliJIdeaCommunity)
        assertTrue(IdeProduct.fromString("pycharm") === IdeProduct.PyCharm)
        assertTrue(IdeProduct.fromString("pycharm-pro") === IdeProduct.PyCharm)
        assertTrue(IdeProduct.fromString("pycharm-community") === IdeProduct.PyCharmCommunity)
        assertTrue(IdeProduct.fromString("PCC") === IdeProduct.PyCharmCommunity)
        assertTrue(IdeProduct.fromString("goland") === IdeProduct.GoLand)
        assertTrue(IdeProduct.fromString("webstorm") === IdeProduct.WebStorm)
        assertTrue(IdeProduct.fromString("rider") === IdeProduct.Rider)
        assertTrue(IdeProduct.fromString("clion") === IdeProduct.CLion)
    }

    @Test
    fun `IdeProduct license tier classification`() {
        assertEquals(LicenseTier.Paid, IdeProduct.IntelliJIdea.licenseTier)
        assertEquals(LicenseTier.Paid, IdeProduct.PyCharm.licenseTier)
        assertEquals(LicenseTier.Free, IdeProduct.IntelliJIdeaCommunity.licenseTier)
        assertEquals(LicenseTier.Free, IdeProduct.PyCharmCommunity.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.GoLand.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.WebStorm.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.Rider.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.CLion.licenseTier)
    }

    @Test
    fun `IdeProduct Custom can describe unknown JetBrains products`() {
        val rubymine = IdeProduct.Custom(
            id = "rubymine",
            displayName = "RubyMine",
            code = "RM",
            launcherExecutable = "rubymine",
            licenseTier = LicenseTier.FreeForNonCommercial,
        )
        assertEquals("RM", rubymine.code)
        assertEquals(LicenseTier.FreeForNonCommercial, rubymine.licenseTier)
    }

    // ---------- requirePaidConsent gate ----------

    @Test
    fun `requirePaidConsent passes for free SKUs by default`() {
        IdeDistribution.Latest(IdeProduct.IntelliJIdeaCommunity, acceptPaid = false).requirePaidConsent()
        IdeDistribution.Latest(IdeProduct.PyCharmCommunity, acceptPaid = false).requirePaidConsent()
    }

    @Test
    fun `requirePaidConsent passes for free-for-non-commercial by default`() {
        // Rider, CLion, GoLand, WebStorm have a free mode and don't need explicit opt-in.
        IdeDistribution.Latest(IdeProduct.Rider, acceptPaid = false).requirePaidConsent()
        IdeDistribution.Latest(IdeProduct.CLion, acceptPaid = false).requirePaidConsent()
        IdeDistribution.Latest(IdeProduct.GoLand, acceptPaid = false).requirePaidConsent()
        IdeDistribution.Latest(IdeProduct.WebStorm, acceptPaid = false).requirePaidConsent()
    }

    @Test
    fun `requirePaidConsent blocks paid SKUs without consent`() {
        val ex = expectError {
            IdeDistribution.Latest(IdeProduct.IntelliJIdea, acceptPaid = false).requirePaidConsent()
        }
        assertTrue("expected paid-gate message, got: ${ex.message}",
            ex.message!!.contains("IntelliJ IDEA Ultimate"))
        val ex2 = expectError {
            IdeDistribution.Latest(IdeProduct.PyCharm, acceptPaid = false).requirePaidConsent()
        }
        assertTrue("expected paid-gate message, got: ${ex2.message}",
            ex2.message!!.contains("PyCharm Professional"))
    }

    @Test
    fun `requirePaidConsent passes for paid SKUs with explicit consent`() {
        // Default is acceptPaid = true; verifies existing call sites keep working unchanged.
        IdeDistribution.Latest(IdeProduct.IntelliJIdea).requirePaidConsent()
        IdeDistribution.Latest(IdeProduct.PyCharm, acceptPaid = true).requirePaidConsent()
    }

    @Test
    fun `requirePaidConsent passes for FromUrl regardless of license tier`() {
        // FromUrl implies the caller already picked a URL by hand.
        IdeDistribution.FromUrl(IdeProduct.IntelliJIdea, "https://example.test/idea.zip").requirePaidConsent()
    }

    // ---------- host helpers ----------

    @Test
    fun `HostArchitecture resolves correctly`() {
        val arm = resolveHostArchitecture("aarch64")
        assertEquals(HostArchitecture.ARM64, arm)
        assertTrue(arm.isArmArch)
        val x86 = resolveHostArchitecture("x86_64")
        assertEquals(HostArchitecture.X86_64, x86)
        assertTrue(!x86.isArmArch)
    }

    @Test
    fun `HostOs resolves correctly`() {
        assertEquals(HostOs.LINUX, resolveHostOs("Linux"))
        assertEquals(HostOs.MAC, resolveHostOs("Mac OS X"))
        assertEquals(HostOs.MAC, resolveHostOs("Darwin"))
        assertEquals(HostOs.WINDOWS, resolveHostOs("Windows 10"))
    }

    // ---------- helpers ----------

    /**
     * Verifies that the resolved URL's extension matches the platform expectation.
     * IDEs ship one canonical archive type per platform:
     *  - linux / mac → .tar.gz / .dmg
     *  - windows → .exe (installer)
     */
    private fun assertExpectedExtension(product: IdeProduct, os: HostOs, arch: HostArchitecture, url: String) {
        val expectedSuffixes: List<String> = when (os) {
            HostOs.LINUX -> listOf(".tar.gz", ".tgz")
            HostOs.MAC -> listOf(".dmg")
            HostOs.WINDOWS -> listOf(".exe")
        }
        assertTrue(
            "Expected URL for ${product.code}/$os/$arch to end with one of $expectedSuffixes, got: $url",
            expectedSuffixes.any { url.endsWith(it) }
        )
        assertTrue("Expected download URL, got: $url", url.startsWith("https://") || url.startsWith("http://"))
    }

    private inline fun expectError(block: () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        fail("Expected an exception; none thrown")
        @Suppress("UNREACHABLE_CODE") throw AssertionError()
    }
}
