package ua.svitlyna.check

/**
 * Finds personal details in recognised text, before a photo is sent: card numbers, phone numbers,
 * e-mail, IBAN, Ukrainian car plates, street addresses. Plain text in, character ranges out, so it
 * can be checked by tests without a phone. It only points things out: the person decides.
 */
object Sensitive {
    enum class Kind { CARD, PHONE, EMAIL, IBAN, PLATE, ADDRESS }

    data class Found(val kind: Kind, val range: IntRange)

    // Card: 13–19 digits, maybe in groups of 4 with spaces or dashes; checked with the Luhn sum.
    private val CARD = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")

    // Phone: +380 / 380 / 0 and nine digits (Ukrainian), or any + and 10–14 digits; separators allowed.
    private val PHONE = Regex("""(?<![\d+])(?:\+?38[\s-]?)?\(?0\d{2}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}(?!\d)|(?<![\d+])\+\d(?:[\s-]?\d){9,13}(?!\d)""")

    private val EMAIL = Regex("""[\p{L}\p{N}._%+-]+@[\p{L}\p{N}.-]+\.\p{L}{2,}""")

    private val IBAN = Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}\b""")

    // Car plate: two letters, four digits, two letters (Cyrillic letters that look Latin, or Latin).
    private const val PLATE_LETTERS = "АВЕІКМНОРСТХABEIKMHOPCTX"
    private val PLATE = Regex("""(?<![\p{L}\d])[$PLATE_LETTERS]{2}\s?\d{4}\s?[$PLATE_LETTERS]{2}(?![\p{L}\d])""")

    // Address: a street word, a name, and a house number.
    private val ADDRESS = Regex(
        """(?iu)(?<!\p{L})(?:вул\.?|вулиця|просп\.?|проспект|пров\.?|провулок|бульв\.?|бульвар|пл\.|площа|наб\.|набережна|str\.?|street|st\.|ave\.?|avenue|ul\.)\s+[\p{L}'’.\- ]{2,40}?,?\s*\d+[\p{L}]?(?:/\d+)?""",
    )

    fun find(text: String): List<Found> {
        val out = ArrayList<Found>()
        fun add(kind: Kind, r: IntRange) {
            if (out.none { it.range.first <= r.last && r.first <= it.range.last }) out += Found(kind, r)
        }
        // Most specific first, so a card number isn't also reported as a phone.
        IBAN.findAll(text).forEach { m -> if (ibanValid(m.value)) add(Kind.IBAN, m.range) }
        CARD.findAll(text).forEach { m -> if (luhn(m.value)) add(Kind.CARD, m.range) }
        EMAIL.findAll(text).forEach { add(Kind.EMAIL, it.range) }
        PHONE.findAll(text).forEach { add(Kind.PHONE, it.range) }
        PLATE.findAll(text).forEach { add(Kind.PLATE, it.range) }
        ADDRESS.findAll(text).forEach { add(Kind.ADDRESS, it.range) }
        return out.sortedBy { it.range.first }
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
