package io.kmpkt.persistence.jdbc

import io.kmpkt.persistence.LogVersionConflictException
import io.kmpkt.persistence.NewLogEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.nio.file.Files
import java.time.Instant

private fun tempSqliteDataSource(prefix: String) =
    DataSourceFactory.create(
        PersistenceConfig(
            "jdbc:sqlite:${Files.createTempFile(prefix, ".db").toFile().apply { deleteOnExit() }.absolutePath}",
        ),
    )

class JdbcPersistenceTest :
    FunSpec({
        val now = Instant.parse("2026-01-01T00:00:00Z")

        onEachEngine { engine ->
            test("keyValueStore stores and updates values isolated per namespace") {
                val persistence = engine.database().persistence
                val ns1 = persistence.keyValueStore("ns1")
                val ns2 = persistence.keyValueStore("ns2")

                ns1.put("k1", "v1")
                ns2.put("k1", "v2")
                ns1.put("k1", "v1-updated")

                ns1.get("k1") shouldBe "v1-updated"
                ns2.get("k1") shouldBe "v2"
            }

            test("keyValueStore deletes single keys and clears namespaces independently") {
                val persistence = engine.database().persistence
                val ns1 = persistence.keyValueStore("ns1")
                val ns2 = persistence.keyValueStore("ns2")
                ns1.put("k1", "v1")
                ns2.put("k1", "v2")

                ns1.delete("k1")
                ns1.get("k1").shouldBeNull()
                ns2.get("k1") shouldBe "v2"

                ns2.clear()
                ns2.get("k1").shouldBeNull()
            }

            test("appendLog reads stream from latest checkpoint and supports full stream read") {
                val log = engine.database().persistence.appendLog("events")
                log.append(
                    streamType = "Order",
                    streamId = "o-1",
                    expectedVersion = 0,
                    entries =
                        listOf(
                            NewLogEntry("e1", "Created", "{}", now),
                            NewLogEntry("e2", "Snap", "{}", now, checkpoint = true),
                            NewLogEntry("e3", "Paid", "{}", now),
                        ),
                )

                log.readStream("Order", "o-1").map { it.id } shouldBe listOf("e2", "e3")
                log.readStream("Order", "o-1", fromLatestCheckpoint = false).map { it.id } shouldBe
                    listOf("e1", "e2", "e3")
            }

            test("appendLog rejects appends with mismatched expectedVersion") {
                val log = engine.database().persistence.appendLog("events")
                log.append("Order", "o-1", 0, listOf(NewLogEntry("e1", "Created", "{}", now)))

                shouldThrow<LogVersionConflictException> {
                    log.append("Order", "o-1", 0, listOf(NewLogEntry("e2", "Paid", "{}", now)))
                }
            }

            test("appendLog reads global feed excluding checkpoints and isolates log names") {
                val persistence = engine.database().persistence
                val log = persistence.appendLog("events")
                val audit = persistence.appendLog("audit")
                log.append(
                    streamType = "Order",
                    streamId = "o-1",
                    expectedVersion = 0,
                    entries =
                        listOf(
                            NewLogEntry("e1", "Created", "{}", now),
                            NewLogEntry("e2", "Snap", "{}", now, checkpoint = true),
                            NewLogEntry("e3", "Paid", "{}", now),
                        ),
                )
                audit.append("Order", "o-1", 0, listOf(NewLogEntry("a1", "Audited", "{}", now)))

                log.readAfter(0).map { it.id } shouldBe listOf("e1", "e3")
                log.latestPosition() shouldBe 3L
                audit.latestPosition() shouldBe 1L
            }

            test("workQueue claims due items and reschedules nacked items") {
                val jobs = engine.database().persistence.workQueue("jobs")
                jobs.enqueue("j1", "p1", now)
                jobs.enqueue("j2", "p2", now.plusSeconds(60))

                val claimed = jobs.claim(now)
                claimed shouldHaveSize 1
                claimed.first().id shouldBe "j1"
                claimed.first().attempts shouldBe 1
                jobs.claim(now).shouldBeEmpty()

                jobs.nack("j1", now.plusSeconds(10))
                val retried = jobs.claim(now.plusSeconds(10))
                retried shouldHaveSize 1
                retried.first().attempts shouldBe 2
            }

            test("workQueue excludes failed and acknowledged items and isolates queue names") {
                val persistence = engine.database().persistence
                val jobs = persistence.workQueue("jobs")
                val otherQueue = persistence.workQueue("other")
                jobs.enqueue("j1", "p1", now)
                jobs.enqueue("j2", "p2", now)
                otherQueue.enqueue("j1", "other-payload", now)

                jobs.claim(now, limit = 2)
                jobs.fail("j1")
                jobs.ack("j2")

                jobs.claim(now.plusSeconds(60)).shouldBeEmpty()
                otherQueue.claim(now).map { it.payload } shouldBe listOf("other-payload")
            }

            test("inTransaction rolls back nested writes across primitives on failure") {
                val persistence = engine.database().persistence
                val store = persistence.keyValueStore("ns")
                val log = persistence.appendLog("events")
                store.put("k", "before")

                shouldThrow<IllegalStateException> {
                    persistence.inTransaction {
                        store.put("k", "after")
                        persistence.inTransaction {
                            log.append("S", "1", 0, listOf(NewLogEntry("e1", "T", "{}", now)))
                        }
                        error("boom")
                    }
                }

                store.get("k") shouldBe "before"
                log.latestPosition() shouldBe 0L
            }
        }

        test("setup provisions the Flyway schema idempotently") {
            val persistence = JdbcPersistence(tempSqliteDataSource("kmpkt-setup-"))

            persistence.setup()
            persistence.setup()

            val queue = persistence.workQueue("jobs")
            queue.enqueue("j1", "payload", now)
            queue.claim(now).map { it.id } shouldBe listOf("j1")
        }

        test("workQueue setup provisions the Flyway schema idempotently") {
            val queue = JdbcPersistence(tempSqliteDataSource("kmpkt-queue-setup-")).workQueue("jobs")

            queue.setup()
            queue.setup()

            queue.enqueue("j1", "payload", now)
            queue.claim(now).map { it.id } shouldBe listOf("j1")
        }

        test("workQueue lists unclaimed items across pages") {
            val queue = JdbcWorkQueue(tempSqliteDataSource("kmpkt-queue-list-"), "jobs", pageSize = 2)
            queue.setup()
            queue.enqueue("j1", "p1", now)
            queue.enqueue("j2", "p2", now.plusSeconds(1))
            queue.enqueue("j3", "p3", now.plusSeconds(1))

            queue.list().toList().map { it.id } shouldBe listOf("j1", "j2", "j3")
            queue
                .list()
                .take(1)
                .toList()
                .map { it.id } shouldBe listOf("j1")
        }

        test("workQueue finds unclaimed items by id") {
            val queue = JdbcWorkQueue(tempSqliteDataSource("kmpkt-queue-find-"), "jobs")
            queue.setup()
            queue.enqueue("j1", "p1", now)

            queue.find("j1")?.payload shouldBe "p1"
            queue.find("missing").shouldBeNull()
        }

        test("workQueue removes unclaimed items by id") {
            val queue = JdbcWorkQueue(tempSqliteDataSource("kmpkt-queue-remove-"), "jobs")
            queue.setup()
            queue.enqueue("j1", "p1", now)
            queue.enqueue("j2", "p2", now.plusSeconds(1))

            queue.remove("j1") shouldBe true
            queue.remove("j1") shouldBe false
            queue.list().toList().map { it.id } shouldBe listOf("j2")
        }
    })
