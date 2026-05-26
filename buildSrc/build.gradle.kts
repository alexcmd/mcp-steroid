plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

// Pull in the shared IDE compatibility matrix from `:intellij-downloader`
// (single source of truth — see `docs/262-EAP-PLAN.md`). Both this buildSrc
// classpath and the `:intellij-downloader` runtime classpath compile the
// same .kt file; the shared folder is dependency-free so buildSrc can
// compile it without pulling additional libraries.
sourceSets.main {
    kotlin.srcDir("../intellij-downloader/src/buildsrc-shared/kotlin")
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation("com.squareup:kotlinpoet:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}
