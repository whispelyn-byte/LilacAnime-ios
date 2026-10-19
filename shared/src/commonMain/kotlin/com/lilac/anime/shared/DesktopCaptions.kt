package com.lilac.anime.shared

/**
 * subtitle-translator.cjs dropCaptions (효과음·화자 이름 빼고 번역): a line's caption labels and speaker name are taken out
 * by the app before it is translated, since a translation-only model (TranslateGemma) takes no instruction to leave them out.
 * - Labels in brackets anywhere on a line: a name or a sound, （創太） （ため息） （ドアの開く音） [laughs] (SIGHS). A Japanese one
 *   is a label when it is kanji / katakana alone (a name, 拍手, 足音) or ends in a sound word (…音, …声, ため息); one with
 *   hiragana otherwise is kept, as Japanese subtitles bracket a character's thoughts too: （どうしよう）. An English one is a
 *   label when it is three words at most without sentence marks.
 * - A short label before the words on a line is the speaker's, with hiragana too: （男の子）こんにちは.
 * - A speaker before a colon at the start: 創太： SOTA: (an English one in capitals only, so "Note: …" stays).
 * - A line of vocal sounds alone: あっ, うう…, くっ, はあ, あはは, ふふっ, Ugh!, Hahaha (not うん, ええ, ううん: those are words).
 * A cue left with nothing is not translated and is left out of the translated file. The scripts are spelled out as ranges
 * (\p{Script=…} is not read the same way on every platform).
 */
object DesktopCaptions {
    private const val HAN = "㐀-䶿一-鿿豈-﫿"
    private const val KATAKANA = "゠-ヿㇰ-ㇿｦ-ﾟ"
    private const val HIRAGANA = "぀-ゟ"
    private val soundWord = Regex("(?:音|声|息|笑い|笑|泣き|悲鳴|咳|咳払い|拍手|歓声|鼻歌|舌打ち|いびき|あくび|くしゃみ|うめき|叫び|ノック|チャイム|ベル|着信|振動|鳴る|鳴く|音楽|BGM)$")
    private val vocal = Regex("^(?:[あぁアァ]+|[うぅウゥ]+|[くク]+|[ぐグ]+|[んン]+|[ひヒ]+|[へヘ]+|[ほホ]+|[ふフ]+|[はハ][あぁアァ]*|(?:[あぁアァ]?[はハ])+|(?:[うぅウゥ]?[ふフ])+|(?:[えぇエェ]?[へヘ])+|(?:[いぃイィ]?[ひヒ])+|a+h*|o+h+|u+g*h+|h+m+|m+|(?:h[aeio]){2,}|gr+|a+r+g+h+|o+w+)$", RegexOption.IGNORE_CASE)
    private val marks = Regex("[。、！？!?…]")
    private val nameOnly = Regex("^[$HAN${KATAKANA}ー・々\\s]+$")
    private val japanese = Regex("[$HIRAGANA$HAN$KATAKANA]")
    private val leadingLabel = Regex("^[（(［\\[]([^（）()［］\\[\\]\\n]{1,12})[）)］\\]]\\s*(?=\\S)")
    private val label = Regex("[（(［\\[]([^（）()［］\\[\\]\\n]{1,20})[）)］\\]]")
    private val japaneseSpeaker = Regex("^[$HAN${KATAKANA}ー・々]{1,10}[：:]\\s*")
    private val englishSpeaker = Regex("^[A-Z][A-Z0-9 .'-]{0,20}:\\s+")
    private val separators = Regex("[\\s、。，,.!！?？…‥~～〜・-]+")
    private val letter = Regex("\\p{L}")

    private fun captionLabel(inside: String): Boolean {
        val value = inside.trim()
        if (value.isEmpty() || marks.containsMatchIn(value)) return false
        if (nameOnly.containsMatchIn(value) || soundWord.containsMatchIn(value)) return true
        return !japanese.containsMatchIn(value) && value.split(Regex("\\s+")).size <= 3
    }

    // A line the patterns cannot read is translated as it is (a Kotlin exception would end the app on iOS).
    fun drop(text: String): String = runCatching { dropLabels(text) }.getOrDefault(text)
    private fun dropLabels(text: String): String = text.split('\n').mapNotNull { line ->
        // A short label before the words is the speaker's, hiragana or not: （男の子）こんにちは.
        var value = leadingLabel.replaceFirst(line.trim()) { if (marks.containsMatchIn(it.groupValues[1])) it.value else "" }
        value = label.replace(value) { if (captionLabel(it.groupValues[1])) " " else it.value }.trim()
        value = englishSpeaker.replaceFirst(japaneseSpeaker.replaceFirst(value, ""), "").trim()
        val words = value.split(separators).map { it.replace(Regex("[ーっッ]"), "") }.filter { it.isNotEmpty() }
        if (words.isNotEmpty() && words.all { vocal.matches(it) } || !letter.containsMatchIn(value)) null
        else value.replace(Regex("\\s{2,}"), " ")
    }.filter { it.isNotEmpty() }.joinToString("\n")
}

private fun Regex.replaceFirst(input: String, transform: (MatchResult) -> CharSequence): String {
    val match = find(input) ?: return input
    return input.substring(0, match.range.first) + transform(match) + input.substring(match.range.last + 1)
}
