plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    id("io.kmpkt.di")
}

group = "io.kmpkt.example.modulith"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":examples:modulith:runtime"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    filter {
        exclude { it.file.path.contains("generated") }
    }
}
