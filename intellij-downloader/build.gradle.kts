import de.undercouch.gradle.tasks.download.Download
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.gradle.api.attributes.Usage
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream

buildscript {
    dependencies {
        // Used by the inline doLast block below to extract `7zz` from 7-zip.org tar.xz archives
        // at configuration/build time. Gradle's built-in `resources.{gzip,bzip2}` helpers don't
        // include xz, so we do the decoding ourselves.
        classpath("org.apache.commons:commons-compress:1.27.1")
        classpath("org.tukaani:xz:1.10")
    }
}

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    // Runtime xz support for IdeUnpacker fallback paths that might handle .tar.xz directly.
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
}

tasks.test {
    useJUnit()
}

// ────────────────────────────────────────────────────────────────────────────
// Bundled 7-Zip 23.01 binaries — LGPL v2.1+, https://www.7-zip.org/license.txt
//
// We ship `7zz` (full console, NSIS-capable) on Linux x64 / Linux arm64 / macOS
// universal so that Windows-installer .exe (NSIS) extraction works out of the box
// on any of those hosts. The `.tar.xz` archive is downloaded from the official
// 7-zip.org URLs at build time; `7zz` and `License.txt` are extracted from it and
// dropped into the main JAR resources at `7z/<platform>/`.
//
// On Windows hosts no binary is bundled — the upstream `7zr.exe` doesn't support
// NSIS, so SevenZipLocator falls back to `7z` / `7za` on `PATH` instead.
//
// License attribution: see `THIRD_PARTY_NOTICES.md`.
// ────────────────────────────────────────────────────────────────────────────

val sevenZipVersion = "2301"
val sevenZipBaseUrl = "https://www.7-zip.org/a"

val sevenZipDownloadDir = layout.buildDirectory.dir("7z-download")
val sevenZipResourceDir = layout.buildDirectory.dir("7z-extracted")

data class SevenZipPlatform(val id: String, val archiveSuffix: String)

val sevenZipPlatforms = listOf(
    SevenZipPlatform("linux-x64", "linux-x64.tar.xz"),
    SevenZipPlatform("linux-arm64", "linux-arm64.tar.xz"),
    SevenZipPlatform("mac", "mac.tar.xz"),
)

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

val downloadSevenZipArchives by tasks.registering {
    outputs.dir(sevenZipDownloadDir)
}

sevenZipPlatforms.forEach { platform ->
    val task = tasks.register<Download>("download_7z_${platform.id}") {
        val destFile = sevenZipDownloadDir.get().asFile.resolve("7z${sevenZipVersion}-${platform.archiveSuffix}")
        src("$sevenZipBaseUrl/7z${sevenZipVersion}-${platform.archiveSuffix}")
        dest(destFile)
        configureReliableDownload()
        // 7-zip.org artefacts are immutable for a given version; skip if already on disk.
        onlyIf { !destFile.exists() }
    }
    downloadSevenZipArchives.configure { dependsOn(task) }
}

val extractSevenZipResources by tasks.registering {
    dependsOn(downloadSevenZipArchives)
    inputs.dir(sevenZipDownloadDir)
    outputs.dir(sevenZipResourceDir)

    doLast {
        val root = sevenZipResourceDir.get().asFile
        sevenZipPlatforms.forEach { platform ->
            val tarXz = sevenZipDownloadDir.get().asFile.resolve("7z${sevenZipVersion}-${platform.archiveSuffix}")
            val outDir = root.resolve("7z/${platform.id}").apply {
                deleteRecursively(); mkdirs()
            }
            BufferedInputStream(tarXz.inputStream()).use { fileStream ->
                XZInputStream(fileStream).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            if (entry.isDirectory) continue
                            // Only the two payload files matter for us; the rest is HTML manuals.
                            val keepName = when (entry.name) {
                                "7zz", "./7zz" -> "7zz"
                                "License.txt", "./License.txt" -> "License.txt"
                                else -> null
                            } ?: continue
                            val outFile = outDir.resolve(keepName)
                            outFile.outputStream().use { sink -> tar.copyTo(sink) }
                            if (keepName == "7zz") outFile.setExecutable(true, false)
                        }
                    }
                }
            }
            require(outDir.resolve("7zz").isFile) {
                "Did not find `7zz` inside ${tarXz.name} — 7-Zip archive layout may have changed"
            }
        }

        // Also surface one shared License.txt at `7z/License.txt` so consumers don't have
        // to know which platform's copy to cite.
        val sharedLicense = root.resolve("7z/License.txt")
        val anyPlatformLicense = sevenZipPlatforms
            .map { root.resolve("7z/${it.id}/License.txt") }
            .firstOrNull { it.isFile }
        if (anyPlatformLicense != null && !sharedLicense.exists()) {
            anyPlatformLicense.copyTo(sharedLicense, overwrite = true)
        }
    }
}

// Outgoing: the unpacked 7-Zip binaries tree (`7z/<platform>/{7zz,License.txt}`)
// for consumers that want to bundle the binaries in their own distribution
// instead of pulling them off the classpath. See SevenZipLocator's doc for
// the classpath-resource consumer path that already exists.
val sevenZipBinariesElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "seven-zip-binaries"))
    }
}

artifacts {
    add(sevenZipBinariesElements.name, sevenZipResourceDir.map { it.asFile }) {
        builtBy(extractSevenZipResources)
    }
}

sourceSets.named("main") {
    resources.srcDir(extractSevenZipResources)
}

tasks.named("processResources") {
    dependsOn(extractSevenZipResources)
}
