package io.kmpkt.di.compiler.pass

import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ContainerBuilder

/**
 * Reports what is wrong with the definitions that are still there once the unused ones are gone.
 *
 * A class swept up by `discover` that cannot be wired - a value object, a DTO - is no problem until
 * something asks for it.
 */
internal class ReportDefinitionErrors : BuilderPass {
    override fun process(container: ContainerBuilder) {
        container.definitions.values
            .flatMap { it.errors }
            .forEach(container.diagnostics::error)
    }
}
