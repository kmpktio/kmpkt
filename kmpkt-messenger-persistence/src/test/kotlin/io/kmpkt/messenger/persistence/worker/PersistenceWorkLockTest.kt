package io.kmpkt.messenger.persistence.worker

import io.kmpkt.persistence.InMemoryPersistence
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async

class PersistenceWorkLockTest :
    FunSpec({
        test("executes block when the named lock is free") {
            val lock = PersistenceWorkLock(InMemoryPersistence())

            lock.underLock("worker") { "acquired" } shouldBe "acquired"
        }

        test("returns null when the named lock is already held") {
            val lock = PersistenceWorkLock(InMemoryPersistence())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val holder =
                async {
                    lock.underLock("worker") {
                        entered.complete(Unit)
                        release.await()
                        "first"
                    }
                }
            entered.await()

            lock.underLock("worker") { "second" }.shouldBeNull()

            release.complete(Unit)
            holder.await() shouldBe "first"
        }
    })
