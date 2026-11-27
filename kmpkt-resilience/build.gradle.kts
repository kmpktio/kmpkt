plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.pitest)
    `java-library`
}

group = "io.kmpkt"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(project(":test-support"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)

    pitest(libs.kotest.extensions.pitest)
}

tasks.test {
    useJUnitPlatform()
}

pitest {
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("io.kmpkt.resilience.*"))
    excludedMethods.set(listOf("equals", "hashCode", "toString", "copy", "copy\$default", "component*"))
    avoidCallsTo.set(listOf("org.slf4j", "kotlin.jvm.internal", "kotlin.ResultKt"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    mutationThreshold.set(70)
}
