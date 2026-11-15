package io.kmpkt.example.modulith

import io.kmpkt.di.KmpKt
import io.kotest.core.spec.style.FunSpec

class ModulithAppTest :
    FunSpec({
        test("composes todos, audit, telemetry, and feature flags via decorators") {
            // Act + Assert (assertions run inside ModulithRunner)
            KmpKt.run<ModulithApp>()
        }
    })
