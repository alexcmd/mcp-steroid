import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import java.security.MessageDigest
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

fun sha512(file: File): String {
    val digest = MessageDigest.getInstance("SHA-512")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (bytesRead > 0) digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun stableJson(value: Any?): String = JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n"

fun requireJsonString(row: Map<*, *>, rowIndex: Int, key: String): String {
    val value = row[key]
    require(value is String) {
        "jdk-manifest.json row $rowIndex field '$key' must be a string, got ${value?.let { it::class.qualifiedName } ?: "null"}"
    }
    return value
}

fun parseJdkManifest(json: String): List<LinkedHashMap<String, Any?>> {
    val parsed = JsonSlurper().parseText(json)
    require(parsed is List<*>) { "jdk-manifest.json must be a JSON array" }
    return parsed.mapIndexed { index, item ->
        val row = item as? Map<*, *> ?: error("jdk-manifest.json row $index must be an object")
        linkedMapOf<String, Any?>(
            "name" to requireJsonString(row, index, "name"),
            "os" to requireJsonString(row, index, "os"),
            "cpu" to requireJsonString(row, index, "cpu"),
            "downloadUrl" to requireJsonString(row, index, "downloadUrl"),
            "sha-512" to requireJsonString(row, index, "sha-512"),
        )
    }
}

// Resolvable: pulls :ij-plugin's `buildPlugin` archive through the "plugin-zip"
// Usage attribute (same hook :test-integration uses). The distribution bundles
// this archive directly as ij-plugin.zip.
val ijPluginZip by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "plugin-zip"))
    }
}

// Resolvable: pulls :intellij-downloader's extracted 7-Zip binaries
// tree through the "seven-zip-binaries" Usage attribute. The directory
// (not a zip) lands here so distZip can copy it verbatim into 7z/.
val sevenZipBinaries by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "seven-zip-binaries"))
    }
}

// Resolvable: pulls :jdk-downloader's generated JDK URL + SHA-512 manifest.
// The future bootstrapper consumes this metadata instead of bundled JDK trees.
val jdkManifest by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "jdk-manifest"))
    }
}

data class JdkPlatform(val id: String, val usageAttr: String) {
    val configName: String = "jdk${id.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}"
}

/**
 * Retained for the upcoming version.json generator: these resolvable
 * configurations keep consuming :jdk-downloader's verified Corretto outputs so
 * that task can compute archive metadata. distZip intentionally no longer reads
 * them; today's npx-kt package expects Java on PATH instead of bundling JDKs.
 */
val jdkPlatforms = listOf(
    JdkPlatform("linux-amd64", "jdk-linux-amd64"),
    JdkPlatform("linux-arm", "jdk-linux-arm"),
    JdkPlatform("mac-arm", "jdk-mac-arm"),
    JdkPlatform("windows-amd64", "jdk-windows-amd64"),
)

val jdkConfigs: Map<String, Configuration> = jdkPlatforms.associate { platform ->
    val cfg = configurations.create(platform.configName) {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, platform.usageAttr))
        }
    }
    dependencies.add(cfg.name, project(":jdk-downloader"))
    platform.id to cfg
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.posthog:posthog-server:2.3.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

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

    // Bundles :ij-plugin's buildPlugin archive into the distZip as ij-plugin.zip.
    // Resolved through the `ijPluginZip` configuration above.
    ijPluginZip(project(":ij-plugin"))

    // Bundles :intellij-downloader's extracted 7-Zip binaries into the distZip under 7z/.
    // Resolved through the `sevenZipBinaries` configuration above.
    sevenZipBinaries(project(":intellij-downloader"))

    // Consumes :jdk-downloader's JDK package rows for build/version.json.
    jdkManifest(project(":jdk-downloader"))

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

// Provider<File> for the resolved plugin zip — derived from `Configuration.elements`
// so Gradle carries the task dependency on `:ij-plugin:buildPlugin` through into
// downstream tasks automatically. Going through `ijPluginZip.singleFile` is eager
// and strips the Buildable, leaving the consumer without a path to producing the zip.
val ijPluginZipFile = ijPluginZip.elements.map { it.single().asFile }
val jdkManifestFile = jdkManifest.elements.map { it.single().asFile }
val sevenZipBinariesDir = sevenZipBinaries.elements.map { it.single().asFile }
val sevenZipLicenseFile = sevenZipBinariesDir.map { it.resolve("7z/License.txt") }
/**
 * Retained with jdkPlatforms/jdkConfigs for future version.json generation even
 * though the distribution no longer copies these extracted JDK trees.
 */
val jdkDirs: Map<String, Provider<File>> = jdkPlatforms.associate { platform ->
    val cfg = jdkConfigs.getValue(platform.id)
    platform.id to cfg.elements.map { it.single().asFile }
}

distributions {
    main {
        contents {
            // Repo-root EULA — same file `:ij-plugin` already ships inside its
            // plugin zip. Bundled at the npx-kt dist root so the binary carries
            // its own license text alongside the launcher and bundled plugin.
            from(rootProject.layout.projectDirectory.file("EULA"))

            // Keep the plugin as the original archive. Managed backend install
            // expands it on demand, which avoids re-packing the plugin payload and
            // preserves the mode bits recorded by :ij-plugin.
            from(ijPluginZipFile) {
                rename { "ij-plugin.zip" }
            }

            // 7-Zip binaries from :intellij-downloader's extractSevenZipResources.
            // Bundled unpacked at the dist root under `7z/<platform>/` so the proxy
            // can call <install>/7z/<platform>/7zz directly — no extraction-on-
            // first-use detour. The +x bit is preserved on the 7zz binaries.
            from(sevenZipBinariesDir) {
                eachFile {
                    if (file.canExecute()) {
                        permissions { unix("rwxr-xr-x") }
                    }
                }
            }

            // Operator-facing license consolidation. The component-local copies
            // remain in place; these entries give admins one index under licenses/.
            into("licenses/seven-zip") {
                from(sevenZipLicenseFile)
            }
            into("licenses/mcp-steroid") {
                from(rootProject.layout.projectDirectory.file("EULA"))
            }

            // JDK bundling intentionally disabled — see TODO-NPX-BOOTSTRAPPER.md; npx-kt expects Java on PATH today, version.json will tell future bootstraps which Corretto build to fetch.
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

val installDistTask = tasks.named<Sync>("installDist") {
    doFirst {
        destinationDir.takeIf { it.exists() }?.walkBottomUp()?.forEach { file ->
            if (!file.setWritable(true)) {
                logger.debug("installDist pre-clean: could not chmod +w on {}", file)
            }
        }
    }
}

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

val generateVersionJson by tasks.registering {
    group = "build"
    description = "Emit the npx-kt version.json manifest"
    dependsOn(tasks.distZip)
    inputs.file(jdkManifestFile).withPropertyName("jdkManifest")
    inputs.file(ijPluginZipFile).withPropertyName("ijPluginZip")
    inputs.file(tasks.distZip.flatMap { it.archiveFile }).withPropertyName("distZip")
    inputs.property("projectVersion", project.version.toString())
    val outputFile = layout.buildDirectory.file("version.json")
    outputs.file(outputFile)

    doLast {
        val jdkEntries = parseJdkManifest(jdkManifestFile.get().readText())
        val baseUrl = "https://github.com/jonnyzzz/mcp-steroid/releases/download/v${project.version}"

        val distZip = tasks.distZip.get().archiveFile.get().asFile
        val pluginZip = ijPluginZipFile.get()

        val npxKtEntry = linkedMapOf<String, Any?>(
            "name" to "npx-kt",
            "downloadUrl" to "$baseUrl/${distZip.name}",
            "sha-512" to sha512(distZip),
        )
        val ijPluginEntry = linkedMapOf<String, Any?>(
            "name" to "ij-plugin",
            "downloadUrl" to "$baseUrl/${pluginZip.name}",
            "sha-512" to sha512(pluginZip),
        )
        val signature = linkedMapOf<String, Any?>(
            "algorithm" to "pgp",
            "status" to "unsigned-dev-build",
            "value" to null,
        )
        val manifest = linkedMapOf<String, Any?>(
            "packages" to jdkEntries + listOf(npxKtEntry, ijPluginEntry),
            "signature" to signature,
        )

        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(stableJson(manifest))
    }
}

tasks.named("assemble") {
    dependsOn(tasks.distZip, generateVersionJson)
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

        val ijPluginZipEntry = "ij-plugin.zip"
        check(ijPluginZipEntry in allFiles) {
            "Expected $ijPluginZipEntry to be bundled in $distName"
        }
        check("$ijPluginZipEntry:X" !in allFiles) {
            "$ijPluginZipEntry must not be marked executable"
        }
        // licenses/ subtree: additive operator-facing consolidation of each
        // bundled component's license files. Original component-local copies stay
        // under 7z/ and inside ij-plugin.zip.
        val licensesPrefix = "licenses/"
        val licensesFiles = allFiles.filter { it.startsWith(licensesPrefix) }.toSortedSet()
        check(licensesFiles.isNotEmpty()) {
            "licenses/ subtree must be populated in $distName"
        }

        val expectedLicensesFiles = sortedSetOf(
            "licenses/README.md",
            "licenses/seven-zip/License.txt",
            "licenses/mcp-steroid/EULA",
        )
        if (licensesFiles != expectedLicensesFiles) {
            val missing = expectedLicensesFiles - licensesFiles
            val unexpected = licensesFiles - expectedLicensesFiles
            throw GradleException(buildString {
                appendLine("licenses/ subtree mismatch in :npx-kt distZip!")
                if (missing.isNotEmpty()) {
                    appendLine("Missing entries:")
                    missing.forEach { appendLine("  - $it") }
                }
                if (unexpected.isNotEmpty()) {
                    appendLine("Unexpected entries:")
                    unexpected.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("Update `expectedLicensesFiles` in npx-kt/build.gradle.kts if this change is intentional.")
            })
        }
        expectedLicensesFiles.forEach { sentinel ->
            check(sentinel in licensesFiles) {
                "licenses/ subtree missing sentinel '$sentinel'"
            }
        }
        allFiles = (allFiles - licensesFiles).toSortedSet()

        // 7z/ subtree: :intellij-downloader enforces the source layout; this task
        // checks the distZip copy is present, contains no surprise entries, and
        // keeps license files non-executable. The Windows 7z.exe bit is intentionally
        // not pinned: POSIX build hosts may or may not preserve +x, and Windows ignores it.
        val sevenZipPrefix = "7z/"
        val sevenZipFiles = allFiles.filter { it.startsWith(sevenZipPrefix) }.toSortedSet()
        check(sevenZipFiles.isNotEmpty()) {
            "Expected 7z/ subtree to be populated in $distName"
        }
        val expectedSevenZipFiles = sortedSetOf(
            "7z/License.txt",
            "7z/win-x64/7z.exe",
            "7z/win-x64/7z.dll",
            "7z/win-x64/License.txt",
        )
        expectedSevenZipFiles.forEach { sentinel ->
            check(sevenZipFiles.any { it.removeSuffix(":X") == sentinel }) {
                "7z/ subtree is missing sentinel '$sentinel'. Present entries: " +
                        sevenZipFiles.joinToString("\n  ", prefix = "\n  ")
            }
        }
        listOf(
            "7z/License.txt",
            "7z/win-x64/License.txt",
        ).forEach { licensePath ->
            check("$licensePath:X" !in sevenZipFiles) {
                "7z/ subtree wrongly marked '$licensePath' executable; license files must stay non-executable."
            }
        }
        val unexpectedSevenZipFiles = sevenZipFiles
            .filter { it.removeSuffix(":X") !in expectedSevenZipFiles }
            .toSortedSet()
        check(unexpectedSevenZipFiles.isEmpty()) {
            "7z/ subtree has unexpected entries: " + unexpectedSevenZipFiles.joinToString("\n  ", prefix = "\n  ")
        }
        allFiles = (allFiles - sevenZipFiles).toSortedSet()

        val expectedFiles = sortedSetOf(
            // EULA — repo-root EULA at the distribution root, mirroring the
            // copy `:ij-plugin` ships inside its plugin zip.
            "EULA",
            "ij-plugin.zip",

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
