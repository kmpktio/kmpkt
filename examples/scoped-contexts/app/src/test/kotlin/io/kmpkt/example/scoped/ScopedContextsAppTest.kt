package io.kmpkt.example.scoped

import io.kmpkt.di.KmpKt
import io.kotest.core.spec.style.FunSpec

/** Verifies multi-module Store context lifecycle, isolation, and teardown. */
class ScopedContextsAppTest :
    FunSpec({
        test("independent feature modules contribute to Store, project views, and tear down on StoreDisconnected") {
            KmpKt.run<ScopedContextsApp>()
        }
    })
