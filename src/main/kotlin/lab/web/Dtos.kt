package lab.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import lab.engine.Divergence
import lab.engine.ReductionResult
import lab.engine.SimStep
import lab.store.RunRow
import lab.store.StepRow

@Serializable
data class DefinitionSummaryJson(
    val name: String,
    val version: Int,
    val status: String,
    val revision: Int,
)

@Serializable
data class DefinitionDetailJson(
    val name: String,
    val version: Int,
    val status: String,
    val revision: Int,
    val body: JsonObject,
)

@Serializable
data class CreateDefinitionRequest(
    val name: String,
    val body: JsonObject,
)

@Serializable
data class CreateVersionRequest(
    val body: JsonObject,
)

@Serializable
data class UpdateDraftRequest(
    val expectedRevision: Int,
    val body: JsonObject,
)

@Serializable
data class CreateRunRequestJson(
    val definition: String,
    val version: Int? = null,
    val idempotencyKey: String? = null,
    val initialState: JsonObject? = null,
    val events: List<JsonObject>? = null,
    val externalResponses: List<JsonObject>? = null,
)

@Serializable
data class StepView(
    val seq: Int,
    val before: JsonObject,
    val event: JsonObject,
    val status: String,
    val guardPassed: Boolean,
    val guardDetail: kotlinx.serialization.json.JsonElement,
    val output: JsonObject? = null,
    val after: JsonObject,
    val definitionVersion: Int,
)

@Serializable
data class RunView(
    val id: String,
    val definition: String,
    val version: Int,
    val idempotencyKey: String?,
    val initialState: JsonObject,
    val events: List<JsonObject>,
    val cursor: Int,
    val completed: Boolean,
    val actionInvocations: Int,
    val created: Boolean,
    val steps: List<StepView>,
) {
    companion object {
        fun of(run: RunRow, rows: List<StepRow>, created: Boolean, service: LabService): RunView {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            return RunView(
                id = run.id,
                definition = run.name,
                version = run.version,
                idempotencyKey = run.idempotencyKey,
                initialState = json.decodeFromString(JsonObject.serializer(), run.initialState),
                events = json.decodeFromString(
                    kotlinx.serialization.json.JsonArray.serializer(),
                    run.eventsJson,
                ).map { it as JsonObject },
                cursor = run.cursor,
                completed = run.completed,
                actionInvocations = run.actionInvocations,
                created = created,
                steps = rows.map { row ->
                    StepView(
                        seq = row.seq,
                        before = json.decodeFromString(JsonObject.serializer(), row.beforeState),
                        event = json.decodeFromString(JsonObject.serializer(), row.eventJson),
                        status = row.status,
                        guardPassed = row.guardPassed,
                        guardDetail = json.decodeFromString(
                            kotlinx.serialization.json.JsonElement.serializer(),
                            row.guardDetail,
                        ),
                        output = row.outputJson?.let {
                            json.decodeFromString(JsonObject.serializer(), it)
                        },
                        after = json.decodeFromString(JsonObject.serializer(), row.afterState),
                        definitionVersion = row.definitionVersion,
                    )
                },
            )
        }
    }
}

@Serializable
data class DivergenceJson(
    val stepIndex: Int,
    val type: String,
    val expected: kotlinx.serialization.json.JsonElement? = null,
    val actual: kotlinx.serialization.json.JsonElement? = null,
    val message: String,
)

@Serializable
data class ComparisonView(
    val matches: Boolean,
    val divergence: DivergenceJson? = null,
    val definition: String,
    val version: Int,
) {
    companion object {
        operator fun invoke(
            result: lab.engine.ComparisonResult,
            name: String,
            version: Int,
        ): ComparisonView = ComparisonView(
            matches = result.matches,
            divergence = result.divergence?.let {
                DivergenceJson(it.stepIndex, it.type.name, it.expected, it.actual, it.message)
            },
            definition = name,
            version = version,
        )
    }
}

@Serializable
data class ReductionAttemptJson(
    val attempt: Int,
    val removedCandidate: Int,
    val keptIndices: List<Int>,
    val verdict: String,
    val divergent: Boolean,
    val divergenceType: String? = null,
    val divergenceStep: Int? = null,
)

@Serializable
data class ReductionView(
    val definition: String,
    val version: Int,
    val originalSize: Int,
    val reducedSize: Int,
    val reducedIndices: List<Int>,
    val divergence: DivergenceJson,
    val reducedSteps: List<StepView>,
    val attempts: List<ReductionAttemptJson>,
) {
    companion object {
        operator fun invoke(
            result: ReductionResult,
            originalEvents: List<JsonObject>,
            name: String,
            version: Int,
        ): ReductionView {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            return ReductionView(
                definition = name,
                version = version,
                originalSize = result.originalSize,
                reducedSize = result.reducedIndices.size,
                reducedIndices = result.reducedIndices,
                divergence = DivergenceJson(
                    result.divergence.stepIndex,
                    result.divergence.type.name,
                    result.divergence.expected,
                    result.divergence.actual,
                    result.divergence.message,
                ),
                reducedSteps = result.reducedSimulation.steps.map { it.toView(version) },
                attempts = result.attempts.map {
                    ReductionAttemptJson(
                        it.attempt,
                        it.removedCandidate,
                        it.keptIndices,
                        it.verdict,
                        it.divergent,
                        it.divergenceType,
                        it.divergenceStep,
                    )
                },
            )
        }
    }
}

internal fun SimStep.toView(version: Int): StepView = StepView(
    seq = seq,
    before = before,
    event = event,
    status = status,
    guardPassed = guardPassed,
    guardDetail = guardDetail,
    output = output,
    after = after,
    definitionVersion = version,
)

@Serializable
data class InterleavingRequest(
    val parties: List<List<JsonObject>>,
    val limit: Int = 256,
)

@Serializable
data class InterleavingView(
    val index: Int,
    val events: List<JsonObject>,
)

@Serializable
data class CommutativityRequestJson(
    val definition: String,
    val version: Int? = null,
    val initialState: JsonObject? = null,
    val pairs: List<Map<String, String>>? = null,
    val payloads: Map<String, JsonObject>? = null,
)

@Serializable
data class CommutativityView(
    val eventA: String,
    val eventB: String,
    val declared: Boolean,
    val passes: Boolean,
    val sameState: Boolean,
    val sameOutputs: Boolean,
    val stateAB: JsonObject,
    val stateBA: JsonObject,
    val outputsAB: List<JsonObject>,
    val outputsBA: List<JsonObject>,
)

@Serializable
data class ErrorJson(val error: String, val status: Int)
