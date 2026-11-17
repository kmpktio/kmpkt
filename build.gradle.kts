import org.gradle.api.component.AdhocComponentWithVariants

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.pitest) apply false
}

subprojects
    .filter { it.name.startsWith("kmpkt-") }
    .forEach { module ->
        module.pluginManager.withPlugin("info.solidsoft.pitest") {
            if (rootProject.file("arcmutate-licence.txt").exists()) {
                module.dependencies.add("pitest", libs.arcmutate.kotlin.plugin)
                module.dependencies.add("pitest", libs.arcmutate.git.plugin)
                val pitestFeatures = providers.gradleProperty("pitest.features").orNull
                if (!pitestFeatures.isNullOrBlank()) {
                    module.extensions.configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
                        features.addAll(pitestFeatures.split(",").map(String::trim).filter(String::isNotEmpty))
                    }
                }
            }
        }
        module.pluginManager.apply("maven-publish")
        module.afterEvaluate {
            val javaComponent = module.components["java"] as? AdhocComponentWithVariants
            listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { name ->
                module.configurations.findByName(name)?.let { cfg ->
                    javaComponent?.withVariantsFromConfiguration(cfg) { skip() }
                }
            }
            module.configurations.findByName("diElements")?.let { diElements ->
                javaComponent?.addVariantsFromConfiguration(diElements) {
                    mapToMavenScope("runtime")
                }
            }
            val publishing = module.extensions.getByType(PublishingExtension::class.java)
            publishing.publications.create("maven", MavenPublication::class.java) {
                from(module.components["java"])
                pom {
                    name.set(module.name)
                    url.set("https://github.com/kmpktio/kmpkt")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
            publishing.repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/kmpktio/kmpkt")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: module.findProperty("gpr.user") as String?
                        password = System.getenv("GITHUB_TOKEN") ?: module.findProperty("gpr.key") as String?
                    }
                }
            }
        }
    }

tasks.register("check") {
    group = "verification"
    description = "Runs all verification tasks across subprojects and the DI Gradle plugin."
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
    dependsOn(gradle.includedBuild("kmpkt-di-compiler-plugin").task(":check"))
}

tasks.register("pitest") {
    group = "verification"
    description = "Runs Pitest mutation tests across configured subprojects."
    dependsOn(
        subprojects
            .filter { it.buildFile.exists() && it.buildFile.readText().contains("pitest") }
            .map { "${it.path}:pitest" },
    )
}

tasks.register("publish") {
    group = "publishing"
    description = "Publishes all kmpkt modules and the DI Gradle plugin to GitHub Packages."
    dependsOn(subprojects.filter { it.name.startsWith("kmpkt-") }.map { "${it.path}:publish" })
    dependsOn(gradle.includedBuild("kmpkt-di-compiler-plugin").task(":publish"))
}

tasks.register("publishToMavenLocal") {
    group = "publishing"
    description = "Publishes all kmpkt modules and the DI Gradle plugin to Maven Local."
    dependsOn(subprojects.filter { it.name.startsWith("kmpkt-") }.map { "${it.path}:publishToMavenLocal" })
    dependsOn(gradle.includedBuild("kmpkt-di-compiler-plugin").task(":publishToMavenLocal"))
}
