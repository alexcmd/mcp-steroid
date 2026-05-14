import org.gradle.api.attributes.Usage
import java.util.SortedSet

plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.jonnyzzz.mcpSteroid"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.posthog:posthog-server:2.3.0")

    // MCP transport: framed/NDJSON parser + McpStdioServer (replaces the old
    // StdioServer in this module — kept compiled but no longer wired into main()).
    implementation(project(":mcp-stdio"))

    // McpSteroidTools — registers the steroid_* tool surface on an McpServerCore.
    // Brings :mcp-core and :prompts transitively.
    implementation(project(":mcp-steroid-server"))

    // IDE-free ExecutionStorage core. Lets the proxy persist execution
    // history with the same on-disk layout the IntelliJ plugin uses, so
    // downstream tooling that reads .idea/mcp-steroid/{eid}/ artefacts
    // works against both backends without conditional logic.
    implementation(project(":execution-storage"))

    // SLF4J binding for the launcher. We use Logback (not slf4j-simple) so
    // operators can drop in a `logback.xml` to add appenders, change levels,
    // or route specific loggers — slf4j-simple has no real configuration
    // surface. The bundled `logback.xml` (in src/main/resources) routes
    // everything to stderr only — never stdout, which is reserved for MCP
    // NDJSON frames.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Spin up an in-process Ktor server in monitor round-trip tests so the
    // monitor's HTTP client talks to a real socket. testImplementation only —
    // the production `mcp-steroid-proxy` binary stays a pure ktor-client.
    testImplementation("io.ktor:ktor-server-core:$ktorVersion")
    testImplementation("io.ktor:ktor-server-cio:$ktorVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "mcp-steroid-proxy"
    mainClass.set("com.jonnyzzz.mcpSteroid.proxy.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Hand the build's project.version through to ProxyVersionResourceTest so it can
    // end-to-end-assert the value `loadProxyVersion()` returns at runtime.
    systemProperty("npx-kt.expected.version", version.toString())
}

kotlin {
    jvmToolchain(21)
}

// `project.version` rendered as a String once so both the version-resource generator
// (just below) and the bundled-libraries verifier (further down) can use it without
// re-evaluating Gradle's Provider chain.
val proxyVersion: String = version.toString()

// Bake `project.version` into a classpath resource (`proxy-version.txt`) consumed by
// `loadProxyVersion()` in src/main/.../Config.kt. Mirrors how :ij-plugin wires
// `project.version` through `intellijPlatform.version` → `patchPluginXml` → the
// `<version>` element in plugin.xml — same idea, different consumer surface.
//
// The generated file is the single source of truth for runtime version reporting:
// it shows up in MCP server-info handshakes, posthog events, and CLI banners. Without
// the generator the fallback in `loadProxyVersion()` ("0.1.0") would mask the actual
// build version everywhere it's used.
//
// `proxyVersion` is declared further down for the verifier and shared here.
val generateProxyVersionResource by tasks.registering {
    group = "build"
    description = "Write project.version into proxy-version.txt for loadProxyVersion()"

    val outputDir = layout.buildDirectory.dir("generated/resources/proxy-version")
    inputs.property("proxyVersion", proxyVersion)
    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().asFile.resolve("proxy-version.txt")
        file.parentFile.mkdirs()
        file.writeText(proxyVersion)
    }
}

sourceSets.named("main") {
    resources.srcDir(generateProxyVersionResource)
}

tasks.named("processResources") {
    dependsOn(generateProxyVersionResource)
}

// Dedicated source set for stdio MCP server integration tests
// (CliMcpStdioIntegrationTest). The tests spawn `bin/mcp-steroid-proxy` from the
// `installDist` output as a subprocess and exchange JSON-RPC frames over stdio.
// They are NOT part of the default `:npx-kt:test` run — invoke explicitly via
// `./gradlew :npx-kt:integrationTest`.
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output +
            sourceSets["test"].compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets["test"].runtimeClasspath
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "integrationTestImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationTestImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(kotlin("test-junit5"))

    // Docker harness: ContainerDriver + buildDockerImage + AISessionBase, plus
    // DockerClaudeSession / DockerCodexSession / DockerGeminiSession used by
    // the Cli{Claude,Codex,Gemini}IntegrationTest classes.
    "integrationTestImplementation"(project(":test-helper"))
    // StdioMcpCommand + the agent MCP-add arg builders.
    "integrationTestImplementation"(project(":ai-agents"))
}

val installDistTask = tasks.named<Sync>("installDist")

tasks.register<Test>("integrationTest") {
    description = "Stdio MCP server integration tests (subprocess-driven). Requires installDist."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    dependsOn(installDistTask)
    // Path to the launcher script produced by installDist. The application plugin
    // names the install dir after `application.applicationName`, not the project,
    // so resolve it via the task itself rather than hard-coding the directory.
    val launcherProvider = installDistTask.map { sync ->
        sync.destinationDir.resolve("bin/${application.applicationName}")
    }
    inputs.file(launcherProvider)
    doFirst {
        systemProperty("npx.kt.launcher", launcherProvider.get().absolutePath)
    }
}

val npxKtPackageElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "npx-kt-package"))
    }
}

artifacts {
    add(npxKtPackageElements.name, tasks.distZip)
}

tasks.named("assemble") {
    dependsOn(tasks.distZip)
}

// Modelled on :ij-plugin's `verifyBundledLibraries`. Locks down what `distZip` ships so
// transitive-dependency churn (a coroutine update, a Ktor bump, a new internal module)
// fails the build instead of silently changing what end users `npx`-install. Update
// `expectedFiles` below when the change is intentional.
val verifyBundledLibraries by tasks.registering {
    group = "verification"
    description = "List and verify libraries bundled in the npx-kt distZip"
    dependsOn(tasks.distZip)
    doLast {
        val zip = tasks.distZip.get().outputs.files.singleFile

        var allFiles: SortedSet<String> = run {
            val collected = mutableListOf<String>()
            zipTree(zip).visit {
                if (!isDirectory) {
                    val path = relativePath.pathString
                    collected += if (permissions.user.execute) "$path:X" else path
                }
            }
            collected
        }.toSortedSet()

        // distZip puts everything under a top-level `<archiveBaseName>-<version>/` dir
        // (the `application` plugin convention). Strip that prefix so the expected list
        // doesn't have to repeat it on every line.
        val distName = tasks.distZip.get().archiveFile.get().asFile.nameWithoutExtension
        val pluginPrefix = "$distName/"
        check(allFiles.all { it.startsWith(pluginPrefix) }) {
            "Entries must live under '$pluginPrefix': " + allFiles.map { it.substringBefore('/') }.toSortedSet()
        }
        allFiles = allFiles.map { it.removePrefix(pluginPrefix) }.toSortedSet()
        check(allFiles.isNotEmpty()) { "distZip has no entries" }

        val expectedFiles = sortedSetOf(
            // Launchers — the `application` plugin marks BOTH executable in the zip
            // (Windows ignores the bit; Unix needs it for the shell launcher).
            "bin/mcp-steroid-proxy:X",
            "bin/mcp-steroid-proxy.bat:X",

            // Internal jars (this project + sibling subprojects).
            "lib/npx-kt-$proxyVersion.jar",
            "lib/execution-storage-$proxyVersion.jar",
            "lib/mcp-core-$proxyVersion.jar",
            "lib/mcp-steroid-server-$proxyVersion.jar",
            "lib/mcp-stdio-$proxyVersion.jar",
            "lib/prompts-$proxyVersion.jar",
            "lib/prompts-api-$proxyVersion.jar",

            // Kotlin runtime.
            "lib/kotlin-stdlib-2.2.20.jar",
            "lib/kotlin-stdlib-jdk7-2.2.20.jar",
            "lib/kotlin-stdlib-jdk8-2.2.20.jar",
            "lib/kotlinx-coroutines-core-jvm-1.10.1.jar",
            "lib/kotlinx-coroutines-slf4j-1.10.1.jar",
            "lib/kotlinx-io-bytestring-jvm-0.6.0.jar",
            "lib/kotlinx-io-core-jvm-0.6.0.jar",
            "lib/kotlinx-serialization-core-jvm-1.9.0.jar",
            "lib/kotlinx-serialization-json-jvm-1.9.0.jar",

            // Ktor client transitives (CIO engine).
            "lib/ktor-client-cio-jvm-3.1.0.jar",
            "lib/ktor-client-core-jvm-3.1.0.jar",
            "lib/ktor-events-jvm-3.1.0.jar",
            "lib/ktor-http-cio-jvm-3.1.0.jar",
            "lib/ktor-http-jvm-3.1.0.jar",
            "lib/ktor-io-jvm-3.1.0.jar",
            "lib/ktor-network-jvm-3.1.0.jar",
            "lib/ktor-network-tls-jvm-3.1.0.jar",
            "lib/ktor-serialization-jvm-3.1.0.jar",
            "lib/ktor-sse-jvm-3.1.0.jar",
            "lib/ktor-utils-jvm-3.1.0.jar",
            "lib/ktor-websocket-serialization-jvm-3.1.0.jar",
            "lib/ktor-websockets-jvm-3.1.0.jar",

            // Analytics + HTTP transitives.
            "lib/posthog-6.4.0.jar",
            "lib/posthog-server-2.3.0.jar",
            "lib/okhttp-4.11.0.jar",
            "lib/okio-jvm-3.2.0.jar",
            "lib/gson-2.10.1.jar",

            // SLF4J + Logback (production logging binding).
            "lib/logback-classic-1.5.18.jar",
            "lib/logback-core-1.5.18.jar",
            "lib/slf4j-api-2.0.17.jar",

            // Other transitives.
            "lib/annotations-23.0.0.jar",
        ).toSortedSet()

        if (allFiles != expectedFiles) {
            val missing = expectedFiles - allFiles
            val unexpected = allFiles - expectedFiles
            throw GradleException(buildString {
                appendLine("Bundled libraries mismatch in :npx-kt distZip!")
                if (missing.isNotEmpty()) {
                    appendLine("Missing entries:")
                    missing.forEach { appendLine("  - $it") }
                }
                if (unexpected.isNotEmpty()) {
                    appendLine("Unexpected entries:")
                    unexpected.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("Actual entries:")
                allFiles.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Update `expectedFiles` in npx-kt/build.gradle.kts if this change is intentional.")
            })
        }
    }
}

tasks.test {
    dependsOn(verifyBundledLibraries)
}

tasks.distZip {
    finalizedBy(verifyBundledLibraries)
}

tasks.named("check") {
    dependsOn(verifyBundledLibraries)
}
