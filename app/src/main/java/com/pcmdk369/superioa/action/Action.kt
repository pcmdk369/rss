package com.pcmdk369.superioa.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class ActionType {
    OPEN_APP, BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, TAP, LONG_PRESS, TOGGLE, TYPE, CLEAR_TEXT, SELECT_ALL, SCROLL, SWIPE,
    COPY, PASTE, READ_UI, WAIT, VERIFY, RETRY, ABORT, REQUEST_USER
}

data class AgentAction(
    val type: ActionType,
    val target: String? = null,
    val text: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val direction: String? = null,
    val millis: Long? = null,
    val reason: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type.name)
        target?.let { put("target", it) }
        text?.let { put("text", it) }
        x?.let { put("x", it) }
        y?.let { put("y", it) }
        x2?.let { put("x2", it) }
        y2?.let { put("y2", it) }
        direction?.let { put("direction", it) }
        millis?.let { put("millis", it) }
        reason?.let { put("reason", it) }
    }

    /** Log-safe description: never includes typed text. */
    fun describe(): String = when (type) {
        ActionType.TYPE -> "TYPE(${text?.length ?: 0} chars)"
        ActionType.TAP, ActionType.LONG_PRESS -> if (target != null) "${type.name} \"$target\"" else "${type.name} ($x,$y)"
        ActionType.TOGGLE -> "TOGGLE \"${target ?: "focused control"}\""
        ActionType.OPEN_APP -> "OPEN_APP ${target ?: ""}"
        ActionType.SCROLL -> "SCROLL $direction"
        ActionType.SWIPE -> "SWIPE ($x,$y)->($x2,$y2)"
        ActionType.WAIT -> "WAIT ${millis}ms"
        ActionType.VERIFY -> "VERIFY \"${target ?: text}\""
        ActionType.ABORT, ActionType.REQUEST_USER -> "${type.name} ${reason ?: ""}".trim()
        else -> type.name
    }
}

data class Plan(val message: String, val done: Boolean, val actions: List<AgentAction>)

sealed class PlanResult {
    data class Valid(val plan: Plan) : PlanResult()
    data class Invalid(val reason: String) : PlanResult()
}

/** Strict schema validation of model-generated plans. Anything unexpected is rejected. */
object ActionValidator {
    const val MAX_ACTIONS_PER_PLAN = 12
    const val MAX_TEXT = 500
    const val MAX_TARGET = 200
    const val MAX_WAIT_MS = 10_000L

    private val ROOT_KEYS = setOf("message", "done", "actions")
    private val STRING_FIELDS = setOf("target", "text", "direction", "reason")
    private val INTEGER_FIELDS = setOf("x", "y", "x2", "y2")
    private val LONG_FIELDS = setOf("millis")
    private val DIRECTIONS = setOf("up", "down", "left", "right")

    private sealed class V {
        class Ok(val action: AgentAction) : V()
        class Bad(val reason: String) : V()
    }

    fun parsePlan(raw: String, width: Int = 10_000, height: Int = 10_000): PlanResult {
        val json = extractJson(raw) ?: return PlanResult.Invalid("No JSON found in reply")
        val el: JsonElement = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return PlanResult.Invalid("Reply is not valid JSON")
        }
        val message: String
        val done: Boolean
        val arr: JsonArray
        when (el) {
            is JsonArray -> {
                message = ""; done = false; arr = el
            }
            is JsonObject -> {
                val unknownRoot = el.keys - ROOT_KEYS
                if (unknownRoot.isNotEmpty()) return PlanResult.Invalid("unknown plan field(s): " + unknownRoot.joinToString().take(80))
                val messageValue = el["message"]
                if (messageValue != null && (messageValue as? JsonPrimitive)?.isString != true) {
                    return PlanResult.Invalid("'message' must be a string")
                }
                val doneValue = el["done"] as? JsonPrimitive
                if (el.containsKey("done") && doneValue?.booleanOrNull == null) {
                    return PlanResult.Invalid("'done' must be a boolean")
                }
                message = messageValue?.let { (it as JsonPrimitive).content } ?: ""
                done = doneValue?.booleanOrNull ?: false
                arr = when (val a = el["actions"]) {
                    null -> JsonArray(emptyList())
                    is JsonArray -> a
                    else -> return PlanResult.Invalid("'actions' must be an array")
                }
            }
            else -> return PlanResult.Invalid("Reply must be a JSON object")
        }
        if (arr.size > MAX_ACTIONS_PER_PLAN) {
            return PlanResult.Invalid("Too many actions (${arr.size} > $MAX_ACTIONS_PER_PLAN)")
        }
        if (arr.isEmpty() && !done) return PlanResult.Invalid("Plan has no actions and is not done")
        if (done && (arr.lastOrNull() as? JsonObject)?.str("type")?.equals("VERIFY", ignoreCase = true) != true) {
            return PlanResult.Invalid("A completed plan must end with a VERIFY action in the same reply")
        }

        val actions = ArrayList<AgentAction>()
        for ((i, item) in arr.withIndex()) {
            val o = item as? JsonObject ?: return PlanResult.Invalid("Action #${i + 1} is not an object")
            when (val r = validate(o, width, height)) {
                is V.Ok -> actions.add(r.action)
                is V.Bad -> return PlanResult.Invalid("Action #${i + 1}: ${r.reason}")
            }
        }
        return PlanResult.Valid(Plan(message.take(300), done, actions))
    }

    private fun validate(o: JsonObject, w: Int, h: Int): V {
        val tName = o.str("type")?.trim()?.uppercase() ?: return V.Bad("missing 'type'")
        val type = ActionType.entries.firstOrNull { it.name == tName }
            ?: return V.Bad("unsupported type '${tName.take(30)}'")

        val allowedFields = when (type) {
            ActionType.OPEN_APP -> setOf("type", "target")
            ActionType.TAP, ActionType.LONG_PRESS -> setOf("type", "target", "x", "y")
            ActionType.TYPE -> setOf("type", "text")
            ActionType.TOGGLE -> setOf("type", "target")
            ActionType.SCROLL -> setOf("type", "direction")
            ActionType.SWIPE -> setOf("type", "x", "y", "x2", "y2")
            ActionType.WAIT -> setOf("type", "millis")
            ActionType.VERIFY -> setOf("type", "target", "text")
            ActionType.ABORT, ActionType.REQUEST_USER -> setOf("type", "reason")
            else -> setOf("type")
        }
        val unknown = o.keys - allowedFields
        if (unknown.isNotEmpty()) return V.Bad("field(s) not allowed for " + tName + ": " + unknown.joinToString().take(80))
        for (field in o.keys.intersect(STRING_FIELDS)) {
            val value = o[field] as? JsonPrimitive
            if (value?.isString != true) return V.Bad("'$field' must be a string")
        }
        for (field in o.keys.intersect(INTEGER_FIELDS)) {
            val value = o[field] as? JsonPrimitive
            if (value == null || value.isString || value.intOrNull == null) return V.Bad("'$field' must be an integer")
        }
        for (field in o.keys.intersect(LONG_FIELDS)) {
            val value = o[field] as? JsonPrimitive
            if (value == null || value.isString || value.longOrNull == null) return V.Bad("'$field' must be an integer")
        }

        val a = AgentAction(
            type = type,
            target = o.str("target")?.trim(),
            text = o.str("text"),
            x = o.int("x"), y = o.int("y"), x2 = o.int("x2"), y2 = o.int("y2"),
            direction = o.str("direction")?.trim()?.lowercase(),
            millis = o.long("millis"),
            reason = o.str("reason")?.take(300),
        )
        fun inX(v: Int?) = v != null && v in 0 until w
        fun inY(v: Int?) = v != null && v in 0 until h

        return when (type) {
            ActionType.OPEN_APP -> {
                val t = a.target ?: ""
                when {
                    t.isEmpty() -> V.Bad("OPEN_APP needs 'target'")
                    t.startsWith("http", ignoreCase = true) ->
                        if (t.startsWith("https://", ignoreCase = true) && t.length <= 2000) V.Ok(a)
                        else V.Bad("only https:// URLs are allowed")
                    t.length > 100 -> V.Bad("app name too long")
                    else -> V.Ok(a)
                }
            }
            ActionType.TAP, ActionType.LONG_PRESS -> {
                val t = a.target ?: ""
                val actionName = if (type == ActionType.TAP) "TAP" else "LONG_PRESS"
                when {
                    t.isNotEmpty() && t.length <= MAX_TARGET && a.x == null && a.y == null -> V.Ok(a)
                    t.isEmpty() && inX(a.x) && inY(a.y) -> V.Ok(a)
                    else -> V.Bad("$actionName needs either a target or in-bounds x,y, not both")
                }
            }
            ActionType.TYPE -> {
                val t = a.text ?: ""
                if (t.isNotEmpty() && t.length <= MAX_TEXT) V.Ok(a) else V.Bad("TYPE needs 'text' (1..$MAX_TEXT chars)")
            }
            ActionType.CLEAR_TEXT, ActionType.SELECT_ALL -> V.Ok(a)
            ActionType.TOGGLE -> if (!a.target.isNullOrBlank() && a.target.length <= MAX_TARGET) V.Ok(a) else V.Bad("TOGGLE needs a target text or view id")
            ActionType.SCROLL ->
                if (a.direction in DIRECTIONS) V.Ok(a) else V.Bad("SCROLL direction must be up/down/left/right")
            ActionType.SWIPE ->
                if (inX(a.x) && inY(a.y) && inX(a.x2) && inY(a.y2)) V.Ok(a) else V.Bad("SWIPE needs in-bounds x,y,x2,y2")
            ActionType.WAIT -> {
                val m = a.millis
                if (m != null && m in 1..MAX_WAIT_MS) V.Ok(a) else V.Bad("WAIT millis must be 1..$MAX_WAIT_MS")
            }
            ActionType.VERIFY -> {
                val t = a.target ?: a.text ?: ""
                if (t.isNotEmpty() && t.length <= MAX_TARGET) V.Ok(a) else V.Bad("VERIFY needs 'target' or 'text'")
            }
            ActionType.BACK, ActionType.HOME, ActionType.RECENTS, ActionType.NOTIFICATIONS,
            ActionType.QUICK_SETTINGS, ActionType.COPY,
            ActionType.PASTE, ActionType.READ_UI, ActionType.RETRY,
            ActionType.ABORT, ActionType.REQUEST_USER -> V.Ok(a)
        }
    }

    private fun extractJson(raw: String): String? {
        val s = raw.trim()
        val start = s.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val close = if (s[start] == '{') '}' else ']'
        val end = s.lastIndexOf(close)
        if (end <= start) return null
        return s.substring(start, end + 1)
    }

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(k: String): Int? =
        (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.long(k: String): Long? =
        (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
