package io.kmpkt.policy.di

import io.kmpkt.command.di.CommandComponent
import io.kmpkt.di.compiler.Component
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.Tag
import io.kmpkt.di.compiler.literal
import io.kmpkt.messenger.di.BusRef
import io.kmpkt.messenger.di.MessageHandlerTag
import io.kmpkt.messenger.di.MessengerComponent
import io.kmpkt.policy.ExecutePolicyCommandsForEventHandler
import io.kmpkt.policy.Policy
import io.kmpkt.policy.PolicyExecutor
import io.kmpkt.policy.PolicyTransport
import io.kmpkt.policy.di.pass.RegisterPoliciesPass
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

public object PolicyTag : Tag

public class PolicyOptions {
    public var name: String = "policies"
    public var collectGarbageEvery: Duration = 1.hours
    public var commandBus: BusRef? = null
}

public fun ContainerBuilder.policy(block: PolicyOptions.() -> Unit = {}) {
    configure(PolicyComponent::class, block)
}

public class PolicyComponent : Component<PolicyOptions> {
    override val requires: Set<KClass<out Component<*>>> = setOf(CommandComponent::class, MessengerComponent::class)

    override fun defaults(): PolicyOptions = PolicyOptions()

    override fun ContainerBuilder.prepare() {
        autoconfigure<Policy<*>> {
            tag(PolicyTag)
        }
    }

    override fun ContainerBuilder.register(config: PolicyOptions) {
        addPass(RegisterPoliciesPass())

        service<PolicyExecutor>()
        service<PolicyTransport> {
            argument("name", literal(config.name))
            argument("collectGarbageEvery", literal(config.collectGarbageEvery))
        }
        service<ExecutePolicyCommandsForEventHandler> {
            tag(MessageHandlerTag(bus = config.commandBus?.name ?: "command"))
        }
    }
}
