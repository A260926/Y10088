package lab.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import lab.engine.Commutativity
import lab.engine.Definition
import lab.engine.DefinitionParser
import lab.engine.Divergence
import lab.engine.Interleavings
import lab.engine.Reducer
import lab.engine.ResponseComparator
import lab.engine.RunExecutor
import lab.engine.Simulator
import lab.store.CreateRunRequest
import lab.store.DefinitionRepository
import lab.store.DefinitionRow
import lab.store.IdempotencyConflictException
import lab.store.PublishedImmutableException
import lab.store.RunRepository
import lab.store.RunRow
import lab.store.VersionConflictException
import java.util.UUID

class LabException(val statusCode: Int, message: String) : RuntimeException(message)

class LabService(
    private val definitions: DefinitionRepository,
    private val runs: RunRepository,
    private val executor: RunExecutor,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun parseDefinition(body: JsonObject): Definition = DefinitionParser.parse(body)

    fun createDraft(name: String, body: JsonObject, now: Long): DefinitionRow {
        validateDefinition(body)
        if (definitions.latestVersion(name) != null) {
            throw LabException(409, "definition '$name' already exists; create a new version instead")
        }
        return definitions.createDraft(name, json.encodeToString(JsonObject.serializer(), body), now)
    }

    fun createNextVersion(name: String, body: JsonObject, now: Long): DefinitionRow {
        validateDefinition(body)
        if (definitions.latestVersion(name) == null) {
            throw LabException(404, "definition '$name' does not exist yet")
        }
        return definitions.createNextDraft(name, json.encodeToString(JsonObject.serializer(), body), now)
    }

    fun updateDraft(name: String, version: Int, expectedRevision: Int, body: JsonObject, now: Long): DefinitionRow {
        validateDefinition(body)
        try {
            return definitions.updateDraft(
                name, version, expectedRevision,
                json.encodeToString(JsonObject.serializer(), body), now,
            )
        } catch (e: VersionConflictException) {
            throw LabException(409, e.message ?: "draft revision conflict")
        } catch (e: PublishedImmutableException) {
            throw LabException(409, e.message ?: "published versions are immutable")
        }
    }

    fun publish(name: String, version: Int, now: Long): DefinitionRow =
        definitions.publish(name, version, now)

    fun getDefinitionRow(name: String, version: Int?): DefinitionRow {
        val row = when (version) {
            null -> definitions.latestPublished(name) ?: definitions.latestVersion(name)
            else -> definitions.get(name, version)
        } ?: throw LabException(404, "definition $name${version?.let { "@$it" } ?: ""} not found")
        return row
    }

    fun loadDefinition(name: String, version: Int): Definition {
        val row = definitions.get(name, version)
            ?: throw LabException(404, "definition $name@$version not found")
        return json.decodeFromString(JsonObject.serializer(), row.body).let(DefinitionParser::parse)
    }

    fun createRun(req: CreateRunRequestJson, now: Long): RunView {
        val row = getDefinitionRow(req.definition, req.version)
        val defBody = json.decodeFromString(JsonObject.serializer(), row.body)
        validateDefinition(defBody)

        val initial = req.initialState ?: (defBody["initial"] as JsonObject)
        val events = req.events
            ?: throw LabException(400, "events are required")
        val responses = req.externalResponses ?: emptyList()

        val request = CreateRunRequest(
            id = UUID.randomUUID().toString(),
            name = req.definition,
            version = row.version,
            idempotencyKey = req.idempotencyKey,
            initialStateJson = json.encodeToString(JsonObject.serializer(), initial),
            eventsJson = json.encodeToString(JsonArray.serializer(), JsonArray(events)),
            responsesJson = json.encodeToString(JsonArray.serializer(), JsonArray(responses)),
            createdAt = now,
        )
        val (run, created) = try {
            runs.createOrGet(request)
        } catch (e: IdempotencyConflictException) {
            throw LabException(409, e.message ?: "idempotency conflict")
        }
        val definition = DefinitionParser.parse(defBody)
        val executed = executor.execute(run, definition)
        return RunView.of(executed, runs.steps(executed.id), created, this)
    }

    fun getRun(runId: String): RunView {
        val run = runs.get(runId) ?: throw LabException(404, "run $runId not found")
        return RunView.of(run, runs.steps(runId), created = false, this)
    }

    fun getRunByIdempotencyKey(key: String): RunView {
        val run = runs.findByIdempotencyKey(key)
            ?: throw LabException(404, "run with idempotency key $key not found")
        return RunView.of(run, runs.steps(run.id), created = false, this)
    }

    fun compareRun(runId: String): ComparisonView {
        val run = runs.get(runId) ?: throw LabException(404, "run $runId not found")
        val definition = loadDefinition(run.name, run.version)
        val initial = json.decodeFromString(JsonObject.serializer(), run.initialState)
        val events = json.decodeFromString(JsonArray.serializer(), run.eventsJson).map { it as JsonObject }
        val responses = json.decodeFromString(JsonArray.serializer(), run.responsesJson).map { it as JsonObject }
        val sim = Simulator.simulate(definition, initial, events)
        val result = ResponseComparator.compare(sim, responses)
        return ComparisonView(result, run.name, run.version)
    }

    fun reduceRun(runId: String): ReductionView {
        val run = runs.get(runId) ?: throw LabException(404, "run $runId not found")
        val definition = loadDefinition(run.name, run.version)
        val initial = json.decodeFromString(JsonObject.serializer(), run.initialState)
        val events = json.decodeFromString(JsonArray.serializer(), run.eventsJson).map { it as JsonObject }
        val responses = json.decodeFromString(JsonArray.serializer(), run.responsesJson).map { it as JsonObject }
        val result = Reducer.reduce(definition, initial, events, responses)
        return ReductionView(result, events, run.name, run.version)
    }

    fun interleavings(parties: List<List<JsonObject>>, limit: Int): List<lab.engine.Interleaving> =
        Interleavings.generate(parties, limit)

    fun commutativity(req: CommutativityRequestJson): List<lab.engine.CommutativityCheck> {
        val row = getDefinitionRow(req.definition, req.version)
        val definition = DefinitionParser.parse(
            json.decodeFromString(JsonObject.serializer(), row.body),
        )
        val initial = req.initialState ?: definition.initial
        return if (req.pairs.isNullOrEmpty()) {
            val payloads = (req.payloads ?: emptyMap()).mapValues { it.value ?: JsonObject(emptyMap()) }
            Commutativity.checkAllDeclared(definition, initial, payloads)
        } else {
            req.pairs.map { pair ->
                val eventA = pairEvent(pair["a"] ?: throw LabException(400, "pair.a required"), req.payloads)
                val eventB = pairEvent(pair["b"] ?: throw LabException(400, "pair.b required"), req.payloads)
                Commutativity.checkPair(definition, initial, eventA, eventB)
            }
        }
    }

    private fun pairEvent(name: String, payloads: Map<String, JsonObject?>?): JsonObject {
        val payload = payloads?.get(name) ?: JsonObject(emptyMap())
        return JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name), "payload" to payload))
    }

    fun listDefinitions(): List<DefinitionSummaryJson> = definitions.listLatest().map {
        DefinitionSummaryJson(it.name, it.version, it.status, it.revision)
    }

    fun definitionDetail(name: String, version: Int?): DefinitionDetailJson {
        val row = getDefinitionRow(name, version)
        val body = json.decodeFromString(JsonObject.serializer(), row.body)
        return DefinitionDetailJson(
            name = row.name,
            version = row.version,
            status = row.status,
            revision = row.revision,
            body = body,
        )
    }

    private fun validateDefinition(body: JsonObject) {
        try {
            DefinitionParser.parse(body)
        } catch (e: IllegalArgumentException) {
            throw LabException(400, "invalid definition: ${e.message}")
        }
    }
}
