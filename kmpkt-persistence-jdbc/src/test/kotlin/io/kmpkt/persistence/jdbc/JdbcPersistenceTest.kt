package io.kmpkt.persistence.jdbc

import io.kmpkt.persistence.LogVersionConflictException
import io.kmpkt.persistence.NewLogEntry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant

class JdbcPersistenceTest :
    FunSpec({
        val now = Instant.parse("2026-01-01T00:00:00Z")

        onEachEngine { engine ->
            test("keyValueStore supports put, get, delete, clear isolated per name") {
                val persistence = engine.database().persistence
                val ns1 = persistence.keyValueStore("ns1")
                val ns2 = persistence.keyValueStore("ns2")

                ns1.put("k1", "v1")
                ns2.put("k1", "v2")

                ns1.get("k1") shouldBe "v1"
                ns2.get("k1") shouldBe "v2"

                ns1.put("k1", "v1-updated")
                ns1.get("k1") shouldBe "v1-updated"

                ns1.delete("k1")
                ns1.get("k1").shouldBeNull()
                ns2.get("k1") shouldBe "v2"

                ns2.clear()
                ns2.get("k1").shouldBeNull()
            }

            test("appendLog appends, checks expectedVersion, and reads streams and global log") {
                val persistence = engine.database().persistence
                val log = persistence.appendLog("events")

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

                shouldThrow<LogVersionConflictException> {
                    log.append("Order", "o-1", 1, listOf(NewLogEntry("e4", "X", "{}", now)))
                }

                log.readStream("Order", "o-1").map { it.id } shouldBe listOf("e2", "e3")
                log.readStream("Order", "o-1", fromLatestCheckpoint = false).map { it.id } shouldBe
                    listOf("e1", "e2", "e3")
                log.readAfter(0).map { it.id } shouldBe listOf("e1", "e3")
                log.latestPosition() shouldBe 3L

                val other = persistence.appendLog("audit")
                other.latestPosition() shouldBe 0L
                other.append("Order", "o-1", 0, listOf(NewLogEntry("a1", "Audited", "{}", now)))
                other.latestPosition() shouldBe 1L
                log.latestPosition() shouldBe 3L
            }

            test("workQueue supports enqueue, claim, nack, fail, and ack") {
                val persistence = engine.database().persistence
                val jobs = persistence.workQueue("jobs")

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

                jobs.fail("j1")
                jobs.claim(now.plusSeconds(30)).shouldBeEmpty()

                val second = jobs.claim(now.plusSeconds(60))
                second.map { it.id } shouldBe listOf("j2")
                jobs.ack("j2")

                val otherQueue = persistence.workQueue("other")
                otherQueue.enqueue("j1", "other-payload", now)
                otherQueue.claim(now).map { it.payload } shouldBe listOf("other-payload")
            }

            test("inTransaction is re-entrant and rolls back across primitives on failure") {
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
    })
