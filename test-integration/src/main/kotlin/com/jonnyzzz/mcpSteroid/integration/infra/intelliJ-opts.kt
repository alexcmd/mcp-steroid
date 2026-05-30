package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import java.io.File

data class IntelliJContainerOpts(
    val dockerFileBase: String = "ide-agent",

    val consoleTitle: String,

    val project : IntelliJProject = IntelliJProject.TestProject,

    val layoutManager : LayoutManager = HorizontalLayoutManager(),

    val distribution: IdeDistribution = IdeDistribution.fromSystemProperties(),

    val aiMode: AiMode = AiMode.AI_MCP,
    /**
     * Explicit MCP connection mode for AI agents.
     *
     * When provided, takes precedence over [aiMode] for MCP connectivity.
     * Use [McpConnectionMode.None] to create a container where agents have NO MCP registered
     * (baseline / control group) while still producing real-time console output and log files.
     *
     * When null (default), the mode is derived from [aiMode].
     */
    val mcpConnectionMode: McpConnectionMode? = null,

    val repoCacheDir: File? = IdeTestFolders.repoCacheDirOrNull,
    /**
     * When true, mounts the host Docker socket (`/var/run/docker.sock`) into the container
     * at the same path so Testcontainers-based tests can start sibling Docker containers.
     *
     * Requirements:
     * - The host Docker socket must exist at `/var/run/docker.sock`
     * - The `ide-base` Docker image must have `docker-ce-cli` installed (already done) and
     *   the `agent` user must be in the `docker` group (already done in the Dockerfile)
     *
     * Default: `false` (Docker socket not mounted — arena tests that use Testcontainers
     * will fail with "Could not find a valid Docker environment" unless this is enabled).
     */
    val mountDockerSocket: Boolean = false,
    /**
     * When true, forwards the host SSH agent socket into the container and sets SSH_AUTH_SOCK.
     * Required for git operations that use SSH remotes/private keys from inside the container.
     */
    val mountSshAgent: Boolean = true,
    /**
     * Optional prebuilt image to start from (for warm snapshot reuse).
     * When provided, IDE archive download/build is skipped and this image is used directly.
     */
    val sourceImage: ImageDriver? = null,

    /**
     * Reuse project sources from [sourceImage] instead of re-deploying project files/clone.
     * Use together with warm snapshot images that already contain project checkout + ide-system.
     */
    val reuseProjectFromImage: Boolean = false,
    /**
     * Default true keeps ordinary Docker tests immune to trust prompts. Tests that validate
     * project-trust behavior can set this false and rely on explicit trusted paths.
     */
    val disableProjectTrustChecks: Boolean = true,
    /**
     * Default true mirrors the historical test image setup that trusts every path. Tests that
     * need an actually-untrusted secondary project can set this false.
     */
    val trustAllProjectPaths: Boolean = true,
)
