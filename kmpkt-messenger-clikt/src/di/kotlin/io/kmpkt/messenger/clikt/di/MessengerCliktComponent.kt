package io.kmpkt.messenger.clikt.di

import io.kmpkt.bridge.clikt.di.CliktComponent
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.messenger.clikt.ConsumeCommand
import io.kmpkt.messenger.di.MessengerComponent
import kotlin.reflect.KClass

/**
 * Registers messenger CLI commands (`messenger:consume`) for [CliktComponent].
 */
public class MessengerCliktComponent : SimpleComponent {
    override val requires: Set<KClass<out Component<*>>> = setOf(MessengerComponent::class, CliktComponent::class)

    override fun ContainerBuilder.register() {
        service<ConsumeCommand>()
    }
}
