package io.kmpkt.messenger.persistence.middleware

import io.kmpkt.messenger.bus.DefaultMessageBus
import io.kmpkt.messenger.handler.DefaultHandlerRegistry
import io.kmpkt.messenger.handler.HandlerPolicy
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.handler.middleware.HandleMessageMiddleware
import io.kmpkt.messenger.message.Message
import io.kmpkt.messenger.message.MessageHandler
import io.kmpkt.persistence.InMemoryPersistence
import io.kmpkt.test.TestClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private data class TransactionalJob(
    val name: String,
    val shouldFail: Boolean = false,
) : Message

private fun transactionalBus(persistence: InMemoryPersistence): DefaultMessageBus {
    val store = persistence.keyValueStore("state")
    val registry =
        DefaultHandlerRegistry().apply {
            register(
                TransactionalJob::class,
                object : MessageHandler<TransactionalJob, Unit> {
                    override suspend fun invoke(message: TransactionalJob) {
                        store.put("last", message.name)
                        if (message.shouldFail) error("boom")
                    }
                },
            )
        }
    return DefaultMessageBus(
        middleware =
            listOf(
                TransactionMiddleware(persistence),
                HandleMessageMiddleware(registry, HandlerPolicy.SINGLE, TestClock()),
            ),
        handlerRegistry = registry,
    )
}

class TransactionMiddlewareTest :
    FunSpec({
        test("commits persistence writes when handler succeeds") {
            val persistence = InMemoryPersistence()
            val bus = transactionalBus(persistence)

            bus.dispatch(TransactionalJob("ok"))

            persistence.keyValueStore("state").get("last") shouldBe "ok"
        }

        test("rolls back persistence writes when handler fails") {
            val persistence = InMemoryPersistence()
            val bus = transactionalBus(persistence)

            shouldThrow<HandlerFailedException> {
                bus.dispatch(TransactionalJob("broken", shouldFail = true))
            }

            persistence.keyValueStore("state").get("last").shouldBeNull()
        }
    })
