@file:Suppress(
    "GrazieInspection",
    "GrazieInspectionRunner",
    "HasPlatformType",
    "SpellCheckingInspection",
    "UnusedReceiverParameter",
)

import de.undercouch.gradle.tasks.download.Download
import org.gradle.api.attributes.Usage
import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(25)
}

// `src/buildsrc-shared/kotlin/` carries the IDE compatibility matrix
// (`McpSteroidIdeTargets`), the resolver, the downloader, the unpacker,
// and the LocalIdeProvisioner that ij-plugin/build.gradle.kts calls at
// script-evaluation time. The same path is added as a source dir in
// `buildSrc/build.gradle.kts`, so Gradle scripts and this module's CLI
// compile the same .kt files independently — one source of truth, two
// compiled copies (different classloaders, different Kotlin versions).
// Transitive deps used by the shared code (kotlinx-serialization, slf4j,
// commons-compress, xz) must stay in lockstep with `buildSrc/build.gradle.kts`.
sourceSets.main {
    kotlin.srcDir("src/buildsrc-shared/kotlin")
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
}

dependencies {
    // kotlinx pins read from root gradle.properties so KotlinxRuntimeProbe is
    // compiled against the SAME versions production modules link against;
    // otherwise a paired bump there could leave the probe shipping stale
    // bytecode and miss the very drift it's meant to catch.
    val kotlinxSerialization = providers.gradleProperty("mcp.kotlinx.serialization.version").get()
    val kotlinxCoroutines = providers.gradleProperty("mcp.kotlinx.coroutines.version").get()
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerialization")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutines")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    // Runtime xz support for IdeUnpacker fallback paths that might handle .tar.xz directly.
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.32")
}

tasks.test {
    useJUnit {
        excludeCategories("com.jonnyzzz.mcpSteroid.ideDownloader.LiveNetwork")
    }
    // OS-specific skip lives inside each test (`Assume.assumeTrue` / `assumeFalse`).
    // SevenZipLocatorTest needs the Windows-bundled resources → Windows-only.
    // IdeUnpackerSecurityTest hits tar path-separator behavior that differs on
    // Windows (#78) → non-Windows-only.
}

tasks.register<Test>("liveNetworkTest") {
    description = "Runs intellij-downloader tests that intentionally hit public vendor feeds."
    group = "verification"
    useJUnit {
        includeCategories("com.jonnyzzz.mcpSteroid.ideDownloader.LiveNetwork")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}

// ────────────────────────────────────────────────────────────────────────────
// Bundled 7-Zip Windows payload — LGPL v2.1+, https://www.7-zip.org/license.txt
//
// We ship the official Windows x64 `7z.exe` + `7z.dll` + `License.txt` tuple.
// The pinned 7-Zip release keeps NSIS and LZMA2 support inside `7z.dll`; there
// are no `Formats/Nsis.dll` or `Codecs/Lzma2.dll` plugin files in this payload.
//
// Build-time chain — **Windows host ONLY** (Linux/macOS fail loudly):
//   1a. download `7zr.exe`              (reduced single-file extractor)
//   1b. download `7z<ver>-extra.7z`     (ships standalone NSIS-capable `7za.exe`)
//   1c. use `7zr.exe` to unpack 1b      → produce `7za.exe`
//   2.  download `7z<ver>-x64.exe`      (NSIS installer with full 7z.exe + 7z.dll)
//   3.  use `7za.exe` to unpack 2       → produce bundled `7z.exe` + `7z.dll` + `License.txt`
//
// Why Windows-only: NSIS unpacking is only needed at runtime on Windows targets
// (Linux/macOS IDE distributions are .tar.gz / .dmg — handled in pure Java by
// IdeUnpacker). Producing the Windows bundle requires the host to RUN one of
// the 7-Zip binaries, and the only NSIS-capable extractor that's downloadable
// as a single file on every relevant host is `7za.exe` from `-extra.7z` — and
// `7za.exe` is a Windows binary. We therefore run the bundle-production chain
// only on Windows hosts. The release plugin .zip must be built on Windows.
//
// License attribution: see `THIRD_PARTY_NOTICES.md`.
// ────────────────────────────────────────────────────────────────────────────

val sevenZipVersion = "2301"
val sevenZipBaseUrl = "https://www.7-zip.org/a"
val isWindowsHost = OperatingSystem.current().isWindows

val sevenZipBootstrapDir = layout.buildDirectory.dir("7z-bootstrap")
val sevenZipBootstrapExtractedDir = sevenZipBootstrapDir.map { it.dir("extracted") }
val sevenZipDownloadDir = layout.buildDirectory.dir("7z-download")
val sevenZipWindowsStagingDir = layout.buildDirectory.dir("7z-windows-staging")
val sevenZipResourceDir = layout.buildDirectory.dir("7z-extracted")

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

// All download tasks register unconditionally — `de.undercouch.download` validates
// `src(...)` during graph validation, so we cannot defer registration to a Provider.
// On non-Windows hosts, the downstream extraction tasks fail loudly before any
// download is required.
val downloadSevenZr = tasks.register<Download>("downloadSevenZr") {
    description = "Stage 1a — download 7zr.exe (single-file .7z extractor, no NSIS)."
    group = "build setup"
    val destFile = sevenZipBootstrapDir.get().asFile.resolve("7zr.exe")
    src("$sevenZipBaseUrl/7zr.exe")
    dest(destFile)
    configureReliableDownload()
    onlyIf { !destFile.exists() }
}

val downloadSevenZipExtra = tasks.register<Download>("downloadSevenZipExtra") {
    description = "Stage 1b — download 7z${sevenZipVersion}-extra.7z (ships NSIS-capable 7za.exe)."
    group = "build setup"
    val destFile = sevenZipBootstrapDir.get().asFile.resolve("7z${sevenZipVersion}-extra.7z")
    src("$sevenZipBaseUrl/7z${sevenZipVersion}-extra.7z")
    dest(destFile)
    configureReliableDownload()
    onlyIf { !destFile.exists() }
}

val extractSevenZa by tasks.registering {
    description = "Stage 1c — use 7zr.exe to extract 7za.exe from the extra archive."
    group = "build setup"
    dependsOn(downloadSevenZr, downloadSevenZipExtra)
    val sevenZr = sevenZipBootstrapDir.map { it.asFile.resolve("7zr.exe") }
    val extraArchive = sevenZipBootstrapDir.map { it.asFile.resolve("7z${sevenZipVersion}-extra.7z") }
    inputs.file(sevenZr)
    inputs.file(extraArchive)
    outputs.dir(sevenZipBootstrapExtractedDir)

    doLast {
        check(isWindowsHost) {
            "7-Zip bundle production is a Windows-only build step (host: ${OperatingSystem.current().name}). " +
                "Run this on a Windows host."
        }
        val outDir = sevenZipBootstrapExtractedDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        providers.exec {
            commandLine(
                sevenZr.get().absolutePath, "x", extraArchive.get().absolutePath,
                "-y", "-o${outDir.absolutePath}",
            )
        }.result.get().assertNormalExitValue()
        val sevenZa = outDir.resolve("7za.exe")
        require(sevenZa.isFile) {
            "Did not find `7za.exe` inside ${extraArchive.get().name} — extra archive layout may have changed"
        }
    }
}

val downloadSevenZipWindowsInstaller = tasks.register<Download>("downloadSevenZipWindowsInstaller") {
    description = "Stage 2 — download the pinned 7-Zip Windows installer (NSIS)."
    group = "build setup"
    val destFile = sevenZipDownloadDir.get().asFile.resolve("7z${sevenZipVersion}-x64.exe")
    src("$sevenZipBaseUrl/7z${sevenZipVersion}-x64.exe")
    dest(destFile)
    configureReliableDownload()
    onlyIf { !destFile.exists() }
}

val extractSevenZipResources by tasks.registering {
    description = "Stage 3 — extract the bundled 7-Zip Windows resources for consumers."
    group = "build setup"
    dependsOn(extractSevenZa, downloadSevenZipWindowsInstaller)
    val sevenZa = sevenZipBootstrapExtractedDir.map { it.asFile.resolve("7za.exe") }
    val windowsInstaller = sevenZipDownloadDir.map { it.asFile.resolve("7z${sevenZipVersion}-x64.exe") }
    inputs.file(sevenZa)
    inputs.file(windowsInstaller)
    inputs.property("sevenZipPayloadLayout", "windows-x64-exe-dll-license-v1")
    outputs.dir(sevenZipResourceDir)

    doLast {
        check(isWindowsHost) {
            "7-Zip bundle production is a Windows-only build step (host: ${OperatingSystem.current().name}). " +
                "Run this on a Windows host."
        }

        val extractor = sevenZa.get()
        require(extractor.isFile) { "7za.exe bootstrap executable is missing: $extractor" }
        val installer = windowsInstaller.get()
        require(installer.isFile) { "7-Zip Windows installer is missing: $installer" }

        val stagingDir = sevenZipWindowsStagingDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        providers.exec {
            commandLine(extractor.absolutePath, "x", installer.absolutePath, "-y", "-o${stagingDir.absolutePath}")
        }.result.get().assertNormalExitValue()

        val root = sevenZipResourceDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val winDir = root.resolve("7z/win-x64").apply { mkdirs() }
        val payload = mapOf(
            "7z.exe" to "7z.exe",
            "7z.dll" to "7z.dll",
            "License.txt" to "License.txt",
        )
        payload.forEach { (sourceName, targetName) ->
            val source = stagingDir.resolve(sourceName)
            require(source.isFile) {
                "Did not find `$sourceName` inside ${installer.name} — 7-Zip Windows installer layout may have changed"
            }
            source.copyTo(winDir.resolve(targetName), overwrite = true)
        }

        winDir.resolve("License.txt").copyTo(root.resolve("7z/License.txt"), overwrite = true)
    }
}

// Outgoing: the unpacked 7-Zip Windows payload tree
// (`7z/win-x64/{7z.exe,7z.dll,License.txt}` plus shared `7z/License.txt`) for
// consumers that want to bundle the binaries in their own distribution instead
// of pulling them off the classpath. See SevenZipLocator's doc for the
// classpath-resource consumer path that already exists.
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

// The bundled 7z payload is NOT exposed via the classpath. :npx-kt's distZip
// consumes the `sevenZipBinariesElements` configuration above (via the
// "seven-zip-binaries" Usage attribute) and places `7z.exe` + `7z.dll` +
// `License.txt` under <devrig-root>/7z/. Runtime code resolves the absolute
// path via `com.jonnyzzz.mcpSteroid.devrig.DevrigRoot.sevenZipBinary()` and
// passes it explicitly to `unpackIdeArchive(..., sevenZipBinary = ...)`.
