package io.kmpkt.example.modulith.telemetry.outbound.telemetry

class SpanRecorder {
    private val spans = mutableListOf<String>()

    fun record(span: String) {
        spans += span
    }

    fun spans(): List<String> = spans.toList()
}
