package lab.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object JsonCodecs {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
}

/** 路径形如 "attempt"、"payment.amount"；空路径表示根。 */
object Pointer {
    fun parse(path: String): List<String> =
        if (path.isBlank()) emptyList() else path.split('.').filter { it.isNotEmpty() }

    fun get(root: JsonElement, path: String): JsonElement {
        var cur: JsonElement = root
        for (key in parse(path)) {
            cur = (cur as? JsonObject)?.get(key) ?: JsonNull
            if (cur is JsonNull) return JsonNull
        }
        return cur
    }

    fun set(root: JsonObject, path: String, value: JsonElement): JsonObject {
        val segs = parse(path)
        require(segs.isNotEmpty()) { "effect path must not be empty" }
        return setSeg(root, segs, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setSeg(obj: JsonObject, segs: List<String>, value: JsonElement): JsonObject {
        val key = segs.first()
        val rest = segs.drop(1)
        val existing = obj[key]
        val next: JsonElement = if (rest.isEmpty()) {
            value
        } else {
            val child = (existing as? JsonObject) ?: JsonObject(emptyMap())
            setSeg(child, rest, value)
        }
        return JsonObject(obj + (key to next))
    }

    fun remove(root: JsonObject, path: String): JsonObject {
        val segs = parse(path)
        require(segs.isNotEmpty()) { "effect path must not be empty" }
        return removeSeg(root, segs)
    }

    private fun removeSeg(obj: JsonObject, segs: List<String>): JsonObject {
        val key = segs.first()
        val rest = segs.drop(1)
        if (rest.isEmpty()) return JsonObject(obj - key)
        val child = obj[key] as? JsonObject ?: return obj
        return JsonObject(obj + (key to removeSeg(child, rest)))
    }
}

/** 严格规范化：键排序，用于“其余字节保持严格”的对比。 */
object Canonical {
    fun canonical(element: JsonElement): String {
        val sb = StringBuilder()
        write(element, sb)
        return sb.toString()
    }

    private fun write(element: JsonElement, sb: StringBuilder) {
        when (element) {
            is JsonObject -> {
                sb.append('{')
                element.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) sb.append(',')
                    write(JsonPrimitive(key), sb)
                    sb.append(':')
                    write(element.getValue(key), sb)
                }
                sb.append('}')
            }
            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { i, child ->
                    if (i > 0) sb.append(',')
                    write(child, sb)
                }
                sb.append(']')
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    writeJsonString(element.content, sb)
                } else {
                    sb.append(element.content)
                }
            }
            JsonNull -> sb.append("null")
        }
    }

    private fun writeJsonString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }
}

/** 比较两个规范化后的 JSON：数字按数值比较（1 与 1.0 相同）。 */
fun jsonEqual(a: JsonElement, b: JsonElement): Boolean {
    if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
        val ai = a.longOrNull ?: a.doubleOrNull
        val bi = b.longOrNull ?: b.doubleOrNull
        if (ai != null && bi != null) return ai.toDouble() == bi.toDouble()
        return a.contentOrNull == b.contentOrNull
    }
    if (a is JsonObject && b is JsonObject) {
        if (a.keys != b.keys) return false
        return a.keys.all { jsonEqual(a.getValue(it), b.getValue(it)) }
    }
    if (a is JsonArray && b is JsonArray) {
        return a.size == b.size && a.zip(b).all { (x, y) -> jsonEqual(x, y) }
    }
    return Canonical.canonical(a) == Canonical.canonical(b)
}

fun JsonObject.stripping(fields: Set<String>): JsonObject =
    if (fields.isEmpty()) this else JsonObject(this - fields)

fun JsonObject.mergeIn(other: JsonObject): JsonObject = JsonObject(this + other)

fun primitiveBool(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.booleanOrNull
