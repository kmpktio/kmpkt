package io.kmpkt.example.modulith.audit.outbound.audit

class AuditLog {
    private val entries = mutableListOf<String>()

    fun record(entry: String) {
        entries += entry
    }

    fun entries(): List<String> = entries.toList()
}
