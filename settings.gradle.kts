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
include("kmpkt-clikt-bridge")
include("kmpkt-ktor-bridge")
include("examples:todo-cqrs:core")
include("examples:todo-cqrs:app")
include("examples:todo-event-sourced:core")
include("examples:todo-event-sourced:app")
include("examples:messenger:core")
include("examples:messenger:app")
include("examples:modulith:todos")
include("examples:modulith:audit")
include("examples:modulith:telemetry")
include("examples:modulith:features")
include("examples:modulith:runtime")
include("examples:modulith:app")

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
