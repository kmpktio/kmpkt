package io.kmpkt.persistence.jdbc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class JdbcPersistenceLockTest :
    FunSpec({
        onEachEngine { engine ->
            test("underLock runs the block when nobody holds the name") {
                // Arrange
                val persistence = engine.database().persistence

                // Act + Assert
                persistence.underLock("names_1") { "ran" } shouldBe "ran"
            }

            test("underLock declines while the same name is held") {
                // Arrange
                val persistence = engine.database().persistence
                val held = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                coroutineScope {
                    val holder =
                        async {
                            persistence.underLock("names_1") {
                                held.complete(Unit)
                                release.await()
                            }
                        }
                    held.await()

                    // Act + Assert
                    persistence.underLock("names_1") { "ran" }.shouldBeNull()

                    release.complete(Unit)
                    holder.await()
                }
            }

            test("a different name is a different lock") {
                // Arrange
                val persistence = engine.database().persistence
                val held = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                coroutineScope {
                    val holder =
                        async {
                            persistence.underLock("names_1") {
                                held.complete(Unit)
                                release.await()
                            }
                        }
                    held.await()

                    // Act + Assert
                    persistence.underLock("names_2") { "ran" } shouldBe "ran"

                    release.complete(Unit)
                    holder.await()
                }
            }

            test("the lock is released even when the block throws") {
                // Arrange
                val persistence = engine.database().persistence
                runCatching { persistence.underLock("names_1") { throw IllegalStateException("boom") } }

                // Act + Assert
                persistence.underLock("names_1") { "ran" } shouldBe "ran"
            }

            test("a second Persistence over the same database is excluded too, where the engine can") {
                // Arrange - two adapters stand in for two processes, which a process-local Mutex cannot
                // see. On PostgreSQL pg_try_advisory_lock does; on SQLite there is nothing to ask, and
                // this records that difference rather than pretending it away.
                val database = engine.database()
                val one = database.persistence
                val two = JdbcPersistence(database.dataSource)
                val held = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()

                coroutineScope {
                    val holder =
                        async {
                            one.underLock("names_1") {
                                held.complete(Unit)
                                release.await()
                            }
                        }
                    held.await()

                    // Act
                    val second = two.underLock("names_1") { "ran" }

                    // Assert
                    if (engine.name == "postgres") second.shouldBeNull() else second shouldBe "ran"

                    release.complete(Unit)
                    holder.await()
                }
            }
        }
    })
