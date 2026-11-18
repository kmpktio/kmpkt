package io.kmpkt.messenger.bus.middleware

import io.kmpkt.messenger.bus.DefaultMessageBus
import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.handler.DefaultHandlerRegistry
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.middleware.HandleMessageMiddleware
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.messenger.stamp.RateLimitedStamp
import io.kmpkt.resilience.RateLimiter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock

private class CountingRateLimiter : RateLimiter {
    var acquired = 0

    override suspend fun tryAcquire(permits: Int): Boolean {
        acquired += permits
        return true
    }

    override suspend fun acquire(permits: Int) {
        acquired += permits
    }
}

class RateLimitedMiddlewareTest :
    FunSpec({
        test("acquires from the matching limiter when RateLimitedStamp is present") {
            val limiter = CountingRateLimiter()
            val handled = mutableListOf<String>()
            val registry =
                DefaultHandlerRegistry(
                    mapOf(
                        Greet::class to
                            listOf(
                                MessageHandler<Greet, Unit> { greet -> handled += greet.name },
                            ),
                    ),
                )
            val bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            RateLimitedMiddleware("api", limiter),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            bus.dispatch(Envelope(Greet("throttled")).with(RateLimitedStamp("api")))
            bus.dispatch(Greet("unthrottled"))

            limiter.acquired shouldBe 1
            handled shouldBe listOf("throttled", "unthrottled")
        }

        test("throws when RateLimitedStamp references an unknown limiter") {
            val registry = DefaultHandlerRegistry(emptyMap())
            val bus =
                DefaultMessageBus(
                    middleware =
                        listOf(
                            RateLimitedMiddleware(emptyMap()),
                            HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, Clock.systemUTC()),
                        ),
                    handlerRegistry = registry,
                )

            shouldThrow<IllegalStateException> {
                bus.dispatch(Envelope(Greet("bad")).with(RateLimitedStamp("missing")))
            }
        }
    })
