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
// Corretto publishes the PGP key for this release under the signing key id.
val correttoPublicKeyUrl = "$correttoBaseUrl/A122542AB04F24E3.pub"

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

// Resolvable: consumes :pgp-verifier's installDist directory through
// the same "install-dist" Usage attribute :ocr-tesseract already
// exposes. The verifier runs as an external process so :jdk-downloader's
// buildscript classpath stays small.
val pgpVerifierInstallDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "install-dist"))
    }
}

dependencies {
    pgpVerifierInstallDist(project(":pgp-verifier"))
}

val pgpVerifierLauncher = pgpVerifierInstallDist.elements.map { elements ->
    val installDir = elements.single().asFile
    installDir.resolve("bin").resolve(
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) "pgp-verifier.bat" else "pgp-verifier"
    )
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
    description = "Download all pinned Amazon Corretto JDK archives and signature verification files."
}

val downloadCorrettoPublicKey by tasks.registering(Download::class) {
    group = jdkDownloaderTaskGroup
    description = "Download the Amazon Corretto public key used to verify JDK signatures."
    val destFile = correttoDownloadDir.get().asFile.resolve("corretto-pubkey.asc")
    src(correttoPublicKeyUrl)
    dest(destFile)
    configureReliableDownload()
    onlyIf { !destFile.exists() }
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
    val downloadSigTask = tasks.register<Download>("downloadJdkSig_${platform.id}") {
        group = jdkDownloaderTaskGroup
        description = "Download the Amazon Corretto JDK $correttoVersion detached PGP signature for ${platform.id}."
        val destFile = correttoDownloadDir.get().asFile.resolve("$archiveFileName.sig")
        src("$correttoBaseUrl/$archiveFileName.sig")
        dest(destFile)
        configureReliableDownload()
        // Corretto signatures are immutable for a pinned version; skip if already on disk.
        onlyIf { !destFile.exists() }
    }
    downloadAllJdks.configure { dependsOn(downloadTask, downloadSigTask, downloadCorrettoPublicKey) }

    val verifyTask = tasks.register<Exec>("verifyJdk_${platform.id}") {
        group = "verification"
        description = "Verify the PGP signature for Amazon Corretto JDK $correttoVersion on ${platform.id}."
        dependsOn(downloadTask, downloadSigTask, downloadCorrettoPublicKey)
        inputs.file(downloadTask.map { it.outputs.files.singleFile })
        inputs.file(downloadSigTask.map { it.outputs.files.singleFile })
        inputs.file(downloadCorrettoPublicKey.map { it.outputs.files.singleFile })
        inputs.file(pgpVerifierLauncher)
        doFirst {
            val archive = downloadTask.get().outputs.files.singleFile
            val signature = downloadSigTask.get().outputs.files.singleFile
            val publicKey = downloadCorrettoPublicKey.get().outputs.files.singleFile
            val launcher = pgpVerifierLauncher.get()
            commandLine(
                launcher.absolutePath,
                archive.absolutePath,
                signature.absolutePath,
                publicKey.absolutePath,
            )
        }
        doLast {
            logger.lifecycle("[jdk-downloader] verified ${platform.id} signature via pgp-verifier")
        }
    }

    val extractTask = tasks.register<Sync>("extractJdk_${platform.id}") {
        group = jdkDownloaderTaskGroup
        description = "Extract Amazon Corretto JDK $correttoVersion for ${platform.id}."
        dependsOn(downloadTask, verifyTask)
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
            if (!isZip) {
                filesMatching("**/bin/*") {
                    permissions { unix("rwxr-xr-x") }
                }
                filesMatching("**/lib/jspawnhelper") {
                    permissions { unix("rwxr-xr-x") }
                }
                filesMatching("**/lib/**/*.so") {
                    permissions { unix("rwxr-xr-x") }
                }
                filesMatching("**/lib/**/*.dylib") {
                    permissions { unix("rwxr-xr-x") }
                }
            }
            includeEmptyDirs = false
        }
        into(correttoExtractDir.map { it.dir(platform.id) })
    }
    extractAllJdks.configure { dependsOn(extractTask) }
}

val verifyAllJdks by tasks.registering {
    group = "verification"
    description = "Verify the PGP signature of every Corretto JDK archive."
    dependsOn(correttoPlatforms.map { tasks.named("verifyJdk_${it.id}") })
}

val posixAuditPlatforms = correttoPlatforms.filter { it.id != "windows-amd64" }

val auditJdkPermissions by tasks.registering {
    group = "verification"
    description = "Audit POSIX +x bits on extracted JDK trees (Linux + macOS)."

    doLast {
        logger.lifecycle("[jdk-downloader] permission audit skipped: windows-amd64 (no POSIX modes)")
    }
}

posixAuditPlatforms.forEach { platform ->
    val extract = tasks.named("extractJdk_${platform.id}")
    val audit = tasks.register("auditJdkPermissions_${platform.id}") {
        group = "verification"
        description = "Audit POSIX +x bits on extracted JDK tree for ${platform.id}."
        dependsOn(extract)

        val rootProvider = correttoExtractDir.map { it.dir(platform.id) }
        inputs.dir(rootProvider)

        doLast {
            val root = rootProvider.get().asFile
            val failures = mutableListOf<String>()

            val binDir = root.resolve("bin")
            require(binDir.isDirectory) { "missing bin/ in ${platform.id} extract" }
            requireNotNull(binDir.listFiles()) { "could not list bin/ in ${platform.id} extract" }
                .filter { it.isFile }
                .forEach { file ->
                    if (!file.canExecute()) failures += "bin/${file.name}"
                }

            val jspawnhelper = root.resolve("lib/jspawnhelper")
            require(jspawnhelper.isFile) { "missing lib/jspawnhelper in ${platform.id} extract" }
            if (!jspawnhelper.canExecute()) failures += "lib/jspawnhelper"

            val sharedLibraryExtension = if (platform.id.startsWith("mac-")) ".dylib" else ".so"
            val libDir = root.resolve("lib")
            require(libDir.isDirectory) { "missing lib/ in ${platform.id} extract" }
            val sharedLibraries = libDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(sharedLibraryExtension) }
                .toList()
            require(sharedLibraries.isNotEmpty()) {
                "missing *$sharedLibraryExtension shared libraries in ${platform.id} extract"
            }

            val expectedLibjvm = if (platform.id.startsWith("mac-")) "libjvm.dylib" else "libjvm.so"
            val libjvm = root.resolve("lib/server/$expectedLibjvm")
            require(libjvm.isFile) { "missing lib/server/$expectedLibjvm in ${platform.id} extract" }

            sharedLibraries.forEach { file ->
                if (!file.canExecute()) {
                    failures += file.toRelativeString(root).replace(File.separatorChar, '/')
                }
            }

            require(failures.isEmpty()) {
                "POSIX +x bit was NOT preserved on ${failures.size} file(s) in " +
                    "${platform.id}:\n  - " + failures.joinToString("\n  - ") + "\n" +
                    "Gradle's tarTree silently lost the executable bit; the extract task " +
                    "needs an explicit filesMatching { permissions { unix(...) } } rule " +
                    "for the listed paths."
            }
            logger.lifecycle("[jdk-downloader] permission audit passed: ${platform.id}")
        }
    }
    auditJdkPermissions.configure { dependsOn(audit) }
}

extractAllJdks.configure { dependsOn(verifyAllJdks, auditJdkPermissions) }
