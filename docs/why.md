# Why KmpKt?

> **Active Development:** KmpKt is in active early development (pre-1.0). APIs, module boundaries and configuration DSLs may still change between releases.

Over the years, I built modular monoliths in different languages and frameworks. For me, the best developer experience came from PHP with Symfony.

Two components made the difference: **Symfony DependencyInjection** and **Symfony Messenger**. Over time, I adapted parts of both across several private and professional Kotlin projects. KmpKt brings them together on top of Kotlin's type safety, coroutines and KSP.

## The Problem: X Modules, Y Artifacts

In many projects, one codebase holds X modules and you have to build Y artifacts from different subsets of those modules, depending on the customer, industry or deployment.

No module can assume which other modules are present in a given build. Hard-coded wiring and central registration lists break as soon as a module is omitted or replaced.

## Symfony DependencyInjection

Runtime DI containers like Koin handle constructor wiring, but lack cross-module compiler passes, native service decoration and build-time verification of the full object graph.

Symfony's compiled container model solves this with three patterns:

- **Compiler passes across modules:** Included modules inspect and transform the dependency graph before the application runs.
- **Service tagging:** Handlers, policies, projections and middleware are collected by tag, so each artifact wires only what is present.
- **Service decoration:** Cross-cutting modules (logging, auditing, feature flags) wrap existing services without touching the original module.

## Symfony Messenger

Many libraries offer a basic message bus. Symfony Messenger stands out through how it separates concerns around the message flow:

- **Envelopes and stamps:** Typed `Stamp` objects carry metadata like routing, delays, retries and failure details on an `Envelope`, so modules can attach their own metadata without sharing a central header map.
- **Middleware pipelines:** Cross-cutting behavior (transactions, outbox, retries, logging) reads and attaches stamps in one composable pipeline.
- **Pluggable transports:** Routing is configuration, not caller code. The same dispatch can run synchronously in-process or asynchronously through a worker.

On top of that, `0..N` events, CQRS and event-sourced workflows all share the same bus model.

## Why Kotlin?

Instead of runtime reflection or annotation magic, KmpKt brings these patterns to Kotlin using the language directly:

- Compile-time type safety and `sealed` message hierarchies.
- KSP code generation that fails broken wiring at build time.
- Coroutines for suspendable handlers, middleware and workers.

## Credits

KmpKt is inspired by **Symfony DependencyInjection** and **Symfony Messenger**.

Full respect to **Fabien Potencier** and the **Symfony** team for building such a solid framework.

## Next

- [Getting Started](getting-started.md) - Add KmpKt to a Gradle project and wire your first application.
- [Modulith Example](../examples/modulith/README.md) - See independent modules composed with decorators and component options.
