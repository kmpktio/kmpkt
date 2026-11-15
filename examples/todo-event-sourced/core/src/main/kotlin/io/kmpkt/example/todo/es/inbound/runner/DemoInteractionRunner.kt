package io.kmpkt.example.todo.es.inbound.runner

import io.kmpkt.aggregate.value.AggregateId
import io.kmpkt.aggregate.value.AggregateUuid
import io.kmpkt.command.CommandBus
import io.kmpkt.di.ApplicationRunner
import io.kmpkt.example.todo.es.application.projection.TodoRow
import io.kmpkt.example.todo.es.domain.aggregate.TodoItem
import io.kmpkt.example.todo.es.domain.command.AddTodo
import io.kmpkt.example.todo.es.domain.command.CompleteTodo
import io.kmpkt.example.todo.es.domain.query.ListTodos
import io.kmpkt.messenger.bus.MessageBus
import io.kmpkt.messenger.transport.Transport
import io.kmpkt.policy.PolicyTransport
import io.kmpkt.projection.ProjectionTransport
import io.kmpkt.query.QueryBus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Simulates an interactive session: dispatches commands, drains policy/projection queues, and verifies the
 * projected read model.
 */
class DemoInteractionRunner(
    private val commands: CommandBus,
    private val queries: QueryBus,
    private val policyTransport: PolicyTransport,
    private val projectionTransport: ProjectionTransport<TodoRow>,
    private val commandMessageBus: MessageBus,
) : ApplicationRunner {
    override fun run(args: Array<String>) =
        runBlocking {
            val urgentId = AggregateUuid.generate<TodoItem>()
            val normalId = AggregateUuid.generate<TodoItem>()

            dispatchCommands(urgentId, normalId)
            processPoliciesAndProjections()
            verifyReadModel(urgentId, normalId)
        }

    private suspend fun dispatchCommands(
        urgentId: AggregateId<TodoItem>,
        normalId: AggregateId<TodoItem>,
    ) {
        // Titles ending with "!" trigger PinUrgentTodoPolicy once domain events are drained.
        commands.dispatch(AddTodo(id = urgentId, title = "Pay rent!"))
        commands.dispatch(AddTodo(id = normalId, title = "Buy milk"))
        commands.dispatch(CompleteTodo(id = normalId))
    }

    private suspend fun processPoliciesAndProjections() {
        // 1. Drain policy events first so PinUrgentTodoPolicy emits PinTodo for "Pay rent!".
        drain(policyTransport, commandMessageBus)
        // 2. Drain projection events into TodoRow (including the TodoPinned event created above).
        drain(projectionTransport, commandMessageBus)
    }

    private suspend fun verifyReadModel(
        urgentId: AggregateId<TodoItem>,
        normalId: AggregateId<TodoItem>,
    ) {
        val rows = queries.ask(ListTodos)
        check(rows.any { it.id == urgentId && it.pinned && !it.completed }) {
            "Expected urgent todo $urgentId to be pinned and open: $rows"
        }
        check(rows.any { it.id == normalId && it.completed && !it.pinned }) {
            "Expected normal todo $normalId to be completed and unpinned: $rows"
        }
        println("Projected todos: $rows")
    }

    private suspend fun drain(
        transport: Transport,
        bus: MessageBus,
    ) {
        while (true) {
            val batch = transport.receive().toList()
            if (batch.isEmpty()) break
            batch.forEach { envelope ->
                bus.dispatch(envelope)
                transport.ack(envelope)
            }
        }
    }
}
