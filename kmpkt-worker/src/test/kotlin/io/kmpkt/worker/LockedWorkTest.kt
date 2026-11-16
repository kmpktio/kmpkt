package io.kmpkt.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LockedWorkTest :
    FunSpec({
        test("runs delegate under lock and returns its processed count when acquired") {
            val locks = mutableListOf<String>()
            val lock =
                object : WorkLock {
                    override suspend fun <R> underLock(
                        name: String,
                        block: suspend () -> R,
                    ): R {
                        locks += name
                        return block()
                    }
                }
            var ran = false
            val delegate =
                Work {
                    ran = true
                    3
                }

            val result = LockedWork(delegate, lock, "leader-lock").run()

            locks shouldBe listOf("leader-lock")
            ran shouldBe true
            result shouldBe 3
        }

        test("skips delegate and returns 0 when lock is held elsewhere") {
            val locks = mutableListOf<String>()
            val lock =
                object : WorkLock {
                    override suspend fun <R> underLock(
                        name: String,
                        block: suspend () -> R,
                    ): R? {
                        locks += name
                        return null
                    }
                }
            var ran = false
            val delegate =
                Work {
                    ran = true
                    1
                }

            val result = LockedWork(delegate, lock, "leader-lock").run()

            locks shouldBe listOf("leader-lock")
            ran shouldBe false
            result shouldBe 0
        }
    })
