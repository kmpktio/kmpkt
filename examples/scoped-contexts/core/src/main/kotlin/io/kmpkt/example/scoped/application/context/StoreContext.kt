package io.kmpkt.example.scoped.application.context

import io.kmpkt.di.KmpKtContext

/** Compile-time context marker shared by `:core` and all store feature modules. */
object StoreContext : KmpKtContext
