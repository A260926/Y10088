package lab.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class Interleaving(val index: Int, val events: List<JsonObject>)

/**
 * 从给定事件集合生成有界交错：
 * 每个“参与方”的事件保持内部顺序，各方合并做确定性枚举；
 * 超过上限后停止（默认上限保证小规模集合可穷举）。
 */
object Interleavings {

    fun generate(parties: List<List<JsonObject>>, limit: Int = 256): List<Interleaving> {
        require(parties.isNotEmpty()) { "at least one party required" }
        require(limit > 0) { "limit must be positive" }
        val results = mutableListOf<Interleaving>()

        fun merge(cursors: IntArray, acc: MutableList<JsonObject>) {
            if (results.size >= limit) return
            var done = true
            for (p in parties.indices) {
                if (cursors[p] < parties[p].size) done = false
            }
            if (done) {
                results += Interleaving(results.size, acc.toList())
                return
            }
            // 固定参与方推进顺序，保证结果确定性与可复现。
            for (p in parties.indices) {
                if (cursors[p] < parties[p].size) {
                    acc += parties[p][cursors[p]]
                    cursors[p]++
                    merge(cursors, acc)
                    cursors[p]--
                    acc.removeAt(acc.size - 1)
                    if (results.size >= limit) return
                }
            }
        }

        merge(IntArray(parties.size) { 0 }, mutableListOf())
        return results
    }
}

data class CommutativityCheck(
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

object Commutativity {

    /**
     * 若两个事件声明可交换，则 ab 与 ba 两个顺序必须达到相同状态、相同输出集合。
     * 输出集合比较时允许忽略声明为无序的字段，其余字节严格。
     */
    fun checkPair(
        definition: Definition,
        initial: JsonObject,
        eventA: JsonObject,
        eventB: JsonObject,
    ): CommutativityCheck {
        val nameA = (eventA["name"] as JsonPrimitive).content
        val nameB = (eventB["name"] as JsonPrimitive).content
        val declared = definition.commutative.any { it.matches(nameA, nameB) }

        val ab = Simulator.simulate(definition, initial, listOf(eventA, eventB))
        val ba = Simulator.simulate(definition, initial, listOf(eventB, eventA))

        val stateAB = ab.finalState()
        val stateBA = ba.finalState()
        val sameState = Canonical.canonical(stateAB) == Canonical.canonical(stateBA)

        val outputsAB = ab.steps.mapNotNull { it.output }
            .map { it.stripping(definition.unorderedOutputFields) }
        val outputsBA = ba.steps.mapNotNull { it.output }
            .map { it.stripping(definition.unorderedOutputFields) }
        val sameOutputs = outputSetEqual(outputsAB, outputsBA)

        return CommutativityCheck(
            eventA = nameA,
            eventB = nameB,
            declared = declared,
            passes = declared && sameState && sameOutputs,
            sameState = sameState,
            sameOutputs = sameOutputs,
            stateAB = stateAB,
            stateBA = stateBA,
            outputsAB = outputsAB,
            outputsBA = outputsBA,
        )
    }

    private fun outputSetEqual(a: List<JsonObject>, b: List<JsonObject>): Boolean {
        if (a.size != b.size) return false
        val pool = a.map { Canonical.canonical(it) }.toMutableList()
        for (output in b) {
            if (!pool.remove(Canonical.canonical(output))) return false
        }
        return pool.isEmpty()
    }

    /** 检查定义中声明的所有可交换事件对，找出“可交换误判”。 */
    fun checkAllDeclared(
        definition: Definition,
        initial: JsonObject,
        payloads: Map<String, JsonObject>,
    ): List<CommutativityCheck> = definition.commutative.map { pair ->
        val eventA = eventOf(pair.a, payloads[pair.a])
        val eventB = eventOf(pair.b, payloads[pair.b])
        checkPair(definition, initial, eventA, eventB)
    }

    private fun eventOf(name: String, payload: JsonObject?): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        if (payload != null) put("payload", payload)
    }
}
