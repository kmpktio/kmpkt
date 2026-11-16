plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.ktlint)
}

group = "io.kmpkt"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    explicitApi()
}

val pluginTestClasspath by configurations.creating

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")

    compileOnly(kotlin("gradle-plugin"))
    implementation("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:${libs.versions.ksp.get()}")

    pluginTestClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")

    testImplementation(gradleTestKit())
    testImplementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

// KSP Gradle plugin metadata targets Kotlin 2.3 while `kotlin-dsl` compiles against Gradle's embedded Kotlin.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

gradlePlugin {
    plugins {
        create("kmpktDi") {
            id = "io.kmpkt.di"
            implementationClass = "io.kmpkt.di.gradle.KmpktDiPlugin"
            displayName = "KompaKt DI Compiler Plugin"
            description = "Configures the KSP-based compile-time DI source set and code generation for kmpkt-di."
        }
    }
}

tasks.pluginUnderTestMetadata {
    pluginClasspath.from(pluginTestClasspath)
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    filter {
        exclude { it.file.path.contains("generated-sources") }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/kmpktio/kmpkt")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
            }
        }
    }
}
