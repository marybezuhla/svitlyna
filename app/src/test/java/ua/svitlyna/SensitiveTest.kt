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
        // An order number of 16 digits that fails the check is not a card.
        assertFalse(Kind.CARD in kinds("Замовлення 1234 5678 9012 3456"))
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
        assertFalse(Sensitive.ibanValid("UA213223130000026007233566002"))
        assertEquals(listOf("АА 1234 ВС"), found("номер АА 1234 ВС біля під'їзду"))
        assertEquals(listOf(Kind.PLATE), kinds("KA1234AB"))
        assertEquals(listOf("вул. Хрещатик, 22"), found("Зустріч: вул. Хрещатик, 22, кв. 5"))
        assertEquals(listOf(Kind.ADDRESS), kinds("просп. Перемоги 37а"))
    }

    @Test
    fun nothingInOrdinaryText() {
        assertTrue(kinds("Привіт! Зустрінемось завтра о 18:30 біля кав'ярні.").isEmpty())
    }
}
