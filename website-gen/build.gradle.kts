plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

dependencies {
    // :installer-gen is the lower build-tooling module — it owns JDK detection AND the shared HTTP/cache
    // infra (KtorHttpFetcher). This is the ONLY project() dependency: :installer-gen has no IntelliJ deps,
    // so `generateWebsite` still never builds the plugin side of the project.
    implementation(project(":installer-gen"))
    // kotlinx-serialization for the GitHub API JSON + version.json. ZIP/XML stay JDK built-in.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

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

val websiteGenMain = "com.jonnyzzz.mcpSteroid.websitegen.WebsiteArtifactsKt"

// Generate the website's release-derived static artifacts (version.json + updatePlugins.xml) straight
// into website/static. The task KNOWS ALL PATHS: VERSION + output dir + release-notes are derived from
// the project layout, so callers (the website Makefile / CI) invoke it with no arguments. It depends ONLY
// on this module's classes (no project() deps), so it never builds the rest of the project — fast.
val generateWebsite by tasks.registering(JavaExec::class) {
    group = "website"
    description = "Generate version.json + updatePlugins.xml into website/static (resolves the GitHub release)."
    mainClass.set(websiteGenMain)
    classpath = sourceSets["main"].runtimeClasspath

    val versionFile = rootProject.layout.projectDirectory.file("VERSION")
    val outDir = rootProject.layout.projectDirectory.dir("website/static")
    val notesDir = rootProject.layout.projectDirectory.dir("release/notes")
    inputs.file(versionFile)
    // Always re-run: the published GitHub release (and its notes) can change without VERSION changing, so
    // we deliberately do not cache or skip on up-to-date.
    outputs.upToDateWhen { false }

    doFirst {
        val version = versionFile.asFile.readText().trim()
        require(version.isNotEmpty()) { "VERSION file (${versionFile.asFile}) is empty" }
        outDir.asFile.mkdirs()
        val resolved = mutableListOf("--version", version, "--out-dir", outDir.asFile.absolutePath)
        val notes = notesDir.file("$version.md").asFile
        if (notes.isFile) {
            resolved += listOf("--notes", notes.absolutePath)
        }
        args = resolved
    }
}

// `generateJdkModel` moved to :installer-gen (it owns JDK detection now).
