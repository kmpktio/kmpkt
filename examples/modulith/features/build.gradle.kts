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
    implementation(project(":examples:modulith:todos"))
}
