package com.lilac.anime.shared.compat
import kotlinx.serialization.json.*
class JSONObject internal constructor(private val values: MutableMap<String, JsonElement>) {
    constructor() : this(linkedMapOf())
    constructor(text: String) : this(Json.parseToJsonElement(text).jsonObject.toMutableMap())
    fun has(key: String) = key in values
    fun isNull(key: String) = values[key] == null || values[key] == JsonNull
    fun opt(key: String): Any? = unwrap(values[key])
    fun optString(key: String, fallback: String = ""): String = values[key]?.let {
        if (it == JsonNull) fallback else if (it is JsonPrimitive) it.content else it.toString()
    } ?: fallback
    fun optInt(key: String, fallback: Int = 0) = optString(key).toIntOrNull() ?: fallback
    fun optLong(key: String, fallback: Long = 0) = optString(key).toLongOrNull() ?: fallback
    fun optDouble(key: String, fallback: Double = Double.NaN) = optString(key).toDoubleOrNull() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false) = optString(key).toBooleanStrictOrNull() ?: fallback
    fun optJSONObject(key: String) = (values[key] as? JsonObject)?.let { JSONObject(it.toMutableMap()) }
    fun optJSONArray(key: String) = (values[key] as? JsonArray)?.let { JSONArray(it.toMutableList()) }
    fun getString(key: String): String { check(has(key)); return optString(key) }
    fun getJSONObject(key: String) = optJSONObject(key) ?: error("Missing object: $key")
    fun getJSONArray(key: String) = optJSONArray(key) ?: error("Missing array: $key")
    fun keys() = values.keys.iterator()
    fun put(key: String, value: Any?): JSONObject { values[key] = wrap(value); return this }
    override fun toString() = JsonObject(values).toString()
    internal fun json() = JsonObject(values)
    companion object { internal val NULL: Any = JsonNull }
}
class JSONArray internal constructor(private val values: MutableList<JsonElement>) {
    constructor() : this(mutableListOf())
    constructor(text: String) : this(Json.parseToJsonElement(text).jsonArray.toMutableList())
    fun length() = values.size
    fun opt(index: Int): Any? = unwrap(values.getOrNull(index))
    fun optString(index: Int, fallback: String = "") = values.getOrNull(index)?.let {
        if (it == JsonNull) fallback else if (it is JsonPrimitive) it.content else it.toString()
    } ?: fallback
    fun optInt(index: Int, fallback: Int = 0) = optString(index).toIntOrNull() ?: fallback
    fun optJSONObject(index: Int) = (values.getOrNull(index) as? JsonObject)?.let { JSONObject(it.toMutableMap()) }
    fun optJSONArray(index: Int) = (values.getOrNull(index) as? JsonArray)?.let { JSONArray(it.toMutableList()) }
    fun getString(index: Int): String { require(index in values.indices); return optString(index) }
    fun put(value: Any?): JSONArray { values.add(wrap(value)); return this }
    override fun toString() = JsonArray(values).toString()
    internal fun json() = JsonArray(values)
}
private fun unwrap(value: JsonElement?): Any? = when (value) {
    null, JsonNull -> null
    is JsonObject -> JSONObject(value.toMutableMap())
    is JsonArray -> JSONArray(value.toMutableList())
    is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
}
private fun wrap(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is JSONObject -> value.json()
    is JSONArray -> value.json()
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
