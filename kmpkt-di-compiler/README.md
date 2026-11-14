# kmpkt-di-compiler

KSP symbol processor and KotlinPoet code generator for [`kmpkt-di`](../kmpkt-di).

## Scope

- `ContainerProcessorProvider` / `ContainerProcessor`:
  - In a `di` source set or `-di` module, discovers `Component` and `Configuration` implementations and registers them in `META-INF/services`.
  - In an application module, loads components and configurations from the processor classpath, runs `ContainerCompiler` for each `Configuration<A>`, and generates `KmpKt.run<A>(args)`.
- `KspTypeOracle`: adapts KSP type and constructor metadata to `TypeOracle` from [`kmpkt-di-compiler-api`](../kmpkt-di-compiler-api).
- `ContainerGenerator`: emits the generated Kotlin container source file via KotlinPoet.
