import org.gradle.api.attributes.Usage

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

    // SLF4J binding for the launcher. We use Logback (not slf4j-simple) so
    // operators can drop in a `logback.xml` to add appenders, change levels,
    // or route specific loggers — slf4j-simple has no real configuration
    // surface. The bundled `logback.xml` (in src/main/resources) routes
    // everything to stderr only — never stdout, which is reserved for MCP
    // NDJSON frames.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

application {
    applicationName = "mcp-steroid-proxy"
    mainClass.set("com.jonnyzzz.mcpSteroid.proxy.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
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
