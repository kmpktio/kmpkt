package io.kmpkt.di.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.gradle.api.attributes.Usage
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

public class KmpktDiPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<KmpktDiExtension>("kmpktDi")

        project.dependencies.attributesSchema.attribute(DI_ELEMENTS_ATTRIBUTE) {
            compatibilityRules.add(DiElementsCompatibilityRule::class.java)
            disambiguationRules.add(DiElementsDisambiguationRule::class.java)
        }

        project.pluginManager.apply("com.google.devtools.ksp")

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            configureJvmProject(project, extension)
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            configureMultiplatformProject(project, extension)
        }
    }

    private fun configureJvmProject(
        project: Project,
        extension: KmpktDiExtension,
    ) {
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        val mainSourceSet = sourceSets.getByName("main")
        val testSourceSet = sourceSets.findByName("test")

        val diSourceSet =
            sourceSets.maybeCreate("di").apply {
                java.setSrcDirs(listOf("src/di/kotlin"))
                resources.setSrcDirs(listOf("src/di/resources"))
            }
        project.kotlinExtension.sourceSets.getByName("di") {
            kotlin.setSrcDirs(listOf("src/di/kotlin"))
        }
        project.afterEvaluate {
            val kotlinExt = project.kotlinExtension as? KotlinJvmProjectExtension ?: return@afterEvaluate
            if (kotlinExt.explicitApi == ExplicitApiMode.Strict) {
                kotlinExt.target.compilations.findByName("di")?.compilerOptions?.configure {
                    freeCompilerArgs.add("-Xexplicit-api=strict")
                }
            }
        }

        val group = extension.groupId.get()
        val version = extension.version.get()
        val runtimeNotation = resolveNotation(project, "kmpkt-di", "$group:kmpkt-di:$version")
        val compilerApiNotation = resolveNotation(project, "kmpkt-di-compiler-api", "$group:kmpkt-di-compiler-api:$version")
        val compilerNotation = resolveNotation(project, "kmpkt-di-compiler", "$group:kmpkt-di-compiler:$version")

        mainSourceSet.runtimeClasspath += diSourceSet.output
        testSourceSet?.let {
            project.dependencies.add(it.implementationConfigurationName, diSourceSet.output)
            (project.kotlinExtension as? KotlinJvmProjectExtension)?.target?.let { target ->
                target.compilations.findByName("test")?.associateWith(target.compilations.getByName("di"))
            }
        }

        val diJarTask =
            project.tasks.register<Jar>("diJar") {
                archiveClassifier.set("di")
                dependsOn("kspDiKotlin")
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                from(diSourceSet.output)
                from(project.layout.buildDirectory.dir("generated/ksp/di/resources"))
            }

        val diElements =
            project.configurations.create("diElements") {
                isCanBeConsumed = true
                isCanBeResolved = false
                extendsFrom(project.configurations.getByName(mainSourceSet.implementationConfigurationName))
                project.configurations.findByName(mainSourceSet.apiConfigurationName)?.let { extendsFrom(it) }
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                    attribute(DI_ELEMENTS_ATTRIBUTE, "di")
                }
            }
        project.artifacts.add(diElements.name, diJarTask)

        val diProcessorClasspath =
            project.configurations.create("diProcessorClasspath") {
                isCanBeConsumed = false
                isCanBeResolved = true
                extendsFrom(project.configurations.getByName(mainSourceSet.implementationConfigurationName))
                project.configurations.findByName(mainSourceSet.apiConfigurationName)?.let { extendsFrom(it) }
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                    attribute(DI_ELEMENTS_ATTRIBUTE, "di")
                }
            }

        project.configurations.getByName(diSourceSet.implementationConfigurationName).apply {
            extendsFrom(project.configurations.getByName(mainSourceSet.implementationConfigurationName))
            extendsFrom(project.configurations.getByName(mainSourceSet.runtimeOnlyConfigurationName))
        }

        val mainRuntimeClasspath = project.configurations.getByName(mainSourceSet.runtimeClasspathConfigurationName)

        project.dependencies {
            add(mainSourceSet.implementationConfigurationName, runtimeNotation)
            add("ksp", compilerNotation)

            add(diSourceSet.implementationConfigurationName, compilerApiNotation)
            add(diSourceSet.implementationConfigurationName, project.files(diProcessorClasspath))
            add("kspDi", compilerNotation)
            add("kspDi", project.files(diProcessorClasspath))
            add("kspDi", project.files(mainRuntimeClasspath))
            add("ksp", project.files(diProcessorClasspath))
            add("ksp", project.files(mainRuntimeClasspath))
        }

        project.afterEvaluate {
            if (declaresApplicationRunnerEntry(mainSourceSet.allSource)) {
                project.dependencies {
                    add("ksp", project.files(diJarTask))
                }
            } else {
                diSourceSet.compileClasspath += mainSourceSet.output
                diSourceSet.runtimeClasspath += mainSourceSet.output
            }
        }
    }

    private fun configureMultiplatformProject(
        project: Project,
        extension: KmpktDiExtension,
    ) {
        val kmp = project.kotlinExtension as KotlinMultiplatformExtension
        val commonMain = kmp.sourceSets.getByName("commonMain")

        val group = extension.groupId.get()
        val version = extension.version.get()
        val runtimeNotation = resolveNotation(project, "kmpkt-di", "$group:kmpkt-di:$version")
        val compilerApiNotation = resolveNotation(project, "kmpkt-di-compiler-api", "$group:kmpkt-di-compiler-api:$version")
        val compilerNotation = resolveNotation(project, "kmpkt-di-compiler", "$group:kmpkt-di-compiler:$version")

        commonMain.dependencies {
            implementation(runtimeNotation)
        }

        val jvmTarget: KotlinTarget =
            kmp.targets.findByName("jvm")
                ?: error("KompaKt DI requires a `jvm()` target in Kotlin Multiplatform projects to compile `src/di/kotlin` for KSP.")

        val jvmMainCompilation = jvmTarget.compilations.getByName("main")
        val diCompilation =
            jvmTarget.compilations.create("di").apply {
                defaultSourceSet {
                    kotlin.setSrcDirs(listOf("src/di/kotlin"))
                    resources.setSrcDirs(listOf("src/di/resources"))
                }
            }

        val diJarTask =
            project.tasks.register<Jar>("diJar") {
                archiveClassifier.set("di")
                dependsOn("kspDiKotlinJvm")
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                from(diCompilation.output.allOutputs)
                from(project.layout.buildDirectory.dir("generated/ksp/jvm/di/resources"))
            }

        val diElements =
            project.configurations.create("diElements") {
                isCanBeConsumed = true
                isCanBeResolved = false
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                    attribute(DI_ELEMENTS_ATTRIBUTE, "di")
                }
            }
        project.artifacts.add(diElements.name, diJarTask)

        val diProcessorClasspath =
            project.configurations.create("diProcessorClasspath") {
                isCanBeConsumed = false
                isCanBeResolved = true
                extendsFrom(project.configurations.getByName(commonMain.implementationConfigurationName))
                extendsFrom(project.configurations.getByName(commonMain.apiConfigurationName))
                extendsFrom(project.configurations.getByName(jvmMainCompilation.defaultSourceSet.implementationConfigurationName))
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                    attribute(DI_ELEMENTS_ATTRIBUTE, "di")
                }
            }

        project.configurations.getByName(diCompilation.defaultSourceSet.implementationConfigurationName).apply {
            extendsFrom(project.configurations.getByName(commonMain.implementationConfigurationName))
            extendsFrom(project.configurations.getByName(jvmMainCompilation.defaultSourceSet.implementationConfigurationName))
        }

        project.dependencies {
            add(diCompilation.defaultSourceSet.implementationConfigurationName, compilerApiNotation)
            add(diCompilation.defaultSourceSet.implementationConfigurationName, project.files(diProcessorClasspath))
            add("kspJvmDi", compilerNotation)
            add("kspJvmDi", project.files(diProcessorClasspath))

            add("kspCommonMainMetadata", compilerNotation)
            add("kspCommonMainMetadata", project.files(diProcessorClasspath))
        }

        kmp.targets.configureEach {
            if (name != "metadata") {
                val capitalized = name.replaceFirstChar { it.uppercaseChar() }
                val kspConfigName = "ksp$capitalized"
                project.dependencies {
                    add(kspConfigName, compilerNotation)
                    add(kspConfigName, project.files(diProcessorClasspath))
                }
            }
        }

        project.afterEvaluate {
            val hasEntry =
                declaresApplicationRunnerEntry(commonMain.kotlin) ||
                    declaresApplicationRunnerEntry(jvmMainCompilation.defaultSourceSet.kotlin)
            if (hasEntry) {
                project.dependencies {
                    add("kspCommonMainMetadata", project.files(diJarTask))
                }
                kmp.targets.configureEach {
                    if (name != "metadata") {
                        val capitalized = name.replaceFirstChar { it.uppercaseChar() }
                        project.dependencies {
                            add("ksp$capitalized", project.files(diJarTask))
                        }
                    }
                }
            } else {
                diCompilation.associateWith(jvmMainCompilation)
            }
        }
    }

    private fun declaresApplicationRunnerEntry(sources: FileTree): Boolean =
        sources.matching { include("**/*.kt") }.files.any { file ->
            file.readText().contains("KmpKt.run")
        }

    // @todo Can be simplified to plain Maven coordinates `"$group:$moduleName:$version"` once kmpkt is published from its own repository.
    private fun resolveNotation(
        project: Project,
        moduleName: String,
        mavenCoordinate: String,
    ): Any = project.rootProject.findProject(":$moduleName") ?: mavenCoordinate

    private companion object {
        val DI_ELEMENTS_ATTRIBUTE: Attribute<String> = Attribute.of("io.kmpkt.di.elements", String::class.java)
    }
}

public abstract class DiElementsCompatibilityRule : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) {
        if (details.producerValue == null) {
            details.compatible()
        }
    }
}

public abstract class DiElementsDisambiguationRule : AttributeDisambiguationRule<String> {
    override fun execute(details: MultipleCandidatesDetails<String>) {
        if ("di" in details.candidateValues) {
            details.closestMatch("di")
        }
    }
}
