plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
}

group = "io.kmpkt"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    explicitApi()
}
