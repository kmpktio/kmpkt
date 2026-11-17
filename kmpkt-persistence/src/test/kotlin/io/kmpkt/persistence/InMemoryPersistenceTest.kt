package io.kmpkt.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import java.time.Instant

class InMemoryPersistenceTest :
    FunSpec({
        val now = Instant.parse("2026-01-01T00:00:00Z")

        test("keyValueStore supports put, get, delete, clear isolated per name") {
            val persistence = InMemoryPersistence()
            val ns1 = persistence.keyValueStore("ns1")
            val ns2 = persistence.keyValueStore("ns2")

            ns1.put("k1", "v1")
            ns2.put("k1", "v2")

            ns1.get("k1") shouldBe "v1"
            ns2.get("k1") shouldBe "v2"

            ns1.delete("k1")
            ns1.get("k1").shouldBeNull()
            ns2.get("k1") shouldBe "v2"

            ns2.clear()
            ns2.get("k1").shouldBeNull()
        }

        test("appendLog appends, checks expectedVersion, and reads streams and global log") {
            val persistence = InMemoryPersistence()
            val log = persistence.appendLog("events")

            log.append("Order", "o-1", 0, emptyList())
            log.latestPosition() shouldBe 0L

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
            log.append(
                streamType = "Invoice",
                streamId = "o-1",
                expectedVersion = 0,
                entries = listOf(NewLogEntry("i1", "Issued", "{}", now)),
            )

            val conflict =
                shouldThrow<LogVersionConflictException> {
                    log.append("Order", "o-1", 1, listOf(NewLogEntry("e5", "X", "{}", now)))
                }
            conflict.streamType shouldBe "Order"
            conflict.streamId shouldBe "o-1"
            conflict.expectedVersion shouldBe 1L

            val entries = log.readStream("Order", "o-1", fromLatestCheckpoint = false)
            entries.first().let { first ->
                first.type shouldBe "Created"
                first.payload shouldBe "{}"
                first.streamType shouldBe "Order"
                first.streamId shouldBe "o-1"
            }

            log.readStream("Order", "o-1").map { it.id } shouldBe listOf("e4")
            log.readStream("Order", "o-1", fromVersion = 1, limit = 2, fromLatestCheckpoint = false).map { it.id } shouldBe
                listOf("e2", "e3")
            log.readStream("Order", "o-1", fromLatestCheckpoint = false).map { it.version } shouldBe listOf(1L, 2L, 3L, 4L)
            log.readAfter(1, limit = 2).map { it.id } shouldBe listOf("e3", "i1")
            log.readAfter(0, includeCheckpoints = true).map { it.id } shouldBe listOf("e1", "e2", "e3", "e4", "i1")
            log.latestPosition() shouldBe 5L
            log.latestPosition(includeCheckpoints = true) shouldBe 5L

            val cpOnly = persistence.appendLog("cp-only")
            cpOnly.append("S", "1", 0, listOf(NewLogEntry("c1", "Snap", "{}", now, checkpoint = true)))
            cpOnly.latestPosition(includeCheckpoints = false) shouldBe 0L
            cpOnly.latestPosition(includeCheckpoints = true) shouldBe 1L

            val other = persistence.appendLog("audit")
            other.latestPosition() shouldBe 0L
            other.append("Order", "o-1", 0, listOf(NewLogEntry("a1", "Audited", "{}", now)))
            other.latestPosition() shouldBe 1L
            log.latestPosition() shouldBe 5L
        }

        test("workQueue supports enqueue, claim, nack, fail, and ack") {
            val persistence = InMemoryPersistence()
            val jobs = persistence.workQueue("jobs")

            jobs.claim(now).shouldBeEmpty()
            jobs.nack("missing", now)
            jobs.fail("missing")
            jobs.ack("missing")

            jobs.enqueue("j2", "p2", now.plusSeconds(60))
            jobs.enqueue("j1", "p1", now)
            jobs.enqueue("j3", "p3", now.minusSeconds(5))

            val claimed = jobs.claim(now, limit = 1)
            claimed shouldHaveSize 1
            claimed.first().id shouldBe "j3"
            claimed.first().attempts shouldBe 1

            val secondClaim = jobs.claim(now)
            secondClaim.map { it.id } shouldBe listOf("j1")
            jobs.claim(now).shouldBeEmpty()

            jobs.nack("j1", now.plusSeconds(10))
            jobs.claim(now).shouldBeEmpty()
            val retried = jobs.claim(now.plusSeconds(10))
            retried shouldHaveSize 1
            retried.first().attempts shouldBe 2

            jobs.nack("j1", now.plusSeconds(20))
            jobs.fail("j1")
            jobs.claim(now.plusSeconds(30)).shouldBeEmpty()

            val second = jobs.claim(now.plusSeconds(60))
            second.map { it.id } shouldBe listOf("j2")
            jobs.ack("j2")
            jobs.nack("j2", now)
            jobs.claim(now.plusSeconds(120)).shouldBeEmpty()

            val otherQueue = persistence.workQueue("other")
            otherQueue.enqueue("j1", "other-payload", now)
            otherQueue.claim(now).map { it.payload } shouldBe listOf("other-payload")
        }

        test("inTransaction is re-entrant, returns value on success, and rolls back on failure") {
            val persistence = InMemoryPersistence()
            val store = persistence.keyValueStore("ns")
            val log = persistence.appendLog("events")
            val jobs = persistence.workQueue("jobs")
            store.put("k", "before")
            log.append("S", "1", 0, listOf(NewLogEntry("e0", "Init", "{}", now)))
            jobs.enqueue("j1", "initial", now)

            persistence.inTransaction { "committed" } shouldBe "committed"

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
