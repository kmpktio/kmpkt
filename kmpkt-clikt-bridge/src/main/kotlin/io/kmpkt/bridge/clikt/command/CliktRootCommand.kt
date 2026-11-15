package io.kmpkt.bridge.clikt.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import io.kmpkt.di.ApplicationRunner

/**
 * Root Clikt command wired as an [ApplicationRunner] that dispatches to all discovered [commands],
 * falling back to [defaultCommand] when no CLI arguments are provided.
 */
public class CliktRootCommand(
    name: String,
    commands: List<CliktCommand>,
    private val defaultCommand: String? = null,
) : NoOpCliktCommand(name = name),
    ApplicationRunner {
    init {
        subcommands(commands)
    }

    override fun run(args: Array<String>) {
        main(withDefaultCommand(args, defaultCommand))
    }

    public companion object {
        public fun withDefaultCommand(
            arguments: Array<String>,
            defaultCommand: String? = null,
        ): Array<String> =
            if (arguments.isEmpty() && defaultCommand != null) {
                arrayOf(defaultCommand)
            } else {
                arguments
            }
    }
}
