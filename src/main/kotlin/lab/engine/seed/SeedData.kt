package lab.engine.seed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import lab.engine.DefinitionParser
import lab.engine.RunExecutor
import lab.store.CreateRunRequest
import lab.store.DefinitionRepository
import lab.store.RunRepository
import java.util.UUID

/**
 * 内置示例：带重试、取消和确认的订单状态机。
 * 守卫在 attempt >= maxAttempts 时拒绝继续重试。
 */
object SeedData {
    const val NAME = "order-retries"
    const val SEED_KEY = "seed-six-step"

    val definitionBody: JsonObject = buildJsonObject {
        put("title", JsonPrimitive("订单重试/取消/确认"))
        put("initial", buildJsonObject {
            put("status", JsonPrimitive("pending"))
            put("attempt", JsonPrimitive(0))
            put("maxAttempts", JsonPrimitive(5))
        })
        put("illegal", JsonPrimitive("reject"))
        put("unorderedOutputFields", JsonArray(listOf(JsonPrimitive("traceId"))))
        put("commutative", JsonArray(listOf(buildJsonObject {
            put("a", JsonPrimitive("retry"))
            put("b", JsonPrimitive("heartbeat"))
        })))
        put("events", buildJsonObject {
            put("retry", buildJsonObject {
                put("guard", buildJsonObject {
                    put("expr", buildJsonObject {
                        put("op", JsonPrimitive("lt"))
                        put("args", JsonArray(listOf(
                            buildJsonObject { put("at", JsonPrimitive("attempt")) },
                            buildJsonObject { put("at", JsonPrimitive("maxAttempts")) },
                        )))
                    })
                })
                put("effects", JsonArray(listOf(
                    buildJsonObject {
                        put("set", buildJsonObject {
                            put("path", JsonPrimitive("attempt"))
                            put("value", buildJsonObject {
                                put("expr", buildJsonObject {
                                    put("op", JsonPrimitive("add"))
                                    put("args", JsonArray(listOf(
                                        buildJsonObject { put("at", JsonPrimitive("attempt")) },
                                        buildJsonObject { put("lit", JsonPrimitive(1)) },
                                    )))
                                })
                            })
                        })
                    },
                )))
                put("output", buildJsonObject {
                    put("type", JsonPrimitive("retry-accepted"))
                    // 本次重试编号取执行后状态：当前 attempt + 1
                    put("attempt", buildJsonObject {
                        put("expr", buildJsonObject {
                            put("op", JsonPrimitive("add"))
                            put("args", JsonArray(listOf(
                                buildJsonObject { put("at", JsonPrimitive("attempt")) },
                                buildJsonObject { put("lit", JsonPrimitive(1)) },
                            )))
                        })
                    })
                })
            })
            put("cancel", buildJsonObject {
                put("guard", buildJsonObject {
                    put("expr", buildJsonObject {
                        put("op", JsonPrimitive("eq"))
                        put("args", JsonArray(listOf(
                            buildJsonObject { put("at", JsonPrimitive("status")) },
                            buildJsonObject { put("lit", JsonPrimitive("pending")) },
                        )))
                    })
                })
                put("effects", JsonArray(listOf(
                    buildJsonObject {
                        put("set", buildJsonObject {
                            put("path", JsonPrimitive("status"))
                            put("value", buildJsonObject { put("lit", JsonPrimitive("cancelled")) })
                        })
                    },
                )))
                put("output", buildJsonObject {
                    put("type", JsonPrimitive("cancelled"))
                })
            })
            put("confirm", buildJsonObject {
                put("guard", buildJsonObject {
                    put("expr", buildJsonObject {
                        put("op", JsonPrimitive("eq"))
                        put("args", JsonArray(listOf(
                            buildJsonObject { put("at", JsonPrimitive("status")) },
                            buildJsonObject { put("lit", JsonPrimitive("pending")) },
                        )))
                    })
                })
                put("effects", JsonArray(listOf(
                    buildJsonObject {
                        put("set", buildJsonObject {
                            put("path", JsonPrimitive("status"))
                            put("value", buildJsonObject { put("lit", JsonPrimitive("confirmed")) })
                        })
                    },
                )))
                put("output", buildJsonObject {
                    put("type", JsonPrimitive("confirmed"))
                })
            })
            put("heartbeat", buildJsonObject {
                put("output", buildJsonObject {
                    put("type", JsonPrimitive("alive"))
                })
            })
        })
    }

    private fun event(name: String): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("payload", buildJsonObject {})
    }

    /**
     * 六步轨迹：retry, retry, heartbeat, retry, cancel, confirm。
     * 前 5 步均正确；外部实现未识别 cancel 后的 cancelled 状态，对 confirm 仍回 output。
     * 反例缩减稳定地得到两步：cancel, confirm（原始索引 4,5）。
     */
    val traceEvents: List<JsonObject> = listOf(
        event("retry"),
        event("retry"),
        event("heartbeat"),
        event("retry"),
        event("cancel"),
        event("confirm"),
    )

    private fun outputResponse(type: String, attempt: Int? = null): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("output"))
        put("output", buildJsonObject {
            put("type", JsonPrimitive(type))
            if (attempt != null) put("attempt", JsonPrimitive(attempt))
        })
    }

    val externalResponses: List<JsonObject> = listOf(
        outputResponse("retry-accepted", 1),
        outputResponse("retry-accepted", 2),
        outputResponse("alive"),
        outputResponse("retry-accepted", 3),
        // cancel 正确返回 cancelled
        outputResponse("cancelled"),
        // 但外部实现没有意识到状态已取消：对随后的 confirm 仍回 output，
        // 声明下该事件必须被守卫拒绝。只有 cancel+confirm 成对才复现，最小反例为两步。
        outputResponse("confirmed"),
    )

    fun seedIfAbsent(
        definitions: DefinitionRepository,
        runs: RunRepository,
        executor: RunExecutor,
        now: () -> Long,
    ) {
        if (definitions.latestVersion(NAME) == null) {
            val body = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), definitionBody)
            definitions.createDraft(NAME, body, now())
            definitions.publish(NAME, 1, now())
        }
        if (runs.findByIdempotencyKey(SEED_KEY) == null) {
            val json = Json { prettyPrint = true }
            val initial = definitionBody["initial"] as JsonObject
            val request = CreateRunRequest(
                id = UUID.nameUUIDFromBytes("seed-six-step".toByteArray()).toString(),
                name = NAME,
                version = 1,
                idempotencyKey = SEED_KEY,
                initialStateJson = json.encodeToString(JsonObject.serializer(), initial),
                eventsJson = json.encodeToString(JsonArray.serializer(), JsonArray(traceEvents)),
                responsesJson = json.encodeToString(JsonArray.serializer(), JsonArray(externalResponses)),
                createdAt = now(),
            )
            val (run, _) = runs.createOrGet(request)
            val definition = DefinitionParser.parse(definitionBody)
            executor.execute(run, definition)
        }
    }
}
