plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `java-library`
    id("io.kmpkt.di")
}

group = "io.kmpkt.example"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":kmpkt-di"))
    api(project(":kmpkt-command-bus"))
    api(project(":kmpkt-query-bus"))
    api(project(":kmpkt-aggregate"))
    api(project(":kmpkt-event-store"))
    api(project(":kmpkt-persistence"))
    api(project(":kmpkt-policy"))
    api(project(":kmpkt-projection"))
    api(project(":kmpkt-serialization-kotlinx"))
}
