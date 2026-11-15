package io.kmpkt.example.todo.cqrs

import io.kmpkt.di.KmpKt
import io.kotest.core.spec.style.FunSpec

class TodoAppTest :
    FunSpec({
        test("runs TodoApp via KmpKt.run") {
            // Act + Assert (completes without error)
            KmpKt.run<TodoApp>(arrayOf("Buy milk", "Write docs"))
        }
    })
