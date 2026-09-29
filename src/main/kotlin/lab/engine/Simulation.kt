package lab.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class SimStep(
    val seq: Int,
    val before: JsonObject,
    val event: JsonObject,
    val status: String,
    val guardPassed: Boolean,
    val guardDetail: JsonElement,
    val output: JsonObject?,
    val after: JsonObject,
)

data class Simulation(
    val initial: JsonObject,
    val steps: List<SimStep>,
) {
    fun finalState(): JsonObject = steps.lastOrNull()?.after ?: initial
}

object Simulator {
    fun simulate(definition: Definition, initial: JsonObject, events: List<JsonObject>): Simulation {
        val machine = StateMachine(definition)
        var state = initial
        val steps = events.mapIndexed { i, event ->
            val before = state
            val outcome = machine.apply(before, event)
            state = outcome.after
            SimStep(
                seq = i,
                before = before,
                event = event,
                status = outcome.status,
                guardPassed = outcome.guard.passed,
                guardDetail = outcome.guard.detail,
                output = outcome.output,
                after = outcome.after,
            )
        }
        return Simulation(initial, steps)
    }
}

/** 外部实现上传的单条响应。 */
sealed interface ExternalResponse {
    data class Output(val output: JsonObject) : ExternalResponse
    data class Terminal(val kind: String, val status: String?) : ExternalResponse
    data object Timeout : ExternalResponse
}

data class ParsedResponse(val response: ExternalResponse, val raw: JsonObject)

object ResponseCodec {
    fun parseOne(raw: JsonObject): ParsedResponse {
        val kind = (raw["kind"] as? JsonPrimitive)?.content
            ?: throw DefinitionFormatException("response.kind required")
        val response = when (kind) {
            "output" -> ExternalResponse.Output(
                raw["output"] as? JsonObject
                    ?: throw DefinitionFormatException("output response needs output object"),
            )
            "rejected", "accepted" -> ExternalResponse.Terminal(
                kind,
                (raw["status"] as? JsonPrimitive)?.content,
            )
            "timeout" -> ExternalResponse.Timeout
            else -> throw DefinitionFormatException("unknown response kind: $kind")
        }
        return ParsedResponse(response, raw)
    }
}

enum class DivergenceType { MISMATCH, TIMEOUT, MISSING_RESPONSE, EXTRA_RESPONSE }

data class Divergence(
    val stepIndex: Int,
    val type: DivergenceType,
    val expected: JsonElement?,
    val actual: JsonElement?,
    val message: String,
)

data class ComparisonResult(
    val matches: Boolean,
    val divergence: Divergence?,
)

object ResponseComparator {

    fun expectedResponse(step: SimStep): JsonObject = buildJsonObject {
        when (step.status) {
            StepStatus.APPLIED.name -> {
                put("kind", JsonPrimitive("output"))
                put("output", step.output!!)
            }
            StepStatus.ILLEGAL_REJECTED.name, StepStatus.GUARD_REJECTED.name -> {
                put("kind", JsonPrimitive("rejected"))
                put("status", JsonPrimitive(step.status))
            }
            else -> {
                put("kind", JsonPrimitive("accepted"))
                put("status", JsonPrimitive(step.status))
            }
        }
    }

    /** 外部响应与声明逐步比较；超时、缺响应、多响应是彼此独立的结论。 */
    fun compare(simulation: Simulation, rawResponses: List<JsonObject>): ComparisonResult {
        simulation.steps.forEachIndexed { i, step ->
            if (i >= rawResponses.size) {
                return div(
                    i,
                    DivergenceType.MISSING_RESPONSE,
                    expectedResponse(step),
                    null,
                    "external implementation sent no response for step $i",
                )
            }
            val parsed = ResponseCodec.parseOne(rawResponses[i])
            when (val actual = parsed.response) {
                is ExternalResponse.Timeout -> return div(
                    i,
                    DivergenceType.TIMEOUT,
                    expectedResponse(step),
                    parsed.raw,
                    "external implementation timed out at step $i",
                )
                is ExternalResponse.Output -> {
                    if (step.status != StepStatus.APPLIED.name) {
                        return div(
                            i,
                            DivergenceType.MISMATCH,
                            expectedResponse(step),
                            parsed.raw,
                            "step $i produced no output (${step.status}) but external returned an output",
                        )
                    }
                    if (Canonical.canonical(step.output!!) != Canonical.canonical(actual.output)) {
                        return div(
                            i,
                            DivergenceType.MISMATCH,
                            expectedResponse(step),
                            parsed.raw,
                            "step $i output bytes differ from the declared contract",
                        )
                    }
                }
                is ExternalResponse.Terminal -> {
                    val expected = expectedResponse(step)
                    val sameKind = (expected["kind"] as JsonPrimitive).content == actual.kind
                    val sameStatus = actual.status == null ||
                        (expected["status"] as? JsonPrimitive)?.content == actual.status
                    if (!sameKind || !sameStatus) {
                        return div(
                            i,
                            DivergenceType.MISMATCH,
                            expected,
                            parsed.raw,
                            "step $i terminal response differs: expected ${step.status}",
                        )
                    }
                }
            }
        }
        if (rawResponses.size > simulation.steps.size) {
            val i = simulation.steps.size
            return div(
                i,
                DivergenceType.EXTRA_RESPONSE,
                null,
                rawResponses[i],
                "external implementation sent an extra response after the trace ended",
            )
        }
        return ComparisonResult(true, null)
    }

    private fun div(
        index: Int,
        type: DivergenceType,
        expected: JsonElement?,
        actual: JsonElement?,
        message: String,
    ) = ComparisonResult(false, Divergence(index, type, expected, actual, message))
}

data class ReductionAttempt(
    val attempt: Int,
    val removedCandidate: Int,
    val keptIndices: List<Int>,
    val verdict: String,
    val divergent: Boolean,
    val divergenceType: String?,
    val divergenceStep: Int?,
)

data class ReductionResult(
    val originalSize: Int,
    val reducedIndices: List<Int>,
    val reducedSimulation: Simulation,
    val reducedResponses: List<JsonObject>,
    val divergence: Divergence,
    val attempts: List<ReductionAttempt>,
)

/**
 * 确定性反例缩减：反复尝试删除单个事件（原始相对顺序不变），
 * 只要缩短后的轨迹仍触发分歧就提交删除，直到一轮内没有可删事件。
 */
object Reducer {

    fun reduce(
        definition: Definition,
        initial: JsonObject,
        events: List<JsonObject>,
        responses: List<JsonObject>,
    ): ReductionResult {
        val initialComparison = ResponseComparator.compare(
            Simulator.simulate(definition, initial, events), responses,
        )
        require(!initialComparison.matches) { "can only reduce a divergent trace" }

        var kept = events.indices.toList()
        val attempts = mutableListOf<ReductionAttempt>()
        var attemptNo = 0
        var changed = true
        while (changed) {
            changed = false
            for (candidate in kept.toList()) {
                attemptNo++
                val trial = kept - candidate
                val comparison = compareAt(definition, initial, events, responses, trial)
                val divergent = !comparison.matches
                attempts += ReductionAttempt(
                    attempt = attemptNo,
                    removedCandidate = candidate,
                    keptIndices = trial,
                    verdict = if (divergent) "removed" else "restored",
                    divergent = divergent,
                    divergenceType = comparison.divergence?.type?.name,
                    divergenceStep = comparison.divergence?.stepIndex,
                )
                if (divergent) {
                    kept = trial
                    changed = true
                }
            }
        }

        val finalComparison = compareAt(definition, initial, events, responses, kept)
        val reducedSim = Simulator.simulate(definition, initial, kept.map(events::elementAt))
        return ReductionResult(
            originalSize = events.size,
            reducedIndices = kept,
            reducedSimulation = reducedSim,
            reducedResponses = kept.map(responses::elementAt),
            divergence = finalComparison.divergence!!,
            attempts = attempts,
        )
    }

    private fun compareAt(
        definition: Definition,
        initial: JsonObject,
        events: List<JsonObject>,
        responses: List<JsonObject>,
        indices: List<Int>,
    ): ComparisonResult {
        val sim = Simulator.simulate(definition, initial, indices.map(events::elementAt))
        return ResponseComparator.compare(sim, indices.map(responses::elementAt))
    }
}
