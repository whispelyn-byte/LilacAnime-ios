package com.lilac.anime.shared

/** Exact desktop glossary rules: Hepburn names, one-character boundaries and speaker-aware sibling terms. */
object AnimeGlossary {
    private val syllables = mapOf(
        "a" to "아",
        "i" to "이",
        "u" to "우",
        "e" to "에",
        "o" to "오",
        "ka" to "카",
        "ki" to "키",
        "ku" to "쿠",
        "ke" to "케",
        "ko" to "코",
        "kya" to "캬",
        "kyu" to "큐",
        "kyo" to "쿄",
        "ga" to "가",
        "gi" to "기",
        "gu" to "구",
        "ge" to "게",
        "go" to "고",
        "gya" to "갸",
        "gyu" to "규",
        "gyo" to "교",
        "sa" to "사",
        "shi" to "시",
        "si" to "시",
        "su" to "스",
        "se" to "세",
        "so" to "소",
        "sha" to "샤",
        "shu" to "슈",
        "sho" to "쇼",
        "she" to "셰",
        "za" to "자",
        "ji" to "지",
        "zi" to "지",
        "zu" to "즈",
        "ze" to "제",
        "zo" to "조",
        "ja" to "자",
        "ju" to "주",
        "jo" to "조",
        "je" to "제",
        "ta" to "타",
        "chi" to "치",
        "ti" to "티",
        "tsu" to "츠",
        "tu" to "츠",
        "te" to "테",
        "to" to "토",
        "cha" to "차",
        "chu" to "추",
        "cho" to "초",
        "che" to "체",
        "da" to "다",
        "di" to "디",
        "du" to "두",
        "dzu" to "즈",
        "de" to "데",
        "do" to "도",
        "na" to "나",
        "ni" to "니",
        "nu" to "누",
        "ne" to "네",
        "no" to "노",
        "nya" to "냐",
        "nyu" to "뉴",
        "nyo" to "뇨",
        "ha" to "하",
        "hi" to "히",
        "fu" to "후",
        "hu" to "후",
        "he" to "헤",
        "ho" to "호",
        "hya" to "햐",
        "hyu" to "휴",
        "hyo" to "효",
        "fa" to "파",
        "fi" to "피",
        "fe" to "페",
        "fo" to "포",
        "ba" to "바",
        "bi" to "비",
        "bu" to "부",
        "be" to "베",
        "bo" to "보",
        "bya" to "뱌",
        "byu" to "뷰",
        "byo" to "뵤",
        "pa" to "파",
        "pi" to "피",
        "pu" to "푸",
        "pe" to "페",
        "po" to "포",
        "pya" to "퍄",
        "pyu" to "퓨",
        "pyo" to "표",
        "ma" to "마",
        "mi" to "미",
        "mu" to "무",
        "me" to "메",
        "mo" to "모",
        "mya" to "먀",
        "myu" to "뮤",
        "myo" to "묘",
        "ya" to "야",
        "yu" to "유",
        "yo" to "요",
        "ra" to "라",
        "ri" to "리",
        "ru" to "루",
        "re" to "레",
        "ro" to "로",
        "rya" to "랴",
        "ryu" to "류",
        "ryo" to "료",
        "wa" to "와",
        "wo" to "오",
        "wi" to "위",
        "we" to "웨",
        "va" to "바",
        "vi" to "비",
        "vu" to "부",
        "ve" to "베",
        "vo" to "보")
    private fun final(text: String, consonant: Int): String {
        if (text.isEmpty()) return text
        val code = text.last().code - 0xac00
        return if (code in 0 until 11172 && code % 28 == 0) text.dropLast(1) + (0xac00 + code + consonant).toChar() else text
    }
    private fun word(value: String): String {
        var rest = value.lowercase().replace("ō", "o").replace("ū", "u").replace("â", "a").replace("ê", "e").replace("î", "i")
            .replace("ô", "o").replace("û", "u").replace(Regex("[^a-z']"), "")
            .replace(Regex("ou(?![aiueo])"), "o").replace("uu", "u").replace("oo", "o")
        var result = ""
        while (rest.isNotEmpty()) {
            when {
                rest[0] == '\'' -> { rest = rest.drop(1); continue }
                Regex("^n(?![aiueoy])|^m(?=[bpm])").containsMatchIn(rest) -> { if (result.isEmpty()) return ""; result = final(result, 4); rest = rest.drop(1); continue }
                Regex("^([kgsztdhbpcfjmr])\\1").containsMatchIn(rest) || rest.startsWith("tch") -> { if (result.isEmpty()) return ""; result = final(result, 19); rest = rest.drop(1); continue }
            }
            val key = listOf(3, 2, 1).map { rest.take(it) }.firstOrNull { it in syllables } ?: return ""
            result += syllables[key]; rest = rest.drop(key.length)
        }
        return result
    }
    fun romaji(name: String): String {
        val parts = name.trim().split(Regex("[\\s-]+")).filter(String::isNotBlank).map(::word)
        return if (parts.isNotEmpty() && parts.all(String::isNotBlank)) parts.joinToString(" ") else ""
    }
    private fun split(character: AnimeCharacter): List<String>? {
        val native = character.native.trim()
        val parts = native.split(Regex("[\\s・･=＝]+")).filter(String::isNotBlank)
        if (parts.size == 2) return parts
        if (parts.size != 1 || native.length < 2) return null
        fun kana(c: Char) = c in '\u3040'..'\u30ff'
        val change = (1 until native.length).firstOrNull { kana(native[it]) != kana(native[it - 1]) }
        if (change != null) return listOf(native.take(change), native.drop(change))
        if (native.any(::kana) || character.last.length + character.first.length == 0) return null
        val cut = kotlin.math.floor(native.length.toDouble() * character.last.length / (character.last.length + character.first.length) + 0.5).toInt().coerceIn(1, native.length - 1)
        return listOf(native.take(cut), native.drop(cut))
    }
    fun characterTerms(characters: List<AnimeCharacter>): String = characters.flatMap { character ->
        val first = romaji(character.first); val last = romaji(character.last)
        when {
            character.native.isBlank() -> emptyList()
            first.isNotBlank() && last.isNotBlank() -> listOf(character.native to (last + " " + first)) + (split(character)?.let { listOf(it[0] to last, it[1] to first) }.orEmpty())
            character.last.isBlank() && first.isNotBlank() -> listOf(character.native to first)
            else -> emptyList()
        }
    }.filter { it.first.isNotBlank() }.distinctBy { it.first }.joinToString("\n") { it.first + "=" + it.second }
    private fun member(tag: String, characters: List<AnimeCharacter>): AnimeCharacter? {
        val name = tag.replace(Regex("\\([^)]*\\)|（[^）]*）"), "").replace(Regex("[\\s　]"), "")
        if (name.isBlank() || Regex("[･・、,]").containsMatchIn(name)) return null
        characters.firstOrNull { it.native.replace(Regex("[\\s　]"), "") == name }?.let { return it }
        return characters.filter { split(it)?.contains(name) == true }.singleOrNull()
    }
    fun speakerTerms(text: String, characters: List<AnimeCharacter>): String {
        var speaker: AnimeCharacter? = null
        val found = linkedMapOf<String, String>()
        val brothers = listOf("お兄ちゃん", "おにいちゃん", "おにーちゃん", "お兄さん", "おにいさん", "兄ちゃん", "兄さん", "兄貴")
        val sisters = listOf("お姉ちゃん", "おねえちゃん", "おねーちゃん", "おね～ちゃん", "お姉さん", "おねえさん", "姉ちゃん", "姉さん", "姉貴")
        for (line in text.lines()) {
            Regex("^\\s*[（(]((?:[^（）()]|\\([^)]*\\))+)[）)]").find(line)?.let { speaker = member(it.groupValues[1], characters) }
            val gender = speaker?.gender?.lowercase()
            if (gender !in listOf("male", "female")) continue
            for (word in brothers) if (line.contains(word)) found[word] = if (gender == "male") "형" else "오빠"
            for (word in sisters) if (line.contains(word)) found[word] = if (gender == "male") "누나" else "언니"
        }
        return found.filterKeys { term -> found.keys.none { it.length > term.length && it.contains(term) } }.entries.joinToString("\n") { it.key + "=" + it.value }
    }
    fun hints(text: String, characters: List<AnimeCharacter>, custom: String): String {
        val terms = (custom + "\n" + speakerTerms(text, characters) + "\n" + characterTerms(characters)).lines().filter { line ->
            val term = line.substringBefore('=')
            if (term.length != 1) true else {
                Regex("(^|[\\s、。，．！？!?…‥「」『』（）()〜~ー・])" + Regex.escape(term) + "($|[\\s、。，．！？!?…‥「」『』（）()〜~ー・さくち様殿君氏先っ])").containsMatchIn(text)
            }
        }.joinToString("\n")
        return TranslationTerminology.hints(text, terms)
    }
}
