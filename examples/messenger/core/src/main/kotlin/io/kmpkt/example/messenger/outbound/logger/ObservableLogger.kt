package io.kmpkt.example.messenger.outbound.logger

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory SLF4J [Logger] shared across handlers, middleware, and transports so the demo runner
 * can assert on emitted log entries without inspecting transport internals.
 */
class ObservableLogger : Logger by delegate {
    val entries: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    override fun info(msg: String) {
        entries += msg
        delegate.info(msg)
    }

    override fun warn(msg: String) {
        entries += msg
        delegate.warn(msg)
    }

    override fun warn(
        msg: String,
        t: Throwable?,
    ) {
        entries += msg
        delegate.warn(msg, t)
    }

    override fun error(msg: String) {
        entries += msg
        delegate.error(msg)
    }

    override fun error(
        msg: String,
        t: Throwable?,
    ) {
        entries += msg
        delegate.error(msg, t)
    }

    private companion object {
        val delegate: Logger = LoggerFactory.getLogger("messenger-example")
    }
}
