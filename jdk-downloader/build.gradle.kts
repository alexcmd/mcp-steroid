import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.file.RelativePath

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

val correttoVersion = "21.0.11.10.1"
val correttoBaseUrl = "https://corretto.aws/downloads/resources/$correttoVersion"

val downloadConnectTimeoutMs = 30_000
val downloadReadTimeoutMs = 15 * 60_000
val downloadRetryCount = 5

fun Download.configureReliableDownload() {
    onlyIfModified(true)
    connectTimeout(downloadConnectTimeoutMs)
    readTimeout(downloadReadTimeoutMs)
    retries(downloadRetryCount)
    tempAndMove(true)
}

data class CorrettoPlatform(
    val id: String,
    val archiveSuffix: String,
)

val correttoPlatforms = listOf(
    CorrettoPlatform("linux-amd64", "linux-x64.tar.gz"),
    CorrettoPlatform("linux-arm", "linux-aarch64.tar.gz"),
    CorrettoPlatform("mac-arm", "macosx-aarch64.tar.gz"),
    CorrettoPlatform("windows-amd64", "windows-x64-jdk.zip"),
)

val correttoDownloadDir = layout.buildDirectory.dir("jdk-download")
val correttoExtractDir = layout.buildDirectory.dir("jdk-extracted")
val jdkDownloaderTaskGroup = "jdk downloader"

val downloadAllJdks by tasks.registering {
    group = jdkDownloaderTaskGroup
    description = "Download all pinned Amazon Corretto JDK archives."
}

val extractAllJdks by tasks.registering {
    group = jdkDownloaderTaskGroup
    description = "Extract all pinned Amazon Corretto JDK archives."
    dependsOn(downloadAllJdks)
    outputs.dir(correttoExtractDir)
    outputs.upToDateWhen { false }

    doLast {
        correttoPlatforms.forEach { platform ->
            val root = correttoExtractDir.get().dir(platform.id).asFile
            listOf("legal", "release", "version.txt").forEach { rel ->
                require(root.resolve(rel).exists()) {
                    "missing $rel inside ${platform.id} after extract — " +
                        "Corretto layout may have changed for ${platform.archiveSuffix}"
                }
            }
            if (!root.resolve("README.md").isFile) {
                logger.warn("README.md not found in ${platform.id} extracted tree")
            }
        }
    }
}

correttoPlatforms.forEach { platform ->
    val archiveFileName = "amazon-corretto-$correttoVersion-${platform.archiveSuffix}"
    val downloadTask = tasks.register<Download>("downloadJdk_${platform.id}") {
        group = jdkDownloaderTaskGroup
        description = "Download Amazon Corretto JDK $correttoVersion for ${platform.id}."
        val destFile = correttoDownloadDir.get().asFile.resolve(archiveFileName)
        src("$correttoBaseUrl/$archiveFileName")
        dest(destFile)
        configureReliableDownload()
        // Corretto artefacts are immutable for a pinned version; skip if already on disk.
        onlyIf { !destFile.exists() }
    }
    downloadAllJdks.configure { dependsOn(downloadTask) }

    val extractTask = tasks.register<Sync>("extractJdk_${platform.id}") {
        group = jdkDownloaderTaskGroup
        description = "Extract Amazon Corretto JDK $correttoVersion for ${platform.id}."
        dependsOn(downloadTask)
        val archiveProvider = downloadTask.map { it.outputs.files.singleFile }
        val isZip = platform.archiveSuffix.endsWith(".zip")
        val tree = archiveProvider.map { archive ->
            if (isZip) zipTree(archive) else tarTree(resources.gzip(archive))
        }
        from(tree) {
            if (platform.id == "mac-arm") {
                // Apple-bundle leftovers that sit outside Contents/Home are not part of
                // the JDK root consumed by downstream distributions.
                exclude("amazon-corretto-21.jdk/Contents/MacOS/**")
                exclude("amazon-corretto-21.jdk/Contents/Info.plist")
                exclude("amazon-corretto-21.jdk/PkgInfo")
            }
            eachFile {
                val parts = relativePath.segments
                if (parts.isEmpty()) {
                    exclude()
                    return@eachFile
                }
                val stripDepth = if (platform.id == "mac-arm") 3 else 1
                if (parts.size <= stripDepth) {
                    exclude()
                    return@eachFile
                }
                relativePath = RelativePath(true, *parts.drop(stripDepth).toTypedArray())
            }
            includeEmptyDirs = false
        }
        into(correttoExtractDir.map { it.dir(platform.id) })
    }
    extractAllJdks.configure { dependsOn(extractTask) }
}
