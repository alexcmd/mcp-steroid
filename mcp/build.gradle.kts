plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1")

    implementation("org.slf4j:slf4j-api:2.0.13")

    implementation(project(":prompts"))

    // Ktor server for MCP HTTP transport
    val ktorVersion = "3.1.0"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")

    // PostHog analytics
    implementation("com.posthog:posthog-server:2.3.0")

    // Ktor client for MCP SSE transport tests
    testImplementation("io.ktor:ktor-client-core:${ktorVersion}")
    testImplementation("io.ktor:ktor-client-cio:${ktorVersion}")
    testImplementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
