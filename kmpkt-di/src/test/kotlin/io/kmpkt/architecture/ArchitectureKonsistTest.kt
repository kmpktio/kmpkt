package io.kmpkt.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import io.kotest.core.spec.style.FunSpec

class ArchitectureKonsistTest :
    FunSpec({
        test("project uses kotlin.uuid.Uuid instead of java.util.UUID") {
            Konsist
                .scopeFromProject()
                .files
                .assertFalse { file ->
                    file.hasImport { it.name == "java.util.UUID" }
                }
        }

        test("project avoids Java utility types with Kotlin stdlib equivalents") {
            Konsist
                .scopeFromProject()
                .files
                .assertFalse { file ->
                    file.hasImport {
                        it.name == "java.util.Optional" ||
                            it.name.startsWith("java.util.stream.") ||
                            it.name.startsWith("java.util.function.")
                    }
                }
        }

        test("project avoids GlobalScope in favor of structured concurrency") {
            Konsist
                .scopeFromProject()
                .files
                .assertFalse { file ->
                    file.hasImport { it.name == "kotlinx.coroutines.GlobalScope" }
                }
        }
    })
