package io.kmpkt.example.messenger

import io.kmpkt.di.KmpKt
import io.kotest.core.spec.style.FunSpec

class MessengerAppTest :
    FunSpec({
        test("runs multi-bus routing, middleware stamps, priority events, retries, and dead-lettering") {
            KmpKt.run<MessengerApp>()
        }
    })
