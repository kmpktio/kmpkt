rootProject.name = "kmpkt"

include("kmpkt-di")
include("kmpkt-di-compiler-api")
include("kmpkt-di-compiler")
include("kmpkt-worker")
include("kmpkt-messenger")

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
