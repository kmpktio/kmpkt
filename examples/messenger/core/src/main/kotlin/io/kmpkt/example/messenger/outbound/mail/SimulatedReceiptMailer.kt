package io.kmpkt.example.messenger.outbound.mail

import io.kmpkt.example.messenger.domain.service.ReceiptMailer
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Simulates a transient outage on the first delivery of `ord-42` and a permanent error for `ord-broken`. */
class SimulatedReceiptMailer(
    private val logger: Logger,
) : ReceiptMailer {
    private val attempts = ConcurrentHashMap<String, AtomicInteger>()

    override fun send(orderId: String) {
        val attempt = attempts.computeIfAbsent(orderId) { AtomicInteger(0) }.incrementAndGet()
        if (orderId == "ord-broken") {
            error("Permanent bounce for $orderId")
        }
        if (orderId == "ord-42" && attempt == 1) {
            error("Temporary SMTP timeout for $orderId")
        }
        logger.info("receipt: delivered $orderId#$attempt")
    }
}
