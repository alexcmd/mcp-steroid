/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream

private fun parsePortOrZero(url: String): Int = try {
    URI(url).port.takeIf { it > 0 } ?: 0
} catch (e: Exception) {
    0
}

private fun ContainerDriver.copyIfPresent(localFile: File, containerPath: String) {
    if (localFile.isFile) {
        copyToContainer(localFile, containerPath)
    }
}

private const val NPX_PACKAGE_ARCHIVE_RESOURCE = "mcp-steroid-npx.zip"
private val npxExtractLock = Any()
@Volatile
private var cachedNpxPackageDir: File? = null
private object NpxProxyInstallerResourceAnchor

private fun extractNpxPackageDirFromResource(): File {
    val cached = cachedNpxPackageDir
    if (cached != null && cached.isDirectory) return cached

    synchronized(npxExtractLock) {
        val lockCached = cachedNpxPackageDir
        if (lockCached != null && lockCached.isDirectory) return lockCached

        val archiveStream = NpxProxyInstallerResourceAnchor::class.java.classLoader
            .getResourceAsStream(NPX_PACKAGE_ARCHIVE_RESOURCE)
            ?: error("Missing NPX package artifact resource: $NPX_PACKAGE_ARCHIVE_RESOURCE")

        val tempDir = extractZipStreamToTempDir(archiveStream)
        cachedNpxPackageDir = tempDir
        return tempDir
    }
}

private fun extractZipStreamToTempDir(archiveStream: InputStream): File {
    val tempDir = Files.createTempDirectory("mcp-steroid-npx-package").toFile()
    ZipInputStream(archiveStream).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val outFile = File(tempDir, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { output -> zip.copyTo(output) }
            }
            zip.closeEntry()
        }
    }
    return tempDir
}

private fun ContainerDriver.deployNpxProxy(
    hostPackageDir: File,
    ideMcpUrl: String,
    userHome: String,
): StdioMcpCommand {
    val hostDistFile = hostPackageDir.resolve("dist/index.js")
    val hostPackageJson = hostPackageDir.resolve("package.json")
    val hostLockFile = hostPackageDir.resolve("package-lock.json")

    require(hostPackageJson.isFile) {
        "NPX package.json is missing at ${hostPackageJson.path}"
    }
    require(hostDistFile.isFile) {
        "NPX proxy dist is missing at ${hostDistFile.path}. Run: npm --prefix npx run build"
    }

    val guestDir = "/tmp/mcp-steroid-npx"
    val guestDistDir = "$guestDir/dist"
    val guestIndex = "$guestDistDir/index.js"
    val guestPackageJson = "$guestDir/package.json"
    val guestLockFile = "$guestDir/package-lock.json"
    val guestConfig = "$guestDir/proxy.json"
    val markerPid = 1L
    val markerPath = "$userHome/${PidMarker.fileNameFor(markerPid)}"

    mkdirs(guestDir)
    mkdirs(guestDistDir)
    copyToContainer(hostPackageJson, guestPackageJson)
    copyIfPresent(hostLockFile, guestLockFile)
    copyToContainer(hostDistFile, guestIndex)

    writeFileInContainer(
        guestConfig,
        """
        {
          "homeDir": "$userHome",
          "scanIntervalMs": 1000,
          "allowHosts": ["host.docker.internal", "localhost", "127.0.0.1"],
          "upstreamTimeoutMs": 15000
        }
        """.trimIndent(),
        executable = false,
    )

    val fakeMarkerPort = parsePortOrZero(ideMcpUrl)
    val fakeMarker = PidMarker(
        pid = markerPid,
        mcpUrl = ideMcpUrl,
        port = fakeMarkerPort,
        token = "test-helper-fake-token",
        ide = IdeInfo(
            name = "IntelliJ IDEA (test-helper fake)",
            version = "0.0.0",
            build = "test"
        ),
        plugin = PluginInfo(
            id = "com.jonnyzzz.mcpSteroid",
            name = "MCP Steroid (test-helper fake)",
            version = "0.0.0"
        ),
        createdAt = "2026-01-01T00:00:00Z",
    )

    writeFileInContainer(
        markerPath,
        PidMarkerJson.encode(fakeMarker) + "\n",
        executable = false,
    )

    return StdioMcpCommand(
        command = "node",
        args = listOf(guestIndex, "--config", guestConfig)
    )
}

fun ContainerDriver.prepareNpxProxyForUrl(
    ideMcpUrl: String,
    userHome: String,
): StdioMcpCommand {
    val hostPackageDir = extractNpxPackageDirFromResource()
    return deployNpxProxy(hostPackageDir, ideMcpUrl, userHome)
}

fun ContainerDriver.prepareNpxProxyFromZipFile(
    npxZipFile: File,
    ideMcpUrl: String,
    userHome: String,
): StdioMcpCommand {
    val hostPackageDir = extractZipStreamToTempDir(npxZipFile.inputStream())
    return deployNpxProxy(hostPackageDir, ideMcpUrl, userHome)
}
