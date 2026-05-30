package com.jonnyzzz.mcpSteroid.integration.infra

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


data class ConsoleFileInfo(
    val runDir: File,
    val title: String,
)

fun allocRunDirAndTitle(consoleTitle: String): ConsoleFileInfo {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val runIdName = consoleTitle.split(" ").joinToString("-") { it.lowercase() }
    val file = File(IdeTestFolders.testOutputDir, "run-${timestamp}-${runIdName}")
    file.mkdirs()
    // Make the runDir world-writable on the HOST before we bind-mount it at
    // /mcp-run-dir inside the container. Linux bind mounts do not do UID
    // remapping: whatever host uid owns the dir controls write access from
    // inside the container. On TC Linux agents the dir is created by the
    // TC-agent user (uid e.g. 999), but the container process runs as
    // `agent` (uid 1000) — EACCES on every `mkdir /mcp-run-dir/<subdir>`.
    // On macOS/Docker Desktop the virtiofs / osxfs layer does this mapping
    // transparently, which is why the same code works locally but fails on
    // CI with: `mkdir -p /mcp-run-dir/video → exit 1` followed eventually
    // by `docker cp: Could not find the file /mcp-run-dir/intellij/temp`.
    //
    // `setWritable(true, ownerOnly=false)` maps to chmod a+w on Linux.
    // Add read+execute too via setReadable / setExecutable so the
    // container user can traverse the tree and read existing files.
    file.setReadable(true, /* ownerOnly = */ false)
    file.setWritable(true, /* ownerOnly = */ false)
    file.setExecutable(true, /* ownerOnly = */ false)
    return ConsoleFileInfo(file, "$consoleTitle $timestamp")
}

