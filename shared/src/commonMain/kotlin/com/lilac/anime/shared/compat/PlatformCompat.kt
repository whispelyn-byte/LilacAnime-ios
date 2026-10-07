package com.lilac.anime.shared.compat
import io.ktor.http.Url
import kotlin.math.pow
internal object Log {
    var sink: ((String, String) -> Unit)? = null
    fun d(tag: String, message: String) { sink?.invoke(tag, message) }
    fun v(tag: String, message: String) = d(tag, message)
    fun w(tag: String, message: String) = d(tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) = d(tag, message)
}
internal class Uri private constructor(private val url: String) {
    val path: String get() = url.substringBefore('?').substringBefore('#').let {
        if ("://" in it) "/" + it.substringAfter("://").substringAfter('/', "") else it
    }
    fun getQueryParameter(key: String): String? = runCatching { Url(url).parameters[key] }.getOrNull()
    companion object { fun parse(value: String) = Uri(value) }
}
internal fun String.format(vararg values: Any?): String {
    var index = 0
    return Regex("%(0?)([0-9]*)(?:\\.([0-9]+))?([dscf%])").replace(this) { match ->
        if (match.groupValues[4] == "%") "%" else {
            val value = values[index++]
            val plain = when (match.groupValues[4]) {
                "d" -> (value as Number).toLong().toString()
                "f" -> {
                    val precision = match.groupValues[3].toIntOrNull() ?: 6
                    val factor = 10.0.pow(precision.toDouble())
                    val rounded = kotlin.math.round((value as Number).toDouble() * factor).toLong()
                    val integer = rounded / factor.toLong()
                    val fraction = kotlin.math.abs(rounded % factor.toLong()).toString().padStart(precision, '0')
                    if (precision == 0) integer.toString() else "$integer.$fraction"
                }
                else -> value.toString()
            }
            plain.padStart(match.groupValues[2].toIntOrNull() ?: 0, if (match.groupValues[1] == "0") '0' else ' ')
        }
    }
}

internal fun <K, V : Any> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? {
    val previous = this[key]
    if (previous == null) this[key] = value
    return previous
}
