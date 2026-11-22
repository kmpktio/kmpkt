# kmpkt-di-compiler

Build-time KSP processor and KotlinPoet code generator that compiles the DI container for KmpKt applications.

This module exposes no public Kotlin API to import or extend. It only runs inside KSP during compilation:

1. **In `di` source sets:** discovers DI components and configurations and writes their `META-INF/services` registrations.
2. **In `app` modules:** compiles the container from all discovered registrations on the classpath and generates `io.kmpkt.di.KmpKt`.

## How to use

In most projects, apply [`kmpkt-di-compiler-plugin`](../kmpkt-di-compiler-plugin/README.md) (`io.kmpkt.di`), which adds this processor to KSP automatically.

If you configure KSP manually:

```kotlin
dependencies {
    ksp("io.kmpkt:kmpkt-di-compiler:0.1.0")
}
```

For the [Container DSL](../kmpkt-di-compiler-api/README.md#container-dsl) and extension points compiled by this processor, see [`kmpkt-di-compiler-api`](../kmpkt-di-compiler-api/README.md).
