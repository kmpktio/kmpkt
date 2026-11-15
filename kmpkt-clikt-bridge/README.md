# kmpkt-clikt-bridge

Clikt integration for `kmpkt-di`: provides `CliktRootCommand` as an `ApplicationRunner` and automatically collects every registered `CliktCommand` as a subcommand.

```kotlin
clikt {
    name = "my-app"
    defaultCommand = "run-server"
}
```
