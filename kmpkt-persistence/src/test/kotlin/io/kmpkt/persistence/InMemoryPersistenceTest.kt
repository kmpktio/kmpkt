package io.kmpkt.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import java.time.Instant

class InMemoryPersistenceTest :
    FunSpec({
        val now = Instant.parse("2026-01-01T00:00:00Z")

        test("keyValueStore stores and overwrites values isolated per namespace") {
            val persistence = InMemoryPersistence()
            val ns1 = persistence.keyValueStore("ns1")
            val ns2 = persistence.keyValueStore("ns2")

            ns1.put("k1", "v1")
            ns2.put("k1", "v2")
            ns1.put("k1", "v1-updated")

            ns1.get("k1") shouldBe "v1-updated"
            ns2.get("k1") shouldBe "v2"
        }

        test("keyValueStore deletes single keys and clears namespaces independently") {
            val persistence = InMemoryPersistence()
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

        test("appendLog ignores empty appends") {
            val log = InMemoryPersistence().appendLog("events")

            log.append("Order", "o-1", 0, emptyList())

            log.latestPosition() shouldBe 0L
        }

        test("appendLog rejects appends with mismatched expectedVersion") {
            val log = InMemoryPersistence().appendLog("events")
            log.append("Order", "o-1", 0, listOf(NewLogEntry("e1", "Created", "{}", now)))

            val conflict =
                shouldThrow<LogVersionConflictException> {
                    log.append("Order", "o-1", 0, listOf(NewLogEntry("e2", "Paid", "{}", now)))
                }

            conflict.streamType shouldBe "Order"
            conflict.streamId shouldBe "o-1"
            conflict.expectedVersion shouldBe 0L
        }

        test("appendLog reads stream from latest checkpoint by default and supports full range slicing") {
            val log = InMemoryPersistence().appendLog("events")
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
            log.append(
                streamType = "Order",
                streamId = "o-1",
                expectedVersion = 3,
                entries = listOf(NewLogEntry("e4", "FinalSnap", "{}", now, checkpoint = true)),
            )

            val allEntries = log.readStream("Order", "o-1", fromLatestCheckpoint = false)
            allEntries.first().let { first ->
                first.type shouldBe "Created"
                first.payload shouldBe "{}"
                first.streamType shouldBe "Order"
                first.streamId shouldBe "o-1"
            }
            allEntries.map { it.version } shouldBe listOf(1L, 2L, 3L, 4L)
            log.readStream("Order", "o-1").map { it.id } shouldBe listOf("e4")
            log.readStream("Order", "o-1", fromVersion = 1, limit = 2, fromLatestCheckpoint = false).map { it.id } shouldBe
                listOf("e2", "e3")
        }

        test("appendLog reads global log entries excluding checkpoints unless requested") {
            val log = InMemoryPersistence().appendLog("events")
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
            log.append(
                streamType = "Invoice",
                streamId = "o-1",
                expectedVersion = 0,
                entries = listOf(NewLogEntry("i1", "Issued", "{}", now)),
            )

            log.readAfter(1, limit = 2).map { it.id } shouldBe listOf("e3", "i1")
            log.readAfter(0, includeCheckpoints = true).map { it.id } shouldBe listOf("e1", "e2", "e3", "i1")
            log.latestPosition() shouldBe 4L
            log.latestPosition(includeCheckpoints = true) shouldBe 4L
        }

        test("appendLog isolates positions per log name and checkpoint visibility") {
            val persistence = InMemoryPersistence()
            val cpOnly = persistence.appendLog("cp-only")
            val audit = persistence.appendLog("audit")

            cpOnly.append("S", "1", 0, listOf(NewLogEntry("c1", "Snap", "{}", now, checkpoint = true)))
            audit.append("Order", "o-1", 0, listOf(NewLogEntry("a1", "Audited", "{}", now)))

            cpOnly.latestPosition(includeCheckpoints = false) shouldBe 0L
            cpOnly.latestPosition(includeCheckpoints = true) shouldBe 1L
            audit.latestPosition() shouldBe 1L
        }

        test("workQueue claims due items oldest-first up to limit") {
            val jobs = InMemoryPersistence().workQueue("jobs")
            jobs.claim(now).shouldBeEmpty()

            jobs.enqueue("j2", "p2", now.plusSeconds(60))
            jobs.enqueue("j1", "p1", now)
            jobs.enqueue("j3", "p3", now.minusSeconds(5))

            val firstClaim = jobs.claim(now, limit = 1)
            firstClaim shouldHaveSize 1
            firstClaim.first().id shouldBe "j3"
            firstClaim.first().attempts shouldBe 1

            jobs.claim(now).map { it.id } shouldBe listOf("j1")
            jobs.claim(now).shouldBeEmpty()
            jobs.claim(now.plusSeconds(60)).map { it.id } shouldBe listOf("j2")
        }

        test("workQueue reschedules nacked items and increments attempt count") {
            val jobs = InMemoryPersistence().workQueue("jobs")
            jobs.enqueue("j1", "p1", now)
            jobs.claim(now)

            jobs.nack("j1", now.plusSeconds(10))

            jobs.claim(now).shouldBeEmpty()
            val retried = jobs.claim(now.plusSeconds(10))
            retried shouldHaveSize 1
            retried.first().attempts shouldBe 2
        }

        test("workQueue excludes failed and acknowledged items from future claims") {
            val jobs = InMemoryPersistence().workQueue("jobs")
            jobs.enqueue("j1", "p1", now)
            jobs.enqueue("j2", "p2", now)
            jobs.claim(now, limit = 2)

            jobs.fail("j1")
            jobs.ack("j2")
            jobs.nack("j2", now)

            jobs.claim(now.plusSeconds(120)).shouldBeEmpty()
        }

        test("workQueue ignores ack, nack, and fail on unknown item ids") {
            val jobs = InMemoryPersistence().workQueue("jobs")

            jobs.nack("missing", now)
            jobs.fail("missing")
            jobs.ack("missing")

            jobs.claim(now).shouldBeEmpty()
        }

        test("workQueue lists unclaimed items ordered by availableAt and id") {
            val persistence = InMemoryPersistence()
            val queue = persistence.workQueue("jobs")
            queue.enqueue("b", "payload-b", now)
            queue.enqueue("a", "payload-a", now)

            queue.list().toList().map { it.id } shouldBe listOf("a", "b")

            queue.claim(now, limit = 2)
            queue.list().toList().shouldBeEmpty()
        }

        test("workQueue isolates items by queue name") {
            val persistence = InMemoryPersistence()
            val first = persistence.workQueue("first")
            val second = persistence.workQueue("second")
            first.enqueue("j1", "first-payload", now)
            second.enqueue("j1", "second-payload", now)

            first.claim(now).map { it.payload } shouldBe listOf("first-payload")
            second.claim(now).map { it.payload } shouldBe listOf("second-payload")
        }

        test("workQueue finds unclaimed items by id and hides claimed or missing items") {
            val persistence = InMemoryPersistence()
            val queue = persistence.workQueue("jobs")
            queue.enqueue("a", "payload-a", now)

            queue.find("a")?.payload shouldBe "payload-a"
            queue.find("missing").shouldBeNull()

            queue.claim(now)
            queue.find("a").shouldBeNull()
        }

        test("workQueue removes unclaimed items by id") {
            val persistence = InMemoryPersistence()
            val queue = persistence.workQueue("jobs")
            queue.enqueue("a", "payload-a", now)
            queue.enqueue("b", "payload-b", now)

            queue.remove("b") shouldBe true
            queue.remove("missing") shouldBe false
            queue.claim(now).map { it.payload } shouldBe listOf("payload-a")
        }

        test("inTransaction commits and returns block value on success") {
            val persistence = InMemoryPersistence()
            val store = persistence.keyValueStore("ns")

            val result =
                persistence.inTransaction {
                    store.put("k", "committed")
                    "ok"
                }

            result shouldBe "ok"
            store.get("k") shouldBe "committed"
        }

        test("inTransaction rolls back nested writes across all primitives on failure") {
            val persistence = InMemoryPersistence()
            val store = persistence.keyValueStore("ns")
            val log = persistence.appendLog("events")
            val jobs = persistence.workQueue("jobs")
            store.put("k", "before")
            log.append("S", "1", 0, listOf(NewLogEntry("e0", "Init", "{}", now)))
            jobs.enqueue("j1", "initial", now)

            shouldThrow<IllegalStateException> {
                persistence.inTransaction {
                    store.put("k", "after")
                    store.put("k2", "new")
                    jobs.enqueue("j2", "added", now)
                    jobs.claim(now)
                    persistence.inTransaction {
                        log.append("S", "1", 1, listOf(NewLogEntry("e1", "T", "{}", now)))
                    }
                    error("boom")
                }
            }

            store.get("k") shouldBe "before"
            store.get("k2").shouldBeNull()
            log.latestPosition() shouldBe 1L
            jobs.claim(now).map { it.id } shouldBe listOf("j1")
        }

        test("underLock declines concurrent holders and releases after completion") {
            val persistence = InMemoryPersistence()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val first =
                async {
                    persistence.underLock("worker") {
                        entered.complete(Unit)
                        release.await()
                        "ok"
                    }
                }
            entered.await()

            persistence.underLock("worker") { "nope" }.shouldBeNull()

            release.complete(Unit)
            first.await() shouldBe "ok"
            persistence.underLock("worker") { "again" } shouldBe "again"
        }
    })
