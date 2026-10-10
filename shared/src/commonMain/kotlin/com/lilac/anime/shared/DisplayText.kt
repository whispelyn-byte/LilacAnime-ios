package com.lilac.anime.shared

import com.fleeksoft.ksoup.Ksoup

/** API descriptions can contain HTML encoded once more inside JSON. Also applied
 * at display time so existing saved/catalog records do not need to be deleted. */
object DisplayText {
    fun plain(value: String): String {
        var text = value
        repeat(3) {
            val doc = Ksoup.parse(text)
            doc.select("script, style, noscript, iframe, template, svg").remove()
            val next = doc.wholeText().replace('\u00a0', ' ').trim()
            if (next == text) return normalize(next)
            text = next
        }
        return normalize(text)
    }
    private fun normalize(text: String) = text.replace(Regex("[\\t ]+"), " ")
        .replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}
