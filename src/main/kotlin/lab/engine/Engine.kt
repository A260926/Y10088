package lab.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

class DefinitionFormatException(message: String) : IllegalArgumentException(message)

object DefinitionParser {

    fun parse(raw: JsonObject): Definition {
        val title = (raw["title"] as? JsonPrimitive)?.contentOrEmpty() ?: "untitled"
        val initial = raw["initial"] as? JsonObject
            ?: throw DefinitionFormatException("definition.initial must be a JSON object")
        val illegal = parsePolicy(raw["illegal"], IllegalPolicy.REJECT)
        val eventsRaw = raw["events"] as? JsonObject
            ?: throw DefinitionFormatException("definition.events must be a JSON object")
        val events = eventsRaw.mapValues { (name, specRaw) ->
            val spec = specRaw as? JsonObject
                ?: throw DefinitionFormatException("events.$name must be an object")
            EventSpec(
                guard = spec["guard"]?.let(::parseExpr),
                effects = (spec["effects"] as? JsonArray)?.map(::parseEffect) ?: emptyList(),
                output = spec["output"] as? JsonObject ?: JsonObject(emptyMap()),
                onGuardFail = parsePolicy(spec["onGuardFail"], illegal),
            )
        }
        val unordered = (raw["unorderedOutputFields"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.toSet()
            ?: emptySet()
        val commutative = (raw["commutative"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val a = (obj["a"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val b = (obj["b"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            UnorderedPair(a, b)
        }.toSet()
        return Definition(title, initial, illegal, events, unordered, commutative)
    }

    private fun parsePolicy(e: JsonElement?, default: IllegalPolicy): IllegalPolicy =
        when ((e as? JsonPrimitive)?.content ?: return default) {
            "reject" -> IllegalPolicy.REJECT
            "accept" -> IllegalPolicy.ACCEPT
            "skip" -> IllegalPolicy.SKIP
            else -> throw DefinitionFormatException("unknown illegal policy: $e")
        }

    private fun parseEffect(e: JsonElement): Effect {
        val obj = e as? JsonObject
            ?: throw DefinitionFormatException("effect must be an object")
        obj["set"]?.let { setRaw ->
            val set = setRaw as? JsonObject
                ?: throw DefinitionFormatException("effect.set must be an object")
            val path = (set["path"] as? JsonPrimitive)?.content
                ?: throw DefinitionFormatException("effect.set.path required")
            val value = set["value"]?.let(::parseExpr)
                ?: throw DefinitionFormatException("effect.set.value required")
            return Effect.Set(path, value)
        }
        obj["remove"]?.let { rmRaw ->
            val rm = rmRaw as? JsonObject
                ?: throw DefinitionFormatException("effect.remove must be an object")
            val path = (rm["path"] as? JsonPrimitive)?.content
                ?: throw DefinitionFormatException("effect.remove.path required")
            return Effect.Remove(path)
        }
        throw DefinitionFormatException("effect needs set or remove")
    }

    fun parseExpr(e: JsonElement): Expr {
        if (e is JsonObject) {
            e["lit"]?.let { return Expr.Lit(it) }
            e["at"]?.let { at ->
                val path = (at as? JsonPrimitive)?.content
                    ?: throw DefinitionFormatException("at must be a string")
                val source = when ((e["source"] as? JsonPrimitive)?.content) {
                    null, "state" -> Source.STATE
                    "event" -> Source.EVENT
                    else -> throw DefinitionFormatException("unknown expression source")
                }
                return Expr.At(path, source)
            }
            e["expr"]?.let { opRaw ->
                val op = opRaw as? JsonObject
                    ?: throw DefinitionFormatException("expr must be an object")
                val name = (op["op"] as? JsonPrimitive)?.content
                    ?: throw DefinitionFormatException("expr.op required")
                val args = (op["args"] as? JsonArray)?.map(::parseExpr) ?: emptyList()
                return Expr.Op(name, args)
            }
            throw DefinitionFormatException("expression must contain lit/at/expr")
        }
        return Expr.Lit(e)
    }
}

class StateMachine(private val definition: Definition) {

    fun initial(): JsonObject = definition.initial

    fun eventSpec(name: String): EventSpec? = definition.events[name]

    /** 手工录入或交错生成后的统一入口；事件携带其负载。 */
    fun apply(before: JsonObject, event: JsonObject): StepOutcome {
        val name = (event["name"] as? JsonPrimitive)?.content
            ?: throw DefinitionFormatException("event.name required")
        val payload = (event["payload"] as? JsonObject) ?: buildJsonObject { }
        val spec = definition.events[name]
        if (spec == null) {
            return handleIllegal(definition.illegal, before, name, "event-not-declared")
        }
        val decision = evaluateGuard(spec.guard, before, payload)
        if (!decision.passed) {
            return when (spec.onGuardFail) {
                IllegalPolicy.REJECT -> StepOutcome(
                    accepted = false,
                    status = StepStatus.GUARD_REJECTED.name,
                    guard = decision,
                    output = null,
                    after = before,
                )
                IllegalPolicy.ACCEPT -> StepOutcome(
                    accepted = true,
                    status = StepStatus.GUARD_ACCEPTED.name,
                    guard = decision,
                    output = null,
                    after = before,
                )
                IllegalPolicy.SKIP -> StepOutcome(
                    accepted = true,
                    status = StepStatus.SKIPPED.name,
                    guard = decision,
                    output = null,
                    after = before,
                )
            }
        }

        var state = before
        for (effect in spec.effects) {
            state = when (effect) {
                is Effect.Set -> Pointer.set(state, effect.path, eval(effect.value, before, payload))
                is Effect.Remove -> Pointer.remove(state, effect.path)
            }
        }
        val output = renderTemplate(spec.output, before, payload)
        return StepOutcome(
            accepted = true,
            status = StepStatus.APPLIED.name,
            guard = decision,
            output = output,
            after = state,
        )
    }

    private fun handleIllegal(
        policy: IllegalPolicy,
        before: JsonObject,
        name: String,
        reason: String,
    ): StepOutcome {
        val guard = GuardDecision(
            passed = false,
            detail = JsonObject(mapOf("reason" to JsonPrimitive(reason), "event" to JsonPrimitive(name))),
        )
        return when (policy) {
            IllegalPolicy.REJECT -> StepOutcome(false, StepStatus.ILLEGAL_REJECTED.name, guard, null, before)
            IllegalPolicy.ACCEPT -> StepOutcome(true, StepStatus.ILLEGAL_ACCEPTED.name, guard, null, before)
            IllegalPolicy.SKIP -> StepOutcome(true, StepStatus.SKIPPED.name, guard, null, before)
        }
    }

    private fun evaluateGuard(guard: Expr?, state: JsonObject, event: JsonObject): GuardDecision {
        if (guard == null) return GuardDecision(true, JsonPrimitive(true))
        val value = eval(guard, state, event)
        val passed = (value as? JsonPrimitive)?.let {
            it.booleanOrNull ?: when (it.content) {
                "true" -> true
                "false", "null" -> false
                else -> it.content.toDoubleOrNull()?.let { d -> d != 0.0 } ?: false
            }
        } ?: false
        return GuardDecision(passed, value)
    }

    private fun renderTemplate(template: JsonObject, state: JsonObject, event: JsonObject): JsonObject =
        renderElement(template, state, event) as JsonObject

    private fun renderElement(element: JsonElement, state: JsonObject, event: JsonObject): JsonElement {
        if (element is JsonObject) {
            if (element.size == 1 && element.keys.first() in TEMPLATE_KEYS) {
                return when (val key = element.keys.first()) {
                    "lit" -> element.getValue(key)
                    "at" -> Pointer.get(
                        if ((element["source"] as? JsonPrimitive)?.content == "event") event else state,
                        (element.getValue(key) as JsonPrimitive).content,
                    )
                    "expr" -> {
                        val op = element.getValue(key) as JsonObject
                        val args = (op["args"] as? JsonArray).orEmpty().map {
                            renderElement(it, state, event)
                        }
                        evalOp((op["op"] as JsonPrimitive).content, args)
                    }
                    else -> error("unreachable")
                }
            }
            return JsonObject(element.mapValues { renderElement(it.value, state, event) })
        }
        if (element is JsonArray) return JsonArray(element.map { renderElement(it, state, event) })
        return element
    }

    private fun eval(expr: Expr, state: JsonObject, event: JsonObject): JsonElement = when (expr) {
        is Expr.Lit -> expr.value
        is Expr.At -> Pointer.get(if (expr.source == Source.EVENT) event else state, expr.path)
        is Expr.Op -> evalOp(expr.op, expr.args.map { eval(it, state, event) })
    }

    private fun evalOp(op: String, args: List<JsonElement>): JsonElement {
        fun nums(): List<Double> = args.map { it.asNumber() }
        val ns by lazy { nums() }
        fun integral(): Boolean = ns.all { it.isFinite() && it == Math.floor(it) }
        return when (op) {
            "and" -> JsonPrimitive(args.all { (it as? JsonPrimitive)?.booleanOrNull == true })
            "or" -> JsonPrimitive(args.any { (it as? JsonPrimitive)?.booleanOrNull == true })
            "not" -> JsonPrimitive((args[0] as? JsonPrimitive)?.booleanOrNull != true)
            "eq" -> JsonPrimitive(jsonEqual(args[0], args[1]))
            "ne" -> JsonPrimitive(!jsonEqual(args[0], args[1]))
            "lt" -> JsonPrimitive(ns[0] < ns[1])
            "le" -> JsonPrimitive(ns[0] <= ns[1])
            "gt" -> JsonPrimitive(ns[0] > ns[1])
            "ge" -> JsonPrimitive(ns[0] >= ns[1])
            "add" -> if (integral())
                JsonPrimitive(ns.drop(1).fold(ns[0].toLong()) { acc, e -> acc + e.toLong() })
            else JsonPrimitive(ns.drop(1).fold(ns[0]) { acc, e -> acc + e })
            "sub" -> if (integral()) JsonPrimitive(ns[0].toLong() - ns[1].toLong())
            else JsonPrimitive(ns[0] - ns[1])
            "mul" -> if (integral())
                JsonPrimitive(ns.drop(1).fold(ns[0].toLong()) { acc, e -> acc * e.toLong() })
            else JsonPrimitive(ns.drop(1).fold(ns[0]) { acc, e -> acc * e })
            "div" -> if (integral() && ns[1] != 0.0 && ns[0].toLong() % ns[1].toLong() == 0L)
                JsonPrimitive(ns[0].toLong() / ns[1].toLong())
            else JsonPrimitive(ns[0] / ns[1])
            "if" ->
                if ((args[0] as? JsonPrimitive)?.booleanOrNull == true) args[1] else args[2]
            else -> throw DefinitionFormatException("unknown op: $op")
        }
    }

    companion object {
        private val TEMPLATE_KEYS = setOf("lit", "at", "expr")
    }
}

private fun JsonElement.asNumber(): Double {
    if (this !is JsonPrimitive || isString) {
        throw DefinitionFormatException("expected number, got $this")
    }
    return content.toDoubleOrNull()
        ?: throw DefinitionFormatException("expected number, got $this")
}

private fun JsonPrimitive.contentOrEmpty(): String = content
