import de.undercouch.gradle.tasks.download.Download
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.gradle.api.file.RelativePath
import java.io.BufferedInputStream

buildscript {
    dependencies {
        // OpenPGP signature verification — used by the doLast in the
        // verifyJdk_* tasks below. The non-fips package is fine for
        // verifying signatures (no key generation, no cipher modes).
        classpath("org.bouncycastle:bcpg-jdk18on:1.78.1")
        classpath("org.bouncycastle:bcprov-jdk18on:1.78.1")
    }
}

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

fun pgpKeyIdHex(keyId: Long): String = keyId.toULong().toString(16).uppercase().padStart(16, '0')

fun verifyDetachedPgpSignature(archive: File, signature: File, publicKeyBundle: File) {
    val rings = PGPUtil.getDecoderStream(publicKeyBundle.inputStream()).use { stream ->
        PGPPublicKeyRingCollection(stream, BcKeyFingerprintCalculator())
    }

    val sig: PGPSignature = PGPUtil.getDecoderStream(signature.inputStream()).use { stream ->
        val factory = PGPObjectFactory(stream, BcKeyFingerprintCalculator())
        val first = factory.nextObject()
        val sigList = when (first) {
            is PGPSignatureList -> first
            is PGPCompressedData -> PGPObjectFactory(first.dataStream, BcKeyFingerprintCalculator()).nextObject() as PGPSignatureList
            else -> error("Unexpected packet in signature file ${signature.name}: ${first?.javaClass?.simpleName ?: "null"}")
        }
        require(sigList.size() > 0) { "Signature file ${signature.name} contains no signature packets" }
        sigList[0]
    }

    val keyId = pgpKeyIdHex(sig.keyID)
    val key = rings.getPublicKey(sig.keyID)
        ?: error(
            "Signature on ${archive.name} was made with key id 0x$keyId, " +
                "which is NOT present in the bundled Corretto public key ring (${publicKeyBundle.name}). " +
                "Refusing to extract — possible tampered archive or untrusted signer."
        )

    sig.init(BcPGPContentVerifierBuilderProvider(), key)
    BufferedInputStream(archive.inputStream()).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sig.update(buffer, 0, read)
        }
    }
    require(sig.verify()) {
        "PGP signature verification FAILED for ${archive.name} " +
            "(signed by key id 0x$keyId, key in ${publicKeyBundle.name}). " +
            "Archive may be corrupted or tampered."
    }
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

    val verifyTask = tasks.register("verifyJdk_${platform.id}") {
        group = "verification"
        description = "Verify the PGP signature for Amazon Corretto JDK $correttoVersion on ${platform.id}."
        dependsOn(downloadTask, downloadSigTask, downloadCorrettoPublicKey)
        inputs.file(downloadTask.map { it.outputs.files.singleFile })
        inputs.file(downloadSigTask.map { it.outputs.files.singleFile })
        inputs.file(downloadCorrettoPublicKey.map { it.outputs.files.singleFile })
        doLast {
            verifyDetachedPgpSignature(
                archive = downloadTask.get().outputs.files.singleFile,
                signature = downloadSigTask.get().outputs.files.singleFile,
                publicKeyBundle = downloadCorrettoPublicKey.get().outputs.files.singleFile,
            )
            logger.lifecycle("[jdk-downloader] verified ${platform.id} signature")
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

extractAllJdks.configure { dependsOn(verifyAllJdks) }
