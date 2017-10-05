package net.corda.node.services.statemachine.interceptors

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.StateMachineRunId
import net.corda.core.utilities.contextLogger
import net.corda.node.services.statemachine.*
import net.corda.node.services.statemachine.transitions.FlowContinuation
import net.corda.node.services.statemachine.transitions.TransitionResult
import java.util.concurrent.ConcurrentHashMap

/**
 * This interceptor records a trace of all of the flows' states and transitions. If the flow dirties it dumps the trace
 * transition to the logger.
 */
class DumpHistoryOnErrorInterceptor(val delegate: TransitionExecutor) : TransitionExecutor {
    companion object {
        private val log = contextLogger()
    }

    private val records = ConcurrentHashMap<StateMachineRunId, ArrayList<TransitionDiagnosticRecord>>()

    @Suspendable
    override fun executeTransition(
            fiber: FlowFiber,
            previousState: StateMachineState,
            event: Event,
            transition: TransitionResult,
            actionExecutor: ActionExecutor
    ): Pair<FlowContinuation, StateMachineState> {
        val (continuation, nextState) = delegate.executeTransition(fiber, previousState, event, transition, actionExecutor)
        val transitionRecord = TransitionDiagnosticRecord(fiber.id, previousState, nextState, event, transition, continuation)
        val record = records.compute(fiber.id) { _, record ->
            (record ?: ArrayList()).apply { add(transitionRecord) }
        }

        if (nextState.checkpoint.errorState is ErrorState.Errored) {
            log.warn("Flow ${fiber.id} dirtied, dumping all transitions:\n${record!!.joinToString("\n")}")
        }

        if (transition.newState.isRemoved) {
            records.remove(fiber.id)
        }

        return Pair(continuation, nextState)
    }

}