package lab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import lab.engine.Commutativity
import lab.engine.DefinitionParser
import lab.engine.DivergenceType
import lab.engine.ResponseComparator
import lab.engine.Simulator
import lab.engine.StepStatus
import lab.engine.stripping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineContractTest {

    private val def = DefinitionParser.parse(TestFixtures.seedDefinition)

    @Test
    fun `illegal event is rejected by policy and leaves state unchanged`() {
        val sim = Simulator.simulate(
            def,
            def.initial,
            listOf(TestFixtures.event("explode")),
        )
        val step = sim.steps.single()
        assertEquals(StepStatus.ILLEGAL_REJECTED.name, step.status)
        assertEquals(def.initial, step.after)
        val cmp = ResponseComparator.compare(
            Simulator.simulate(def, def.initial, listOf(TestFixtures.event("explode"))),
            listOf(TestFixtures.responseOutput("boom")),
        )
        assertFalse(cmp.matches)
        assertEquals(DivergenceType.MISMATCH, cmp.divergence!!.type)
    }

    @Test
    fun `guard boundary rejects retry once attempt reaches maxAttempts`() {
        // maxAttempts = 5：第 6 次 retry 必须在边界被守卫拒绝。
        val events = (0 until 6).map { TestFixtures.event("retry") }
        val sim = Simulator.simulate(def, def.initial, events)
        val applied = sim.steps.take(5)
        assertTrue(applied.all { it.status == StepStatus.APPLIED.name })
        val boundary = sim.steps[5] // 第 6 次 retry（seq=5）越过边界
        assertEquals(StepStatus.GUARD_REJECTED.name, boundary.status)
        assertFalse(boundary.guardPassed)
        assertEquals(5, (boundary.before["attempt"] as JsonPrimitive).content.toInt())
        assertEquals(5, (boundary.after["attempt"] as JsonPrimitive).content.toInt())
        // 第 i 次 retry 的输出编号是执行后 attempt：1..5；第 6 次应被拒绝。
        val responses = events.take(5).mapIndexed { i, _ -> TestFixtures.retryOutput(i + 1) } +
            TestFixtures.retryOutput(6)
        val cmp = ResponseComparator.compare(Simulator.simulate(def, def.initial, events), responses)
        assertFalse(cmp.matches)
        assertEquals(DivergenceType.MISMATCH, cmp.divergence!!.type)
        assertEquals(5, cmp.divergence.stepIndex)
    }

    @Test
    fun `commutativity claim that is wrong is detected`() {
        // 声明 retry 与 heartbeat 可交换；在 attempt 已为 4 的状态下，
        // 若把 heartbeat 实现成也推进 attempt，则两顺序终态不同 -> 误判被抓出。
        val body = buildJsonObject {
            put("title", JsonPrimitive("bad-commute"))
            put("initial", buildJsonObject {
                put("attempt", JsonPrimitive(4))
                put("maxAttempts", JsonPrimitive(5))
            })
            put("illegal", JsonPrimitive("reject"))
            put("commutative", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("a", JsonPrimitive("retry"))
                put("b", JsonPrimitive("tick"))
            })))
            put("events", buildJsonObject {
                put("retry", buildJsonObject {
                    put("guard", buildJsonObject {
                        put("expr", buildJsonObject {
                            put("op", JsonPrimitive("lt"))
                            put("args", kotlinx.serialization.json.JsonArray(listOf(
                                buildJsonObject { put("at", JsonPrimitive("attempt")) },
                                buildJsonObject { put("at", JsonPrimitive("maxAttempts")) },
                            )))
                        })
                    })
                    put("effects", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                        put("set", buildJsonObject {
                            put("path", JsonPrimitive("attempt"))
                            put("value", buildJsonObject {
                                put("expr", buildJsonObject {
                                    put("op", JsonPrimitive("add"))
                                    put("args", kotlinx.serialization.json.JsonArray(listOf(
                                        buildJsonObject { put("at", JsonPrimitive("attempt")) },
                                        buildJsonObject { put("lit", JsonPrimitive(1)) },
                                    )))
                                })
                            })
                        })
                    })))
                    put("output", buildJsonObject { put("type", JsonPrimitive("r")) })
                })
                put("tick", buildJsonObject {
                    // tick 无守卫：retry 先执行会抵达边界，于是两个顺序终态不同
                    put("effects", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                        put("set", buildJsonObject {
                            put("path", JsonPrimitive("attempt"))
                            put("value", buildJsonObject {
                                put("expr", buildJsonObject {
                                    put("op", JsonPrimitive("add"))
                                    put("args", kotlinx.serialization.json.JsonArray(listOf(
                                        buildJsonObject { put("at", JsonPrimitive("attempt")) },
                                        buildJsonObject { put("lit", JsonPrimitive(1)) },
                                    )))
                                })
                            })
                        })
                    })))
                    put("output", buildJsonObject { put("type", JsonPrimitive("t")) })
                })
            })
        }
        val bad = DefinitionParser.parse(body)
        val check = Commutativity.checkPair(
            bad,
            bad.initial,
            TestFixtures.event("retry"),
            TestFixtures.event("tick"),
        )
        assertTrue(check.declared)
        assertFalse(check.passes)
        assertFalse(check.sameState)

        // 种子定义的 retry/heartbeat 确实可交换：状态相同，输出集合相同。
        val ok = Commutativity.checkPair(
            def,
            def.initial,
            TestFixtures.event("retry"),
            TestFixtures.event("heartbeat"),
        )
        assertTrue(ok.passes)
        assertTrue(ok.sameState)
        assertTrue(ok.sameOutputs)
    }

    @Test
    fun `timeout missing and extra responses are distinct outcomes`() {
        val events = listOf(
            TestFixtures.event("retry"),
            TestFixtures.event("retry"),
            TestFixtures.event("retry"),
        )
        fun sim() = Simulator.simulate(def, def.initial, events)

        val timeout = ResponseComparator.compare(sim(), listOf(TestFixtures.timeout()))
        assertEquals(DivergenceType.TIMEOUT, timeout.divergence!!.type)
        assertEquals(0, timeout.divergence.stepIndex)

        val missing = ResponseComparator.compare(sim(), listOf(
            TestFixtures.retryOutput(1),
            TestFixtures.retryOutput(2),
        ))
        assertEquals(DivergenceType.MISSING_RESPONSE, missing.divergence!!.type)
        assertEquals(2, missing.divergence.stepIndex)

        val extra = ResponseComparator.compare(sim(), listOf(
            TestFixtures.retryOutput(1),
            TestFixtures.retryOutput(2),
            TestFixtures.retryOutput(3),
            TestFixtures.retryOutput(4),
        ))
        assertEquals(DivergenceType.EXTRA_RESPONSE, extra.divergence!!.type)
        assertEquals(3, extra.divergence.stepIndex)
    }

    @Test
    fun `unordered output fields are ignored but remaining bytes stay strict`() {
        // outputsAB/BA 中输出集合比较忽略 traceId，但 type 字节差异必须仍然被发现。
        val check = Commutativity.checkPair(
            def,
            def.initial,
            TestFixtures.event("heartbeat"),
            TestFixtures.event("retry"),
        )
        assertTrue(check.sameOutputs)
        // 直接严格比较：traceId 不同 -> 规范字节不同
        val a = buildJsonObject { put("type", JsonPrimitive("alive")); put("traceId", JsonPrimitive("x")) }
        val b = buildJsonObject { put("type", JsonPrimitive("alive")); put("traceId", JsonPrimitive("y")) }
        assertEquals(
            lab.engine.Canonical.canonical(a) != lab.engine.Canonical.canonical(b),
            true,
        )
        // 忽略声明字段后相等
        assertEquals(
            lab.engine.Canonical.canonical(a.stripping(setOf("traceId"))),
            lab.engine.Canonical.canonical(b.stripping(setOf("traceId"))),
        )
    }
}
