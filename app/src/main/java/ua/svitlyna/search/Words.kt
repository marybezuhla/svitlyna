package ua.svitlyna.search

import java.text.Normalizer

/**
 * How words are compared in search. Every word, Ukrainian or Latin, becomes one plain Latin "key":
 * - letters are lower-cased, apostrophes dropped («пам'ять» = «памʼять» = «память»);
 * - Cyrillic is written in Latin letters (as in passports), so «Шевченко» and «Shevchenko» meet;
 * - a few spellings that differ between transliterations are made the same (y = i, j = i, w = v), so
 *   «і», «ї», «и», «й» don't matter either;
 * - the ending is cut off («Шевченка», «Шевченку», «Шевченком» → one key).
 * The same is done to what is found on photos and to what is typed, so they meet.
 */
object Words {
    private val CYR = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e", 'є' to "ie",
        'ж' to "zh", 'з' to "z", 'и' to "y", 'і' to "i", 'ї' to "i", 'й' to "i", 'к' to "k", 'л' to "l",
        'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ь' to "", 'ю' to "iu",
        'я' to "ia",
        // Letters a text recogniser or an old sign may give; written the nearest way.
        'ё' to "io", 'ъ' to "", 'ы' to "y", 'э' to "e",
    )

    /** Latin letters with marks that stand for two letters elsewhere (Czech-style transliteration). */
    private val MARKED = mapOf('š' to "sh", 'č' to "ch", 'ž' to "zh", 'ß' to "ss")

    private val APOSTROPHES = setOf('\'', '’', 'ʼ', '`', '´', '‘')

    /** Endings cut from keys, longest first (Ukrainian endings as they look after [key]). */
    private val ENDINGS = listOf(
        "iakh", "iami", "oiu", "eiu", "oho", "omu", "ami", "ovi", "evi", "imi", "iam",
        "ikh", "akh", "im", "ii", "am", "om", "em", "iv", "ei", "iu", "ia", "ie",
        "o", "a", "u", "i", "e",
    )

    /** A key shorter than this keeps its ending (too short to say what word it is). */
    private const val MIN_STEM = 3

    /** Words of [text]: runs of letters (with apostrophes inside) or digits. */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) out += sb.toString()
            sb.setLength(0)
        }
        for ((i, c) in text.withIndex()) {
            when {
                c.isLetterOrDigit() -> sb.append(c)
                // An apostrophe between letters belongs to the word.
                c in APOSTROPHES && sb.isNotEmpty() && i + 1 < text.length && text[i + 1].isLetter() -> sb.append(c)
                else -> flush()
            }
        }
        flush()
        return out
    }

    /** One word as plain Latin, before cutting the ending. */
    fun latin(word: String): String {
        val sb = StringBuilder()
        for (c in word.lowercase()) {
            when {
                c in APOSTROPHES -> {}
                CYR[c] != null -> sb.append(CYR[c])
                MARKED[c] != null -> sb.append(MARKED[c])
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                else -> {
                    // é → e, ü → u…; anything else (other scripts) is dropped.
                    val plain = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).filter { it in 'a'..'z' }
                    sb.append(plain)
                }
            }
        }
        return sb.toString()
            .replace('y', 'i')
            .replace('j', 'i')
            .replace('w', 'v')
    }

    /** The search key of one word: plain Latin without its ending. Numbers stay as they are. */
    fun key(word: String): String {
        val l = latin(word)
        if (l.isEmpty() || l.all { it.isDigit() }) return l
        for (e in ENDINGS) {
            if (l.endsWith(e) && l.length - e.length >= MIN_STEM) return l.dropLast(e.length)
        }
        return l
    }

    /** Keys of all words of [text] (for the index), each once. */
    fun keys(text: String): List<String> = split(text).map(::key).filter { it.isNotEmpty() }.distinct()

    /** Whether a [word] seen on a photo is what one of the typed [keys] looks for (beginning of the key). */
    fun matches(word: String, keys: List<String>): Boolean {
        val k = key(word)
        return k.isNotEmpty() && keys.any { k.startsWith(it) }
    }
}
