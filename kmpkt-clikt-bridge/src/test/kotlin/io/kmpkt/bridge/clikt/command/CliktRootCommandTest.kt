package io.kmpkt.bridge.clikt.command

import com.github.ajalt.clikt.core.CliktCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CliktRootCommandTest :
    FunSpec({
        test("withDefaultCommand uses defaultCommand when arguments are empty") {
            CliktRootCommand.withDefaultCommand(emptyArray(), defaultCommand = "run-server") shouldBe
                arrayOf("run-server")
        }

        test("withDefaultCommand keeps explicit arguments") {
            CliktRootCommand.withDefaultCommand(arrayOf("sync"), defaultCommand = "run-server") shouldBe
                arrayOf("sync")
        }

        test("run dispatches to matching subcommand and falls back to defaultCommand") {
            var ran = 0
            val subcommand =
                object : CliktCommand(name = "hello") {
                    override fun run() {
                        ran += 1
                    }
                }
            val root = CliktRootCommand(name = "test", commands = listOf(subcommand), defaultCommand = "hello")

            root.run(emptyArray())
            root.run(arrayOf("hello"))

            ran shouldBe 2
        }
    })
