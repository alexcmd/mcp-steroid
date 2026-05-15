/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendManagerDownloadValidationTest {

    @Test
    fun `download accepts requested product-info productCode`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val archivePath = fakeArchive(tempDir, "ideaIC-2025.3.3-aarch64.dmg")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(productCode = "IC", archivePath = archivePath),
            bundledPluginResolver = FixedPluginResolver(pluginFixture(tempDir.resolve("dist/ij-plugin"))),
        )

        val result = manager.download(parseBackendId("idea-community-2025.3.3"))

        assertEquals("idea-community-2025.3.3", result.id)
        assertEquals("IC", result.descriptor.productCode)
        assertEquals("sha-IC", result.descriptor.sourceArchiveSha256)
        assertTrue(descriptorPath(result.backendDir).exists())
        assertTrue(result.backendDir.resolve(result.descriptor.bundleDirName).resolve("product-info.json").exists())
    }

    @Test
    fun `download rejects product-info productCode mismatch and cleans partial install`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-community-2025.3.3"
        val backendDir = homePaths.backendDir(backendId)
        Files.createDirectories(backendDir)
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = backendId,
                productKey = "idea-community",
                productCode = "IC",
                version = "2025.3.3",
                buildNumber = "IC-253.1",
                bundleDirName = "stale-missing-bundle",
                launcherPath = "bin/idea.sh",
                downloadedAt = "2026-05-15T00:00:00Z",
            ),
        )
        val archivePath = fakeArchive(tempDir, "idea-2025.3-aarch64.dmg")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(productCode = "IU", archivePath = archivePath),
            bundledPluginResolver = FixedPluginResolver(pluginFixture(tempDir.resolve("dist/ij-plugin"))),
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.download(parseBackendId(backendId))
        }

        val unpackedBundle = backendDir.resolve("idea-IU-253.1")
        assertFalse(unpackedBundle.exists(), "mismatched bundle must be removed")
        assertFalse(descriptorPath(backendDir).exists(), "backend.json must be removed on validation failure")
        assertTrue(error.message!!.contains("idea-community (IIC)"), error.message)
        assertTrue(error.message!!.contains("Expected product-info.json productCode 'IC'"), error.message)
        assertTrue(error.message!!.contains("actual 'IU'"), error.message)
        assertTrue(error.message!!.contains("https://download.jetbrains.com/idea/idea-2025.3-aarch64.dmg"), error.message)
        assertTrue(error.message!!.contains(archivePath.toString()), error.message)
        assertTrue(error.message!!.contains(unpackedBundle.toString()), error.message)
    }

    @Test
    fun `validation accepts installed product code for each known product`(
        @TempDir tempDir: Path,
    ) {
        val expectedCodes = mapOf(
            IdeProduct.IntelliJIdeaCommunity to "IC",
            IdeProduct.IntelliJIdea to "IU",
            IdeProduct.PyCharmCommunity to "PC",
            IdeProduct.PyCharm to "PY",
            IdeProduct.GoLand to "GO",
            IdeProduct.WebStorm to "WS",
            IdeProduct.Rider to "RD",
            IdeProduct.CLion to "CL",
            IdeProduct.AndroidStudio to "AI",
        )
        assertEquals(expectedCodes.keys, IdeProduct.knownProducts.toSet())

        for ((product, expectedCode) in expectedCodes) {
            assertEquals(expectedCode, product.installedProductCode)
            val bundleDir = tempDir.resolve(product.id)
            Files.createDirectories(bundleDir)

            validateInstalledProductCode(
                product = product,
                actualProductCode = expectedCode,
                downloadedUrl = "https://example.invalid/${product.id}.tar.gz",
                archivePath = null,
                bundleDir = bundleDir,
                descriptorPath = tempDir.resolve("${product.id}.json"),
            )

            assertTrue(bundleDir.exists(), "matching validation must keep ${product.id} bundle")
        }
    }

    @Test
    fun `custom product installed product code falls back to products API code`() {
        val custom = IdeProduct.Custom(
            id = "rubymine",
            displayName = "RubyMine",
            code = "RM",
            launcherExecutable = "rubymine",
            licenseTier = com.jonnyzzz.mcpSteroid.ideDownloader.LicenseTier.FreeForNonCommercial,
        )

        assertEquals("RM", custom.installedProductCode)
    }

    private fun fakeArchive(tempDir: Path, fileName: String): Path {
        val archive = tempDir.resolve("downloads").resolve(fileName)
        Files.createDirectories(archive.parent)
        Files.writeString(archive, "fake archive")
        return archive
    }

    private fun pluginFixture(root: Path): Path {
        Files.createDirectories(root.resolve("lib"))
        Files.writeString(root.resolve("lib/plugin.txt"), "plugin")
        return root
    }

    private class FixedPluginResolver(private val dir: Path) : BundledPluginResolver {
        override fun resolveBundledPluginDir(): Path = dir
    }

    private class InstallingDownloader(
        private val productCode: String,
        private val archivePath: Path,
    ) : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = IdeProduct.IntelliJIdeaCommunity,
                version = id.version ?: "2025.3.3",
                build = "$productCode-253.1",
                url = "https://download.jetbrains.com/idea/${archivePath.fileName}",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact {
            val bundleDir = targetDir.resolve("idea-$productCode-253.1")
            Files.createDirectories(bundleDir.resolve("bin"))
            Files.writeString(
                bundleDir.resolve("product-info.json"),
                productInfo(productCode),
            )
            Files.writeString(bundleDir.resolve("bin/idea.sh"), "#!/usr/bin/env sh\n")
            Files.writeString(bundleDir.resolve("bin/idea.bat"), "@echo off\r\n")
            return BackendDownloadArtifact(
                sourceArchiveSha256 = "sha-$productCode",
                archivePath = archivePath,
            )
        }

        private fun productInfo(productCode: String): String =
            """
            {
              "productCode": "$productCode",
              "buildNumber": "$productCode-253.1",
              "launch": [
                { "os": "Linux", "launcherPath": "bin/idea.sh" },
                { "os": "macOS", "launcherPath": "bin/idea.sh" },
                { "os": "Windows", "launcherPath": "bin/idea.bat" }
              ]
            }
            """.trimIndent()
    }
}
