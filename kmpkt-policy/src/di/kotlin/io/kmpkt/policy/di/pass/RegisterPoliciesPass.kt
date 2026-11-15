package io.kmpkt.policy.di.pass

import io.kmpkt.aggregate.event.DomainEvent
import io.kmpkt.di.compiler.Argument
import io.kmpkt.di.compiler.BuilderPass
import io.kmpkt.di.compiler.ConstValue
import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.ServiceDefinition
import io.kmpkt.di.compiler.ServiceId
import io.kmpkt.di.compiler.ServiceReference
import io.kmpkt.di.compiler.TaggedService
import io.kmpkt.di.compiler.TypeArgument
import io.kmpkt.di.compiler.TypeRef
import io.kmpkt.di.compiler.TypedArgument
import io.kmpkt.di.compiler.typeRef
import io.kmpkt.policy.Policy
import io.kmpkt.policy.PolicyExecutor
import io.kmpkt.policy.di.PolicyTag
import kotlin.reflect.KClass

public class RegisterPoliciesPass : BuilderPass {
    override fun process(container: ContainerBuilder) {
        val policies = container.tagged<PolicyTag>()
        val entries =
            policies
                .mapNotNull { tagged ->
                    eventOf(container, tagged.definition)?.let { event -> event to tagged }
                }.groupBy({ it.first }, { it.second })
                .map { (event, matching) -> entry(event, matching) }

        container.update(ServiceId.of<PolicyExecutor>()) {
            it.copy(typedArguments = it.typedArguments + TypedArgument(INITIAL, Argument.Dictionary(entries)))
        }
    }

    private fun entry(
        event: TypeRef,
        policies: List<TaggedService<PolicyTag>>,
    ): Pair<Argument, Argument> =
        Argument.Literal(ConstValue.ClassLiteral(event)) to
            Argument.Sequence(policies.map { Argument.Service(ServiceReference.ById(it.definition.id)) })

    private fun eventOf(
        container: ContainerBuilder,
        policy: ServiceDefinition,
    ): TypeRef? {
        val event = container.types.typeArguments(policy.type, of = typeRef<Policy<*>>()).firstOrNull()

        if (event !is TypeArgument.Of) {
            container.diagnostics.error(
                "Cannot tell which event ${policy.describe()} reacts to: its type ${policy.type} does not say what the E " +
                    "of Policy<E> is.",
            )
        }

        return (event as? TypeArgument.Of)?.type
    }

    private companion object {
        val INITIAL = typeRef<Map<KClass<out DomainEvent<*>>, List<Policy<*>>>>()
    }
}
