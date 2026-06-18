plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

dependencies {
    // JDK detection (the data model) + installer-script generation. NO project() dependencies on
    // purpose: the generator tasks compile only this module, never the rest of the build.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-client-core:3.3.2")
    implementation("io.ktor:ktor-client-cio:3.3.2")
    // BouncyCastle PGP: verify the detached OpenPGP signatures (Corretto .sig, Azul signature-binary).
    implementation("org.bouncycastle:bcpg-jdk18on:1.79")
    // Apache Commons Compress: stream tar.gz / zip entries to locate each JDK's inner JAVA_HOME (bin/java).
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

// The JDK download cache MUST live outside any `build/` folder so the multi-hundred-MB JDK archives
// survive `clean` and are shared across runs/branches. The Gradle user home is the natural home; the
// path is computed here and handed to the generator via --cache-dir (the generator never guesses it).
val jdkDownloadCacheDir: java.io.File = gradle.gradleUserHomeDir.resolve("caches/mcp-steroid/installer-gen-jdk")

// Resolve all JDK builds (Amazon Corretto 25 + Azul Zulu 25) into the version-pinned data model JSON.
// Vendor-natural validation (detached OpenPGP signatures) happens inside the generator; downloads are
// cached in jdkDownloadCacheDir. No project() deps — compiles only this module.
val generateJdkModel by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Resolve all JDK builds (Corretto + Azul) into the JDK data model JSON (PGP-verified, cached)."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.JdkModelMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // JDK archives are read fully into memory (one ~230 MB array at a time) to hash + scan them.
    maxHeapSize = "2g"

    val outFile = layout.buildDirectory.file("jdk-model/jdk-model.json")
    outputs.file(outFile)
    // The live vendor sources can publish a new build at any time, so never treat this as up-to-date.
    outputs.upToDateWhen { false }

    doFirst {
        jdkDownloadCacheDir.mkdirs()
        args = listOf(
            "--cache-dir", jdkDownloadCacheDir.absolutePath,
            "--out", outFile.get().asFile.absolutePath,
        )
    }
}

// Generate install.sh + install.ps1 into website/static. Resolves the 5 JDKs (cached, PGP-verified) and
// the devrig coordinates (latest GitHub release by default), then bakes the per-platform table into the
// scripts. Knows all paths from the project layout — callers invoke it with no args. No project() deps.
val generateInstaller by tasks.registering(JavaExec::class) {
    group = "installer"
    description = "Generate install.sh + install.ps1 into website/static (JDKs PGP-verified + cached; devrig from the latest release)."
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.InstallerGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath
    maxHeapSize = "2g" // resolveAllJdks holds one ~230 MB archive at a time

    val versionFile = rootProject.layout.projectDirectory.file("VERSION")
    val outDir = rootProject.layout.projectDirectory.dir("website/static")
    inputs.file(versionFile)
    // Always re-run: the published devrig release + the live JDK builds can change without VERSION changing.
    outputs.upToDateWhen { false }

    doFirst {
        val version = versionFile.asFile.readText().trim()
        require(version.isNotEmpty()) { "VERSION file (${versionFile.asFile}) is empty" }
        outDir.asFile.mkdirs()
        jdkDownloadCacheDir.mkdirs()
        args = listOf(
            "--out-dir", outDir.asFile.absolutePath,
            "--version", version,
            "--cache-dir", jdkDownloadCacheDir.absolutePath,
        )
    }
}
