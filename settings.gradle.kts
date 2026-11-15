rootProject.name = "kmpkt"

include("kmpkt-di")
include("kmpkt-di-compiler-api")
include("kmpkt-di-compiler")
include("kmpkt-worker")
include("kmpkt-messenger")
include("kmpkt-command-bus")
include("kmpkt-event-bus")
include("kmpkt-query-bus")
include("kmpkt-persistence")
include("kmpkt-persistence-jdbc")
include("kmpkt-messenger-persistence")
include("kmpkt-aggregate")
include("kmpkt-event-store")
include("kmpkt-aggregate-snapshot")
include("kmpkt-serialization-kotlinx")
include("kmpkt-policy")
include("kmpkt-projection")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

pluginManagement {
    includeBuild("kmpkt-di-compiler-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.devtools.ksp") {
                useModule("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.3.14")
            }
        }
    }
}
