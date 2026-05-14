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

// Resolvable: pulls :ij-plugin's `buildPlugin` archive through the "plugin-zip"
// Usage attribute (same hook :test-integration uses). The zip lands here so the
// distribution can unpack it into ij-plugin/ — see `distributions { main { … } }`
// further down.
val ijPluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

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

    // Managed backends reuse the existing IntelliJ downloader/unpacker instead
    // of carrying a second product-feed / archive-extraction implementation.
    implementation(project(":intellij-downloader"))

    // Bundles :ij-plugin's buildPlugin archive into the distZip under ij-plugin/.
    // Resolved through the `ijPluginZip` configuration above.
    ijPluginZip(project(":ij-plugin"))

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
    // Compile-time access to logback's ListAppender / Logger so tests can pin
    // log-level routing (e.g. legacy markers must NOT log at WARN). Logback is
    // already on the runtime classpath via the `runtimeOnly` line above; this
    // additional declaration just makes the API visible to test source.
    testImplementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    applicationName = "mcp-steroid-proxy"
    mainClass.set("com.jonnyzzz.mcpSteroid.proxy.MainKt")
}

// Unpack :ij-plugin's buildPlugin archive into the proxy distribution at
// `ij-plugin/`. The plugin zip is shaped `mcp-steroid/{EULA,lib,…}` (the
// IntelliJ Platform plugin's default); the outer `mcp-steroid/` directory is
// stripped on copy so the proxy sees `ij-plugin/EULA`, `ij-plugin/lib/…`,
// `ij-plugin/kotlinc/…`, etc. — one level shallower, no wrapper directory.
//
// The plugin archive's contents (and their executable bits, e.g. kotlinc shell
// scripts and ocr-tesseract binaries) are enforced by :ij-plugin's own
// `verifyBundledLibraries` task. The npx-kt verifier (below) only sentinel-
// checks the bundling step itself.
//
// Provider<File> for the resolved plugin zip — derived from `Configuration.elements`
// so Gradle carries the task dependency on `:ij-plugin:buildPlugin` through into
// downstream tasks automatically. Going through `ijPluginZip.singleFile` is eager
// and strips the Buildable, leaving the consumer without a path to producing the zip.
val ijPluginZipFile = ijPluginZip.elements.map { it.single().asFile }

// Pre-extract the plugin zip into a staging dir with the outer `mcp-steroid/`
// wrapper stripped. Doing this in a dedicated Sync task instead of an inline
// `eachFile { relativePath = … }` under `distributions.main.contents.into(…)`
// works around a Kotlin DSL quirk where the rewrite is silently dropped in that
// position; pre-extracting is also cheaper for incremental builds.
val extractIjPluginZip by tasks.registering(Sync::class) {
    group = "build"
    description = "Extract :ij-plugin's buildPlugin zip into a staging dir, strip the mcp-steroid/ wrapper"
    from(zipTree(ijPluginZipFile)) {
        eachFile {
            val segments = relativePath.segments
            if (segments.isNotEmpty() && segments[0] == "mcp-steroid") {
                relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
            }
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("ij-plugin-extracted"))
}

distributions {
    main {
        contents {
            // Repo-root EULA — same file `:ij-plugin` already ships inside its
            // plugin zip. Bundled at the npx-kt dist root so the binary carries
            // its own license text alongside the launcher and bundled plugin.
            from(rootProject.layout.projectDirectory.file("EULA"))

            // Unpacked :ij-plugin contents — sits under `ij-plugin/` with no
            // outer wrapper directory. The Sync extract preserved every entry's
            // POSIX mode on disk; distZip then rewrites file modes to 0o644 by
            // default and drops the executable bit. Re-apply +x for any source
            // file the OS reports as executable, so every executable inside
            // the plugin (kotlinc launchers, ocr-tesseract binary, plus any
            // new ones :ij-plugin starts shipping) survives without a narrow
            // per-pattern allowlist.
            into("ij-plugin") {
                from(extractIjPluginZip) {
                    eachFile {
                        if (file.canExecute()) {
                            permissions { unix("rwxr-xr-x") }
                        }
                    }
                }
            }
        }
    }
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

        // ij-plugin/ subtree: full contents are enforced by :ij-plugin's own
        // `verifyBundledLibraries` task before the archive ever reaches us. Here we
        // just sentinel-check that the unpack + relocate step did what it claimed:
        // (1) the subtree exists and is populated, (2) the outer `mcp-steroid/`
        // wrapper was stripped, (3) the key jars from the plugin zip are present.
        val ijPluginPrefix = "ij-plugin/"
        val ijPluginFiles = allFiles.filter { it.startsWith(ijPluginPrefix) }.toSortedSet()
        check(ijPluginFiles.isNotEmpty()) {
            "Expected ij-plugin/ subtree to be populated in $distName"
        }
        check(ijPluginFiles.none { it.startsWith("ij-plugin/mcp-steroid/") }) {
            "Outer mcp-steroid/ wrapper was not stripped from ij-plugin/ subtree"
        }
        listOf(
            "ij-plugin/EULA",
            "ij-plugin/lib/ij-plugin-$proxyVersion.jar",
            "ij-plugin/lib/execution-storage-$proxyVersion.jar",
            "ij-plugin/lib/mcp-steroid-server-$proxyVersion.jar",
            "ij-plugin/kotlinc/build.txt",
        ).forEach { sentinel ->
            check(ijPluginFiles.any { it.removeSuffix(":X") == sentinel }) {
                "ij-plugin/ subtree is missing sentinel '$sentinel'. Present prefix sample: " +
                        ijPluginFiles.take(10).joinToString("\n  ", prefix = "\n  ")
            }
        }
        // POSIX launchers inside the plugin must keep their executable bit through
        // the unpack + repack. The `.bat` siblings stay non-executable — Windows
        // ignores the bit and the `eachFile` rule only promotes files whose source
        // mode already had +x. The exact 6-file list mirrors what the plugin zip
        // ships today; a future plugin launcher landing here without +x in the
        // dist will fail this check loudly, prompting an investigation rather
        // than silently shipping a broken script.
        listOf(
            "ij-plugin/kotlinc/bin/kapt:X",
            "ij-plugin/kotlinc/bin/kotlin:X",
            "ij-plugin/kotlinc/bin/kotlinc:X",
            "ij-plugin/kotlinc/bin/kotlinc-js:X",
            "ij-plugin/kotlinc/bin/kotlinc-jvm:X",
            "ij-plugin/ocr-tesseract/bin/ocr-tesseract:X",
        ).forEach { sentinel ->
            check(sentinel in ijPluginFiles) {
                "ij-plugin/ subtree is missing executable sentinel '$sentinel'. " +
                        "Check that distZip's eachFile { permissions { … } } block " +
                        "preserves the +x bit on POSIX launchers."
            }
        }
        // Conversely, the `.bat` siblings MUST NOT carry +x — Windows ignores it
        // but a stray bit would mean our eachFile rule mis-promoted a non-source-
        // executable file, signaling a regression in the rule itself.
        listOf(
            "ij-plugin/kotlinc/bin/kotlinc.bat",
            "ij-plugin/ocr-tesseract/bin/ocr-tesseract.bat",
        ).forEach { batPath ->
            check(batPath in ijPluginFiles) {
                "ij-plugin/ subtree is missing '$batPath' (expected as non-executable .bat sibling)."
            }
            check("$batPath:X" !in ijPluginFiles) {
                "ij-plugin/ subtree wrongly marked '$batPath' executable; eachFile { } rule " +
                        "should only promote files whose source mode had +x."
            }
        }
        allFiles = (allFiles - ijPluginFiles).toSortedSet()

        val expectedFiles = sortedSetOf(
            // EULA — repo-root EULA at the distribution root, mirroring the
            // copy `:ij-plugin` ships inside its plugin zip.
            "EULA",

            // Launchers — the `application` plugin marks BOTH executable in the zip
            // (Windows ignores the bit; Unix needs it for the shell launcher).
            "bin/mcp-steroid-proxy:X",
            "bin/mcp-steroid-proxy.bat:X",

            // Internal jars (this project + sibling subprojects).
            "lib/npx-kt-$proxyVersion.jar",
            "lib/execution-storage-$proxyVersion.jar",
            "lib/intellij-downloader-$proxyVersion.jar",
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
            "lib/commons-codec-1.17.1.jar",
            "lib/commons-compress-1.27.1.jar",
            "lib/commons-io-2.16.1.jar",
            "lib/commons-lang3-3.16.0.jar",
            "lib/xz-1.10.jar",
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
