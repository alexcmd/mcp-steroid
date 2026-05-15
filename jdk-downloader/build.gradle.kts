plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}
