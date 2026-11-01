package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.check.Sensitive
import ua.svitlyna.check.Sensitive.Kind

class SensitiveTest {
    private fun kinds(text: String) = Sensitive.find(text).map { it.kind }
    private fun found(text: String) = Sensitive.find(text).map { text.substring(it.range) }

    @Test
    fun cardNumbersOnlyWithValidChecksum() {
        assertEquals(listOf(Kind.CARD), kinds("Картка 4111 1111 1111 1111 до 12/27"))
        assertEquals(listOf("5168-7451-0000-0006"), found("5168-7451-0000-0006"))
        assertTrue(Sensitive.luhn("4111111111111111"))
        assertFalse(Sensitive.luhn("4111111111111112"))
        // 16 digits in one piece that fail the check: not a card.
        assertFalse(Kind.CARD in kinds("Замовлення 1234567890123455"))
        // Four groups of four are blacked out even then: better too much than a misread card.
        assertEquals(listOf(Kind.CARD), kinds("1234 5678 9012 3456"))
        // Expiry date, CVV or an amount right next to the number don't hide it.
        assertEquals(listOf("4111 1111 1111 1111"), found("4111 1111 1111 1111 12/27"))
        assertEquals(listOf("4111111111111111"), found("4111111111111111 12 27"))
        assertEquals(listOf("4111111111111111"), found("Сума 1 250 4111111111111111"))
        // A misread 0 (O) or 1 (l) still finds the card.
        assertEquals(listOf(Kind.CARD), kinds("4lll 1111 1111 1111"))
    }

    @Test
    fun ukrainianAndInternationalPhones() {
        assertEquals(listOf("+380 67 123 45 67"), found("Дзвоніть: +380 67 123 45 67"))
        assertEquals(listOf("067-123-45-67"), found("тел. 067-123-45-67"))
        assertEquals(listOf("(067) 123 45 67"), found("(067) 123 45 67, Олена"))
        assertEquals(listOf("+48 600 700 800"), found("PL +48 600 700 800"))
        // A year or a price is not a phone.
        assertTrue(kinds("Київ 2026, ціна 1250 грн").isEmpty())
    }

    @Test
    fun emailIbanPlateAddress() {
        assertEquals(listOf("olena.k@ukr.net"), found("пишіть olena.k@ukr.net"))
        assertEquals(listOf(Kind.IBAN), kinds("IBAN UA213223130000026007233566001"))
        assertEquals(listOf("UA213223130000026007233566001"), found("UA213223130000026007233566001 UAH"))
        assertFalse(Sensitive.ibanValid("UA213223130000026007233566002"))
        assertEquals(listOf("АА 1234 ВС"), found("номер АА 1234 ВС біля під'їзду"))
        assertEquals(listOf(Kind.PLATE), kinds("KA1234AB"))
        assertEquals(listOf("вул. Хрещатик, 22"), found("Зустріч: вул. Хрещатик, 22, кв. 5"))
        assertEquals(listOf(Kind.ADDRESS), kinds("просп. Перемоги 37а"))
        assertEquals(listOf(Kind.ADDRESS), kinds("вул.Хрещатик 22"))
        assertEquals(listOf(Kind.ADDRESS), kinds("вул. Т. Шевченка, буд. 5"))
        assertEquals(listOf(Kind.PHONE), kinds("+380 (67) 123-45-67"))
    }

    @Test
    fun nothingInOrdinaryText() {
        assertTrue(kinds("Привіт! Зустрінемось завтра о 18:30 біля кав'ярні.").isEmpty())
    }
}
