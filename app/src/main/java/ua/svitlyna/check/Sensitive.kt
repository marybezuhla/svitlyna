package ua.svitlyna.check

/**
 * Finds personal details in recognised text, before a photo is sent: card numbers, phone numbers,
 * e-mail, IBAN, Ukrainian car plates, street addresses. Plain text in, character ranges out, so it
 * can be checked by tests without a phone. It only points things out: the person decides.
 */
object Sensitive {
    enum class Kind { CARD, PHONE, EMAIL, IBAN, PLATE, ADDRESS }

    data class Found(val kind: Kind, val range: IntRange)

    // A run of digits with spaces or dashes; card numbers are looked for inside it (expiry dates,
    // CVV or amounts next to the number don't hide it).
    private val DIGIT_RUN = Regex("""(?<!\d)\d(?:[ -]?\d){12,}(?!\d)""")

    // Four groups of four digits: blacked out even if the check sum fails (a misread digit).
    private val CARD_4X4 = Regex("""(?<!\d)\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4}(?!\d)""")

    // Phone: +380 / 380 / 0 and nine digits (Ukrainian), or any + and 10–14 digits; separators allowed.
    private val PHONE = Regex(
        """(?<![\d+])(?:\+?38[\s-]?)?\(?0\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}(?!\d)""" +
            """|(?<![\d+])\+?380[\s-]?\(?\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}(?!\d)""" +
            """|(?<![\d+])\+\d(?:[\s-]?\d){9,13}(?!\d)""",
    )

    private val EMAIL = Regex("""[\p{L}\p{N}._%+-]+@[\p{L}\p{N}.-]+\.\p{L}{2,}""")

    private val IBAN = Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}\b""")

    // Car plate: two letters, four digits, two letters (Cyrillic letters that look Latin, or Latin).
    private const val PLATE_LETTERS = "АВЕІКМНОРСТХABEIKMHOPCTX"
    private val PLATE = Regex("""(?<![\p{L}\d])[$PLATE_LETTERS]{2}\s?\d{4}\s?[$PLATE_LETTERS]{2}(?![\p{L}\d])""")

    // Address: a street word, a name, and a house number.
    private val ADDRESS = Regex(
        """(?iu)(?<!\p{L})(?:(?:вул|просп|пров|бульв|пл|наб|str|st|ave|ul)\.\s*|(?:вулиця|вул|проспект|провулок|бульвар|площа|набережна|street|avenue)\s+)""" +
            """[\p{L}'’.\- ]{2,40}?,?\s*(?:буд\.\s*)?\d+[\p{L}]?(?:/\d+)?""",
    )

    fun find(text: String): List<Found> {
        val out = ArrayList<Found>()
        fun add(kind: Kind, r: IntRange) {
            if (out.none { it.range.first <= r.last && r.first <= it.range.last }) out += Found(kind, r)
        }
        // Most specific first, so a card number isn't also reported as a phone.
        IBAN.findAll(text).forEach { m -> ibanIn(m.value)?.let { r -> add(Kind.IBAN, (m.range.first + r.first)..(m.range.first + r.last)) } }
        // Recognition may read 0 as O or 1 as l: such letters between digits count as digits.
        val digits = lookalikesAsDigits(text)
        DIGIT_RUN.findAll(digits).forEach { m -> cardIn(m.value)?.let { r -> add(Kind.CARD, (m.range.first + r.first)..(m.range.first + r.last)) } }
        CARD_4X4.findAll(digits).forEach { add(Kind.CARD, it.range) }
        EMAIL.findAll(text).forEach { add(Kind.EMAIL, it.range) }
        PHONE.findAll(text).forEach { add(Kind.PHONE, it.range) }
        PLATE.findAll(text).forEach { add(Kind.PLATE, it.range) }
        ADDRESS.findAll(text).forEach { add(Kind.ADDRESS, it.range) }
        return out.sortedBy { it.range.first }
    }

    /**
     * The card number (13–19 digits passing Luhn) inside a run of digits, as a range in it. Several
     * windows may pass by chance, so the likeliest wins: one that starts and ends where the digit
     * groups do, then 16 digits (the usual length), then the longer one.
     */
    private fun cardIn(run: String): IntRange? {
        val pos = run.indices.filter { run[it].isDigit() }
        fun edgeBefore(p: Int) = p == 0 || !run[p - 1].isDigit()
        fun edgeAfter(p: Int) = p == run.length - 1 || !run[p + 1].isDigit()
        var best: IntRange? = null
        var bestScore = -1
        for (len in 13..19) {
            for (start in 0..pos.size - len) {
                val digits = String(CharArray(len) { run[pos[start + it]] })
                if (!luhn(digits)) continue
                val first = pos[start]
                val last = pos[start + len - 1]
                val score = (if (edgeBefore(first) && edgeAfter(last)) 100 else 0) + (if (len == 16) 50 else 0) + len
                if (score > bestScore) {
                    bestScore = score
                    best = first..last
                }
            }
        }
        return best
    }

    /** The IBAN at the start of a match, without anything stuck after it (like "UAH"). */
    private fun ibanIn(m: String): IntRange? {
        val pos = m.indices.filter { m[it] != ' ' }
        for (len in minOf(34, pos.size) downTo 15) {
            val candidate = String(CharArray(len) { m[pos[it]] })
            if (ibanValid(candidate)) return pos[0]..pos[len - 1]
        }
        return null
    }

    private const val LOOKALIKES = "OoОоlI|"

    /** Same length as [text]: O, o → 0 and l, I, | → 1 where a digit is right before or after. */
    fun lookalikesAsDigits(text: String): String {
        val c = text.toCharArray()
        // Repeated until nothing changes, so a run like "4lll" becomes "4111" letter by letter.
        var changed = true
        while (changed) {
            changed = false
            for (i in c.indices) {
                if (c[i] !in LOOKALIKES) continue
                val near = (i > 0 && c[i - 1].isDigit()) || (i + 1 < c.size && c[i + 1].isDigit())
                if (near) {
                    c[i] = if (c[i] in "OoОо") '0' else '1'
                    changed = true
                }
            }
        }
        return String(c)
    }

    fun luhn(s: String): Boolean {
        val digits = s.filter { it.isDigit() }
        if (digits.length !in 13..19) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** IBAN check digits (mod 97), so a random code that looks like one isn't flagged. */
    fun ibanValid(s: String): Boolean {
        val iban = s.replace(" ", "").uppercase()
        if (iban.length !in 15..34) return false
        val moved = iban.drop(4) + iban.take(4)
        var rest = 0
        for (c in moved) {
            val v = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                else -> return false
            }
            rest = if (v >= 10) (rest * 100 + v) % 97 else (rest * 10 + v) % 97
        }
        return rest == 1
    }
}
