package io.kmpkt.example.todo.es

import io.kmpkt.di.KmpKt
import io.kotest.core.spec.style.FunSpec

class TodoAppTest :
    FunSpec({
        test("runs TodoEventSourcedApp via KmpKt.run") {
            // Act + Assert (verifies policies and projections inside TodoEventSourcedRunner)
            KmpKt.run<TodoEventSourcedApp>()
        }
    })
