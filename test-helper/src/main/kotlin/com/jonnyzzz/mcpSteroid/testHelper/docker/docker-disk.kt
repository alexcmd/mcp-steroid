/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.io.File

fun ContainerDriver.mkdirs(guestPath: String): ProcessResult {
    return startProcessInContainer {
        this
            .args("mkdir", "-p", guestPath)
            .description("Create directory $guestPath in the container")
            .quietly()
    }.awaitForProcessFinish()
}

fun ContainerDriver.copyFromContainer(containerPath: String, localPath: File) {
    localPath.parentFile?.mkdirs()
    // Direct host access when the guest path is under a bind mount — no `docker cp` process.
    mapGuestPathToHostPathOrNull(containerPath)?.let { hostPath ->
        require(hostPath.exists()) { "Mapped host path does not exist for $containerPath: $hostPath" }
        hostPath.copyTo(localPath, overwrite = true)
        return
    }
    newRunOnHost()
        .command("docker", "cp", "$containerId:$containerPath", localPath.absolutePath)
        .description("Copy container:$containerPath to ${localPath.name}")
        .timeoutSeconds(300L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to copy from container: $containerPath to $localPath: $stderr" }
}

fun ContainerDriver.copyToContainer(localPath: File, containerPath: String) {
    require(localPath.exists()) { "Local path does not exist: $localPath" }
    // Direct host access when the guest path is under a bind mount — no `docker cp` process.
    mapGuestPathToHostPathOrNull(containerPath)?.let { hostPath ->
        hostPath.parentFile?.mkdirs()
        localPath.copyTo(hostPath, overwrite = true)
        return
    }
    newRunOnHost()
        .command("docker", "cp", localPath.absolutePath, "$containerId:$containerPath")
        .description("Copy ${localPath.name} to container:$containerPath")
        .timeoutSeconds(120L)
        .quietly()
        .startProcess()
        .assertExitCode(0) { "Failed to copy to container: $localPath: $stderr" }
}

/**
 * Write [content] to [containerPath]. Implemented over [copyToContainer] — a host temp file is
 * staged then copied in — so there is no content-size limit and it transparently uses direct
 * host-filesystem access when the path is under a bind mount (no `docker exec`/heredoc).
 */
fun ContainerDriver.writeFileInContainer(
    containerPath: String,
    content: String,
    executable: Boolean = false,
) {
    val tmp = File.createTempFile("write-in-container-", ".tmp")
    try {
        tmp.writeText(content)
        // `docker cp` (container-local target) needs the parent dir to exist; the host-mapped
        // branch of copyToContainer creates the host parent itself.
        val parentDir = containerPath.substringBeforeLast('/')
        if (parentDir.isNotEmpty() && mapGuestPathToHostPathOrNull(containerPath) == null) {
            mkdirs(parentDir).assertExitCode(0) { "Failed to create directory in container $parentDir: $stderr" }
        }
        copyToContainer(tmp, containerPath)
    } finally {
        tmp.delete()
    }

    if (executable) {
        // chmod works for both host-mapped and container-local targets.
        startProcessInContainer {
            this
                .args("chmod", "+x", containerPath)
                .description("chmod +x $containerPath")
                .timeoutSeconds(5)
                .quietly()
        }.assertExitCode(0) { "Failed to chmod the created file in container to $containerPath: $stderr" }
    }
}
