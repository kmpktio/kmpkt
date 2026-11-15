plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    `java-library`
    id("io.kmpkt.di")
}

group = "io.kmpkt.example.modulith"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":examples:modulith:todos"))
    api(project(":examples:modulith:audit"))
    api(project(":examples:modulith:telemetry"))
    api(project(":examples:modulith:features"))
}
