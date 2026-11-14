# kmpkt-di

Runtime contracts for KMPKt's compile-time dependency injection.

## Scope

- `KmpKtApplication`: marker interface for an application entry point wired by a `Configuration<A>`.
- `ApplicationRunner`: functional interface (`run(args: Array<String>)`) whose implementations are autoconfigured and invoked by `KmpKt.run<A>(args)`.

## Usage

```kotlin
object TodoApp : KmpKtApplication

class TodoRunner(
    private val commands: CommandBus,
) : ApplicationRunner {
    override fun run(args: Array<String>) = runBlocking {
        commands.dispatch(AddTodo("1", "Buy milk"))
    }
}

fun main(args: Array<String>) = KmpKt.run<TodoApp>(args)
```

## Related Modules

- [`kmpkt-di-compiler-api`](../kmpkt-di-compiler-api): container builder, `Component`, `Configuration`, `BuilderPass`, and compiler pipeline.
- [`kmpkt-di-compiler`](../kmpkt-di-compiler): KSP processor and KotlinPoet code generator.
- [`kmpkt-di-compiler-plugin`](../kmpkt-di-compiler-plugin): Gradle plugin (`io.kmpkt.di`) wiring `di` source sets and KSP.
