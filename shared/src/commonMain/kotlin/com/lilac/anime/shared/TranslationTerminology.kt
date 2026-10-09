package com.lilac.anime.shared
/** Desktop anime-glossary's fixed phrases; custom names use one source=Korean entry per line. */
object TranslationTerminology {
    private val phrases = listOf(
        Triple("いただきます", "잘 먹겠습니다", true), Triple("ごちそうさまでした", "잘 먹었습니다", true),
        Triple("ごちそうさま", "잘 먹었어", true), Triple("ただいま", "다녀왔어", true),
        Triple("おかえりなさい", "어서 와", true), Triple("おかえり", "어서 와", true),
        Triple("いってきます", "다녀올게", true), Triple("行ってきます", "다녀올게", true),
        Triple("いってらっしゃい", "잘 다녀와", true), Triple("行ってらっしゃい", "잘 다녀와", true),
        Triple("おやすみなさい", "안녕히 주무세요", true), Triple("おやすみ", "잘 자", true),
        Triple("お疲れ様でした", "수고하셨습니다", false), Triple("お疲れさまでした", "수고하셨습니다", false),
        Triple("お疲れ様です", "수고하십니다", false), Triple("お疲れさまです", "수고하십니다", false),
        Triple("よろしくお願いします", "잘 부탁드립니다", false), Triple("よろしくお願いいたします", "잘 부탁드립니다", false),
        Triple("先輩", "선배", false), Triple("先生", "선생님", false), Triple("マジで", "진짜", false))
    fun hints(text: String, custom: String): String {
        val customTerms = custom.lines().mapNotNull { line ->
            val parts = line.split('=', limit = 2).map(String::trim)
            if (parts.size == 2 && parts.all(String::isNotBlank)) Triple(parts[0], parts[1], false) else null
        }
        val matches = (customTerms + phrases).filter { term ->
            if (!term.third) text.contains(term.first) else Regex("(^|[\\s、。，．！？!?…‥「」『』（）()〜~ー・])" + Regex.escape(term.first) + "($|[\\s、。，．！？!?…‥「」『』（）()〜~ー・])").containsMatchIn(text)
        }.distinctBy { it.first }
        return matches.filter { term -> matches.none { other -> other.first.length > term.first.length && other.first.contains(term.first) } }
            .joinToString("\n") { it.first + " = " + it.second }
    }
}
