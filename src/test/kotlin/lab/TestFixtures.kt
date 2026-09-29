package lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import lab.engine.seed.SeedData

object TestFixtures {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun event(name: String, payload: JsonObject? = null): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        if (payload != null) put("payload", payload)
    }

    fun responseOutput(type: String, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("output"))
        put("output", buildJsonObject {
            put("type", JsonPrimitive(type))
            extra.forEach { (k, v) -> put(k, v) }
        })
    }

    fun retryOutput(attempt: Int): JsonObject =
        responseOutput("retry-accepted", mapOf("attempt" to JsonPrimitive(attempt)))

    fun rejected(status: String): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("rejected"))
        put("status", JsonPrimitive(status))
    }

    fun timeout(): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("timeout"))
    }

    val seedDefinition get() = SeedData.definitionBody
}
