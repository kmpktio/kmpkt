# kmpkt-di-compiler-api

Pure-Kotlin container model, component SPI, and multi-phase pass pipeline used by [`kmpkt-di-compiler`](../kmpkt-di-compiler) and module `di` source sets.

## Scope

- `Component<C>` & `SimpleComponent`: SPI implemented by feature modules to declare `requires`, default configuration (`defaults()`), `prepare()`, and `register(config)` on a `ContainerBuilder`.
- `ContainerBuilder`, `Service`, `Argument`, `TypeRef`, `TypeOracle`: mutable definition model for services, aliases, tags, decorators, autoconfiguration, and diagnostics.
- `BuilderPass` & `PassPhase`: extensible compiler passes ordered across `BeforeOptimization`, `Optimization`, `BeforeRemoving`, `Removing`, and `AfterRemoving`.
- `ContainerCompiler`: executes components in topological order, runs `Configuration`s, executes built-in and custom `BuilderPass`es, and produces a `CompiledContainer`.

## Built-in Passes

In `io.kmpkt.di.compiler.pass`: `ApplyAutoconfiguration`, `ResolveDecorators`, `Autowire`, `BindTypedArguments`, `ResolveTaggedArguments`, `ResolveAliases`, `ReportDefinitionErrors`, `CheckReferences`, `RemoveUnused`, `InlineSingleUse`.
