package io.kmpkt.messenger.retry.middleware

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.retry.MultiplierRetryStrategy
import io.kmpkt.messenger.retry.RetryScheduledException
import io.kmpkt.messenger.stamp.DelayStamp
import io.kmpkt.messenger.stamp.ReceivedStamp
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kmpkt.messenger.transport.InMemoryTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger(RetryMiddlewareTest::class.java)

class RetryMiddlewareTest :
    FunSpec({
        test("passes through when envelope has no ReceivedStamp or no matching strategy") {
            val transport = InMemoryTransport()
            val middleware =
                RetryMiddleware(
                    strategies = mapOf("queue" to MultiplierRetryStrategy(maxRetries = 2)),
                    transports = mapOf("queue" to transport),
                    logger = logger,
                )

            val plain = Envelope(Greet("Milch"))
            middleware.handle(plain) { it } shouldBe plain

            val other = Envelope(Greet("Milch")).with(ReceivedStamp("other"))
            shouldThrow<IllegalStateException> {
                middleware.handle(other) { throw IllegalStateException("boom") }
            }

            val missingTransport =
                RetryMiddleware(
                    strategies = mapOf("queue" to MultiplierRetryStrategy(maxRetries = 2)),
                    transports = emptyMap(),
                    logger = logger,
                )
            shouldThrow<IllegalStateException> {
                missingTransport.handle(Envelope(Greet("Milch")).with(ReceivedStamp("queue"))) {
                    throw IllegalStateException("no-transport")
                }
            }

            val missingStrategy =
                RetryMiddleware(
                    strategies = emptyMap(),
                    transports = mapOf("queue" to transport),
                    logger = logger,
                )
            shouldThrow<IllegalStateException> {
                missingStrategy.handle(Envelope(Greet("Milch")).with(ReceivedStamp("queue"))) {
                    throw IllegalStateException("no-strategy")
                }
            }
            transport.pending() shouldBe emptyList()
        }

        test("re-sends envelope with RedeliveryStamp and DelayStamp and throws RetryScheduledException") {
            val transport = InMemoryTransport()
            val strategy = MultiplierRetryStrategy(maxRetries = 3, delay = 2.seconds, jitter = 0.0)
            val middleware =
                RetryMiddleware(
                    strategies = mapOf("queue" to strategy),
                    transports = mapOf("queue" to transport),
                    logger = logger,
                )
            val envelope = Envelope(Greet("Milch")).with(ReceivedStamp("queue"))

            shouldThrow<RetryScheduledException> {
                middleware.handle(envelope) { throw RuntimeException("boom") }
            }

            transport.pending() shouldBe listOf(envelope.with(RedeliveryStamp(1), DelayStamp(2.seconds)))
        }

        test("rethrows original failure without re-sending when retries are exhausted") {
            val transport = InMemoryTransport()
            val strategy = MultiplierRetryStrategy(maxRetries = 1)
            val middleware =
                RetryMiddleware(
                    strategies = mapOf("queue" to strategy),
                    transports = mapOf("queue" to transport),
                    logger = logger,
                )
            val envelope = Envelope(Greet("Milch")).with(ReceivedStamp("queue"), RedeliveryStamp(1))

            shouldThrow<IllegalStateException> {
                middleware.handle(envelope) { throw IllegalStateException("exhausted") }
            }

            transport.pending() shouldBe emptyList()
        }
    })
