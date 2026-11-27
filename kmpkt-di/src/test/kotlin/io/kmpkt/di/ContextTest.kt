package io.kmpkt.di

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private interface ExtraView {
    val label: String
}

private interface MissingView

private class TestHandle(
    override val context: String,
    override val label: String,
) : ContextHandle<String>,
    ExtraView {
    var closed = false

    override fun close() {
        closed = true
    }
}

class ContextTest :
    FunSpec({
        test("ContextScope invoke(input, block) keeps handle open inside block and closes it afterwards") {
            var lastHandle: TestHandle? = null
            val scope =
                object : ContextScope<String, TestHandle> {
                    override fun open(input: String): TestHandle = TestHandle(input, "v1").also { lastHandle = it }
                }

            val result =
                scope("tenant-1") {
                    closed shouldBe false
                    context
                }

            result shouldBe "tenant-1"
            lastHandle?.closed shouldBe true
        }

        test("ContextScope<Unit, H> invoke(block) opens and closes without an input argument") {
            var lastHandle: TestHandle? = null
            val scope =
                object : ContextScope<Unit, TestHandle> {
                    override fun open(input: Unit): TestHandle = TestHandle("unit-ctx", "v1").also { lastHandle = it }
                }

            val result = scope { context }

            result shouldBe "unit-ctx"
            lastHandle?.closed shouldBe true
        }

        test("ContextScope invoke closes handle even when block throws") {
            var lastHandle: TestHandle? = null
            val scope =
                object : ContextScope<Unit, TestHandle> {
                    override fun open(input: Unit): TestHandle = TestHandle("unit-ctx", "v1").also { lastHandle = it }
                }

            shouldThrow<IllegalStateException> {
                scope { error("boom") }
            }

            lastHandle?.closed shouldBe true
        }

        test("ContextHandle view returns matching implemented view") {
            val handle = TestHandle("unit-ctx", "v1")

            handle.view<ExtraView>().label shouldBe "v1"
        }

        test("ContextHandle view throws when handle does not implement requested view") {
            val handle = TestHandle("unit-ctx", "v1")

            val error =
                shouldThrow<IllegalStateException> {
                    handle.view<MissingView>()
                }

            error.message shouldContain "does not implement view"
        }

        test("ContextFactory default destroy is a safe no-op") {
            val factory =
                object : ContextFactory<String, Int> {
                    override fun create(input: Int): String = "ctx-$input"
                }

            val created = factory.create(7)
            created shouldBe "ctx-7"
            factory.destroy(created)
        }
    })
