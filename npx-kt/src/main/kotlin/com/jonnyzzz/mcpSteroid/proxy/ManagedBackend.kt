/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.proxy

import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeChannel
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeDistribution
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveAndDownload
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveArchive
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.unpackIdeArchive
import com.jonnyzzz.mcpSteroid.ideDownloader.writeIdeStartupConfigFiles
import com.jonnyzzz.mcpSteroid.ideDownloader.writeIdeUserStartupConfigFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.jvm.optionals.getOrNull
import kotlin.streams.asSequence

@Serializable
internal data class BackendDescriptor(
    val schemaVersion: Int = 1,
    val id: String,
    val productKey: String,
    val productCode: String,
    val version: String,
    val buildNumber: String? = null,
    val bundleDirName: String,
    val launcherPath: String,
    val downloadedAt: String,
    val sourceArchiveSha256: String? = null,
)

internal data class DownloadResult(
    val id: String,
    val descriptor: BackendDescriptor,
    val backendDir: Path,
    val vmOptionsPath: Path,
)

internal data class StartResult(
    val id: String,
    val pid: Long,
    val ideaLogPath: Path,
    val configPath: Path,
    val alreadyRunning: Boolean = false,
)

internal data class StopResult(
    val id: String,
    val pid: Long?,
    val outcome: String,
)

internal enum class ManagedBackendState {
    INSTALLED,
    RUNNING,
    UNREACHABLE,
}

internal data class ManagedBackendInfo(
    val id: String,
    val productKey: String,
    val productCode: String,
    val version: String,
    val buildNumber: String?,
    val installPath: Path,
    val cachePath: Path,
    val runningPid: Long?,
    val state: ManagedBackendState,
)

internal data class BackendDownloadResolution(
    val product: IdeProduct,
    val version: String,
    val build: String,
    val url: String,
)

internal interface ManagedBackendDownloader {
    suspend fun resolve(id: BackendId): BackendDownloadResolution

    suspend fun downloadAndUnpack(
        resolution: BackendDownloadResolution,
        targetDir: Path,
    ): String?
}

internal interface BundledPluginResolver {
    fun resolveBundledPluginDir(): Path
}

internal data class ProcessSnapshot(
    val pid: Long,
    val command: String?,
)

internal interface ManagedProcessInspector {
    fun isAlive(pid: Long): Boolean
    fun allProcesses(): List<ProcessSnapshot>
}

internal object DefaultManagedProcessInspector : ManagedProcessInspector {
    override fun isAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).getOrNull()?.isAlive == true

    override fun allProcesses(): List<ProcessSnapshot> =
        ProcessHandle.allProcesses().use { stream ->
            stream.asSequence()
                .map { handle -> ProcessSnapshot(handle.pid(), handle.info().command().orElse(null)) }
                .toList()
        }
}

internal class ManagedBackendLockException(message: String) : RuntimeException(message)

internal interface ManagedBackendService {
    suspend fun download(id: BackendId): DownloadResult
    suspend fun start(id: BackendId): StartResult
    suspend fun stop(id: BackendId): StopResult
}

internal class ClasspathBundledPluginResolver : BundledPluginResolver {
    override fun resolveBundledPluginDir(): Path {
        val pluginDir = NpxKtRoot.ijPluginDir().toAbsolutePath().normalize()
        require(Files.isDirectory(pluginDir)) {
            "Bundled ij-plugin/ directory is missing: $pluginDir. " +
                "Build and launch devrig from :npx-kt:installDist so the bundled plugin is available."
        }
        return pluginDir
    }
}

internal class DefaultManagedBackendDownloader(
    private val archiveDownloadDir: Path,
    private val os: HostOs = resolveHostOs(),
) : ManagedBackendDownloader {
    override suspend fun resolve(id: BackendId): BackendDownloadResolution = withContext(Dispatchers.IO) {
        val archive = resolveArchive(
            product = id.product,
            channel = IdeChannel.STABLE,
            os = os,
            version = id.version,
        )
        BackendDownloadResolution(
            product = archive.product,
            version = archive.version,
            build = archive.build,
            url = archive.url,
        )
    }

    override suspend fun downloadAndUnpack(
        resolution: BackendDownloadResolution,
        targetDir: Path,
    ): String? = withContext(Dispatchers.IO) {
        val distribution = IdeDistribution.FromUrl(product = resolution.product, url = resolution.url)

        Files.createDirectories(archiveDownloadDir)
        val archive = distribution.resolveAndDownload(archiveDownloadDir.toFile(), os = os)
        unpackIdeArchive(archive, targetDir.toFile())
        sha256(archive.toPath())
    }
}

internal class BackendManager(
    private val homePaths: HomePaths,
    private val downloader: ManagedBackendDownloader = DefaultManagedBackendDownloader(
        archiveDownloadDir = homePaths.cachesDir.resolve("_archives"),
    ),
    private val launcherResolver: LauncherResolver = LauncherResolver(),
    private val bundledPluginResolver: BundledPluginResolver = ClasspathBundledPluginResolver(),
    private val processInspector: ManagedProcessInspector = DefaultManagedProcessInspector,
    private val ideUserHome: Path = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
    private val stopGracePeriodMillis: Long = 5_000L,
) : ManagedBackendService {
    override suspend fun download(id: BackendId): DownloadResult {
        homePaths.mkdirsAll()
        val resolution = downloader.resolve(id)
        val resolved = ResolvedBackendId(resolution.product, resolution.version)
        val backendDir = homePaths.backendDir(resolved.id)
        Files.createDirectories(backendDir)

        val descriptorPath = descriptorPath(backendDir)
        val existingDescriptor = readDescriptorOrNull(descriptorPath)
        val archiveSha = if (existingDescriptor == null || !backendDir.resolve(existingDescriptor.bundleDirName).isDirectory()) {
            downloader.downloadAndUnpack(resolution, backendDir)
        } else {
            existingDescriptor.sourceArchiveSha256
        }

        val bundleDir = resolveBundleDir(backendDir)
        val launcher = launcherResolver.resolve(bundleDir)
        val vmOptionsPath = writeBackendVmOptions(homePaths, resolved.id, bundleDir.fileName.toString())
        val descriptor = BackendDescriptor(
            id = resolved.id,
            productKey = resolution.product.id,
            productCode = launcher.productCode ?: resolution.product.code,
            version = resolution.version,
            buildNumber = launcher.buildNumber ?: resolution.build,
            bundleDirName = bundleDir.fileName.toString(),
            launcherPath = launcher.launcherPath,
            downloadedAt = existingDescriptor?.downloadedAt ?: Instant.now().toString(),
            sourceArchiveSha256 = archiveSha,
        )
        writeDescriptor(descriptorPath, descriptor)
        deployMcpSteroidPlugin(resolved.id)
        return DownloadResult(resolved.id, descriptor, backendDir, vmOptionsPath)
    }

    fun deployMcpSteroidPlugin(id: String): Path {
        val source = bundledPluginResolver.resolveBundledPluginDir()
        require(Files.isDirectory(source)) { "Bundled ij-plugin/ directory is missing: $source" }
        val target = homePaths.cacheDir(id).resolve("plugins/mcp-steroid")
        deleteRecursively(target)
        copyDirectory(source, target)
        return target
    }

    override suspend fun start(id: BackendId): StartResult {
        homePaths.mkdirsAll()
        val resolved = resolveConcreteId(id)
        val descriptor = loadDescriptor(resolved)
        val running = scanRunningManagedProcesses()
        val other = running.firstOrNull { it.backendId != resolved.id }
        if (other != null) {
            throw ManagedBackendLockException(lockConflictMessage(other))
        }
        val existing = running.firstOrNull { it.backendId == resolved.id }
        if (existing != null) {
            return StartResult(
                id = resolved.id,
                pid = existing.pid,
                ideaLogPath = homePaths.cacheDir(resolved.id).resolve("logs/idea.log"),
                configPath = homePaths.cacheDir(resolved.id).resolve("config"),
                alreadyRunning = true,
            )
        }

        val pidFile = homePaths.pidFile(resolved.id)
        val bundleDir = homePaths.backendDir(resolved.id).resolve(descriptor.bundleDirName)
        val launcher = bundleDir.resolve(descriptor.launcherPath)
        require(Files.isExecutable(launcher)) { "Launcher is not executable: $launcher" }

        writeBackendVmOptions(homePaths, resolved.id, descriptor.bundleDirName)
        val cacheDir = homePaths.cacheDir(resolved.id)
        val logDir = cacheDir.resolve("logs")
        listOf("config", "system", "logs", "plugins").forEach { Files.createDirectories(cacheDir.resolve(it)) }
        writeIdeStartupConfigFiles(cacheDir.resolve("config"))
        writeIdeUserStartupConfigFiles(ideUserHome)

        val stdoutLog = logDir.resolve("devrig-launcher.out.log").toFile()
        val stderrLog = logDir.resolve("devrig-launcher.err.log").toFile()
        val process = ProcessBuilder(launcher.toString())
            .directory(bundleDir.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutLog))
            .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
            .start()

        Files.createDirectories(pidFile.parent)
        Files.writeString(pidFile, "${process.pid()}\n")
        return StartResult(
            id = resolved.id,
            pid = process.pid(),
            ideaLogPath = logDir.resolve("idea.log"),
            configPath = cacheDir.resolve("config"),
        )
    }

    override suspend fun stop(id: BackendId): StopResult {
        val resolved = resolveConcreteId(id)
        val pidFile = homePaths.pidFile(resolved.id)
        val pid = readPid(pidFile)
        if (pid == null) {
            Files.deleteIfExists(pidFile)
            return StopResult(resolved.id, pid = null, outcome = "not running")
        }

        val handle = ProcessHandle.of(pid).getOrNull()
        if (handle == null || !handle.isAlive) {
            deleteMcpMarker(pid)
            Files.deleteIfExists(pidFile)
            return StopResult(resolved.id, pid = pid, outcome = "already stopped")
        }

        handle.destroy()
        val graceful = waitForExit(handle, timeoutMillis = stopGracePeriodMillis)
        val outcome = if (graceful) {
            "stopped"
        } else {
            handle.destroyForcibly()
            waitForExit(handle, timeoutMillis = 5_000L)
            "killed"
        }
        deleteMcpMarker(pid)
        Files.deleteIfExists(pidFile)
        return StopResult(resolved.id, pid = pid, outcome = outcome)
    }

    private fun deleteMcpMarker(pid: Long) {
        val marker = ideUserHome.resolve(PidMarker.fileNameFor(pid))
        try {
            Files.deleteIfExists(marker)
        } catch (e: Exception) {
            System.err.println("WARN: failed to delete MCP Steroid marker file $marker: ${e.message}")
        }
    }

    fun list(): List<ManagedBackendInfo> {
        if (!Files.isDirectory(homePaths.backendsDir)) return emptyList()
        return Files.list(homePaths.backendsDir).use { stream ->
            stream.asSequence()
                .filter { it.isDirectory() }
                .mapNotNull { dir ->
                    try {
                        val descriptor = readDescriptorOrNull(descriptorPath(dir)) ?: return@mapNotNull null
                        val pid = readPid(homePaths.pidFile(descriptor.id))
                        val alivePid = pid?.takeIf { processInspector.isAlive(it) }
                        val state = when {
                            alivePid != null -> ManagedBackendState.RUNNING
                            pid != null -> ManagedBackendState.UNREACHABLE
                            else -> ManagedBackendState.INSTALLED
                        }
                        ManagedBackendInfo(
                            id = descriptor.id,
                            productKey = descriptor.productKey,
                            productCode = descriptor.productCode,
                            version = descriptor.version,
                            buildNumber = descriptor.buildNumber,
                            installPath = dir,
                            cachePath = homePaths.cacheDir(descriptor.id),
                            runningPid = alivePid,
                            state = state,
                        )
                    } catch (e: Exception) {
                        System.err.println("WARN: failed to read managed backend metadata from $dir: ${e.message}")
                        null
                    }
                }
                .sortedWith(compareBy({ it.productKey }, { it.version }))
                .toList()
        }
    }

    private suspend fun resolveConcreteId(id: BackendId): ResolvedBackendId {
        if (id.version != null) return ResolvedBackendId(id.product, id.version)
        val resolution = downloader.resolve(id)
        return ResolvedBackendId(resolution.product, resolution.version)
    }

    private fun loadDescriptor(id: ResolvedBackendId): BackendDescriptor {
        val path = descriptorPath(homePaths.backendDir(id.id))
        return readDescriptorOrNull(path)
            ?: error("Managed backend '${id.id}' is not installed. Run `devrig backend download ${id.product.id}` first.")
    }

    private fun scanRunningManagedProcesses(): List<RunningManagedProcess> {
        val byId = mutableListOf<RunningManagedProcess>()
        val trackedPids = mutableSetOf<Long>()
        val trackedIds = mutableSetOf<String>()
        if (Files.isDirectory(homePaths.stateDir)) {
            Files.list(homePaths.stateDir).use { stream ->
                stream.asSequence()
                    .filter { it.fileName.toString().endsWith(".pid") }
                    .forEach { pidFile ->
                        val backendId = pidFile.fileName.toString().removeSuffix(".pid")
                        val pid = readPid(pidFile)
                        if (pid != null && processInspector.isAlive(pid)) {
                            trackedPids += pid
                            trackedIds += backendId
                            byId += RunningManagedProcess(backendId, pid, untracked = false)
                        } else {
                            Files.deleteIfExists(pidFile)
                        }
                    }
            }
        }

        for (process in processInspector.allProcesses()) {
            if (process.pid in trackedPids) continue
            val backendId = backendIdFromCommand(process.command) ?: continue
            if (backendId in trackedIds) continue
            byId += RunningManagedProcess(backendId, process.pid, untracked = true)
        }

        return byId.sortedWith(compareBy({ it.backendId }, { it.pid }))
    }

    private fun backendIdFromCommand(command: String?): String? {
        if (command.isNullOrBlank()) return null
        val backendsRoot = homePaths.backendsDir.toAbsolutePath().normalize().toString().pathKey() + "/"
        val commandPath = Path.of(command).toAbsolutePath().normalize().toString().pathKey()
        val index = commandPath.indexOf(backendsRoot)
        if (index < 0) return null
        val rest = commandPath.substring(index + backendsRoot.length)
        return rest.substringBefore('/').takeIf { it.isNotBlank() }
    }

    private fun lockConflictMessage(process: RunningManagedProcess): String = buildString {
        appendLine("error: another managed backend is already running: ${process.backendId} (pid ${process.pid})")
        append("stop it first:  devrig backend stop ${process.backendId}")
        if (process.untracked) {
            appendLine()
            append("cleanup stale state under ${homePaths.stateDir} if this process is no longer managed")
        }
    }
}

private data class RunningManagedProcess(
    val backendId: String,
    val pid: Long,
    val untracked: Boolean,
)

private fun String.pathKey(): String =
    replace('\\', '/').trimEnd('/').lowercase()

internal fun writeBackendVmOptions(homePaths: HomePaths, id: String, bundleDirName: String): Path {
    val cacheDir = homePaths.cacheDir(id).toAbsolutePath().normalize()
    listOf("config", "system", "logs", "plugins", "execution-storage").forEach { Files.createDirectories(cacheDir.resolve(it)) }
    Files.createDirectories(homePaths.backendDir(id))
    val path = homePaths.backendDir(id).resolve("$bundleDirName.vmoptions")
    val content = buildString {
        appendLine("-Didea.config.path=${cacheDir.resolve("config")}")
        appendLine("-Didea.system.path=${cacheDir.resolve("system")}")
        appendLine("-Didea.log.path=${cacheDir.resolve("logs")}")
        appendLine("-Didea.plugins.path=${cacheDir.resolve("plugins")}")
        appendLine("-Didea.vendor.name=devrig (managed)")
        appendLine("-Xms256m")
        appendLine("-Xmx2048m")
        appendLine("-Dmcp.steroid.review.mode=NEVER")
        appendLine("-Dmcp.steroid.updates.enabled=false")
        appendLine("-Dmcp.steroid.analytics.enabled=false")
        appendLine("-Dmcp.steroid.idea.description.enabled=false")
        appendLine("-Dmcp.steroid.dialog.killer.enabled=true")
        appendLine("-Dmcp.steroid.storage.path=${cacheDir.resolve("execution-storage")}")
        appendLine("-Djb.consents.confirmation.enabled=false")
        appendLine("-Djb.privacy.policy.text=<!--999.999-->")
        appendLine("-Djb.privacy.policy.ai.assistant.text=<!--999.999-->")
        appendLine("-Dmarketplace.eula.reviewed.and.accepted=true")
        appendLine("-Dwriterside.eula.reviewed.and.accepted=true")
        appendLine("-Didea.initially.ask.config=never")
        appendLine("-Dide.newUsersOnboarding=false")
        appendLine("-Dnosplash=true")
    }
    Files.writeString(path, content)
    return path
}

internal fun descriptorPath(backendDir: Path): Path = backendDir.resolve("backend.json")

internal fun readDescriptorOrNull(path: Path): BackendDescriptor? {
    if (!path.exists()) return null
    return backendJson.decodeFromString<BackendDescriptor>(Files.readString(path))
}

internal fun writeDescriptor(path: Path, descriptor: BackendDescriptor) {
    Files.createDirectories(path.parent)
    Files.writeString(path, backendJson.encodeToString(descriptor) + "\n")
}

private val backendJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private fun resolveBundleDir(backendDir: Path): Path {
    val candidates = Files.list(backendDir).use { stream ->
        stream.asSequence()
            .filter { it.isDirectory() }
            .filter { hasProductInfoCandidate(it) }
            .toList()
    }
    require(candidates.isNotEmpty()) {
        "No IntelliJ bundle with product-info.json found in $backendDir"
    }
    require(candidates.size == 1) {
        "Expected exactly one IntelliJ bundle in $backendDir, found: ${candidates.joinToString { it.fileName.toString() }}"
    }
    return candidates.single()
}

private fun hasProductInfoCandidate(dir: Path): Boolean {
    return listOf(
        dir.resolve("product-info.json"),
        dir.resolve("Contents/Resources/product-info.json"),
    ).any { it.exists() }
}

private fun readPid(path: Path): Long? {
    if (!path.exists()) return null
    val text = Files.readString(path).trim()
    if (text.isBlank()) return null
    return text.toLongOrNull()
}

private fun waitForExit(handle: ProcessHandle, timeoutMillis: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (!handle.isAlive) return true
        Thread.sleep(200L)
    }
    return !handle.isAlive
}

private fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { stream ->
        stream.asSequence().forEach { path ->
            val relative = source.relativize(path)
            val destination = target.resolve(relative)
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}

private fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { stream ->
        stream.asSequence()
            .sortedWith(compareByDescending { it.nameCount })
            .forEach { Files.deleteIfExists(it) }
    }
}

private fun nullDevice(): File {
    return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) File("NUL") else File("/dev/null")
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
