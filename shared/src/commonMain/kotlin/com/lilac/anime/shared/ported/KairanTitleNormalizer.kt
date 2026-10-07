package com.lilac.anime.shared.ported

import com.lilac.anime.shared.*
import com.lilac.anime.shared.compat.*
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import kotlin.math.min


object KairanTitleNormalizer {
    fun normalize(title: String): String {
        val hangul = Regex("[가-힣]+")
            .findAll(title)
            .joinToString("") { it.value }

        return if (hangul.length >= 2) {
            hangul
        } else {
            title.replace(Regex("[^a-zA-Z0-9\\s]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
