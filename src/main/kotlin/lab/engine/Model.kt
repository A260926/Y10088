package lab.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 用户用 JSON 声明的状态机。
 *
 * 顶层形态示例：
 * {
 *   "title": "...",
 *   "initial": {...},
 *   "illegal": "reject" | "accept" | "skip",
 *   "unorderedOutputFields": ["traceId"],
 *   "commutative": [{"a":"retry","b":"heartbeat"}],
 *   "events": {
 *     "retry": {
 *       "guard": {...},
 *       "effects": [{"set":{"path":"attempt","value":{"expr":...}}}, ...],
 *       "output": {"note":{"lit":"retry-accepted"}},
 *       "onGuardFail": "reject"
 *     }
 *   }
 * }
 */
data class Definition(
    val title: String,
    val initial: JsonObject,
    val illegal: IllegalPolicy,
    val events: Map<String, EventSpec>,
    val unorderedOutputFields: Set<String>,
    val commutative: Set<UnorderedPair>,
)

enum class IllegalPolicy { REJECT, ACCEPT, SKIP }

data class UnorderedPair(val a: String, val b: String) {
    fun matches(x: String, y: String): Boolean =
        (a == x && b == y) || (a == y && b == x)
}

data class EventSpec(
    val guard: Expr?,
    val effects: List<Effect>,
    val output: JsonObject,
    val onGuardFail: IllegalPolicy,
)

sealed interface Effect {
    data class Set(val path: String, val value: Expr) : Effect
    data class Remove(val path: String) : Effect
}

/** 结构化表达式，避免发明需要转义的迷你字符串语言。 */
sealed interface Expr {
    data class Lit(val value: JsonElement) : Expr
    data class At(val path: String, val source: Source = Source.STATE) : Expr
    data class Op(val op: String, val args: List<Expr>) : Expr
}

enum class Source { STATE, EVENT }

data class GuardDecision(
    val passed: Boolean,
    val detail: JsonElement,
)

data class StepOutcome(
    val accepted: Boolean,
    val status: String,
    val guard: GuardDecision,
    val output: JsonObject?,
    val after: JsonObject,
)

enum class StepStatus {
    /** 守卫通过，动作已执行。 */
    APPLIED,

    /** 非法事件（未声明），按策略拒绝：状态不变、无输出。 */
    ILLEGAL_REJECTED,

    /** 守卫不满足，按策略拒绝。 */
    GUARD_REJECTED,

    /** 非法事件按 accept 处理：状态不变但承认该事件。 */
    ILLEGAL_ACCEPTED,

    /** 守卫失败按 accept 处理。 */
    GUARD_ACCEPTED,

    /** 按 skip 策略忽略（非法或守卫失败）。 */
    SKIPPED;

    val actionRan: Boolean get() = this == APPLIED
}
