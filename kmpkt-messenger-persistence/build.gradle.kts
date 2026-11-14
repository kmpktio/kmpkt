plugins {
    id("io.kmpkt.di")
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

dependencies {
    api(project(":kmpkt-messenger"))
    api(project(":kmpkt-persistence"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}
