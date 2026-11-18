package io.kmpkt.persistence.jdbc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FuseTest :
    FunSpec({
        test("runOnce executes the block only once after succeeding") {
            val fuse = Fuse()
            var calls = 0

            fuse.runOnce { calls++ }
            fuse.runOnce { calls++ }

            calls shouldBe 1
        }

        test("runOnce retries when a previous attempt threw") {
            val fuse = Fuse()
            var calls = 0

            shouldThrow<IllegalStateException> {
                fuse.runOnce {
                    calls++
                    error("boom")
                }
            }
            fuse.runOnce { calls++ }
            fuse.runOnce { calls++ }

            calls shouldBe 2
        }
    })
