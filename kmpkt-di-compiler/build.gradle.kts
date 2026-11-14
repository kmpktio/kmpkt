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

dependencies {
    // api: a `-di` module that puts the processor on `ksp` gets the Bundle types it loads from the same place.
    api(project(":kmpkt-di-compiler-api"))
    implementation(project(":kmpkt-di"))
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kctfork.ksp)
    // kctfork brings the KSP implementation it was built against; these keep it on the version of the API above.
    testImplementation(libs.ksp.common.deps)
    testImplementation(libs.ksp.aa.embeddable)
}

tasks.test {
    useJUnitPlatform()
}
