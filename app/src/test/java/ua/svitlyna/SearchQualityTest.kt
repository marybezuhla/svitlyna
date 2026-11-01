package ua.svitlyna

import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.search.Words

/**
 * The checking set: texts as they come off photos (with recogniser slips: capitals, apostrophe
 * look-alikes, Latin look-alike letters) and Ukrainian queries. Search quality is measured as the
 * share of queries that find their photo and only it; it must stay above the bar.
 */
class SearchQualityTest {
    private val photos = mapOf(
        1 to "ТАРАС ШЕВЧЕНКО 1814-1861 Кобзар",
        2 to "Вул. Хрещатик, 22 Київ",
        3 to "ЧЕК № 0042 АТБ-маркет Хліб 23,50 Молоко 41,90 Разом 65,40",
        4 to "Пам’ятник Лесі Українці",
        5 to "Квиток Укрзалізниця Інтерсіті+ Київ — Львів вагон 5 місце 23",
        6 to "Посвідчення водія Driving licence UKRAINE",
        7 to "Меню: борщ, вареники з вишнями, узвар",
        8 to "Шевченківський район, м. Львів",
        9 to "Олександр Довженко «Зачарована Десна»",
        10 to "Аптека 9-1-1 Парацетамол 500 мг",
    )
    private val index = photos.mapValues { Words.keys(it.value) }

    private fun found(q: String): Set<Int> {
        val keys = Words.split(q).map(Words::key).filter { it.isNotEmpty() }
        return index.filterValues { words -> keys.all { k -> words.any { it.startsWith(k) } } }.keys
    }

    /** Query → the photos it must find (the first one at least). */
    private val queries = listOf(
        "Шевченко" to setOf(1, 8),
        "Шевченка" to setOf(1, 8),
        "Тарасові Шевченку" to setOf(1),
        "кобзар" to setOf(1),
        "Хрещатику" to setOf(2),
        "Khreshchatyk" to setOf(2),
        "чек АТБ" to setOf(3),
        "молока" to setOf(3),
        "памятник" to setOf(4),
        "пам'ятник Лесі Українці" to setOf(4),
        "Леся Українка" to setOf(4),
        "квиток Львів" to setOf(5),
        "укрзалізниці" to setOf(5),
        "посвідчення водія" to setOf(6),
        "driving licence" to setOf(6),
        "вареники" to setOf(7),
        "борщу" to setOf(7),
        "Довженка" to setOf(9),
        "Десна" to setOf(9),
        "парацетамолу" to setOf(10),
    )

    @Test
    fun enoughQueriesFindExactlyTheirPhotos() {
        val missed = queries.filter { (q, want) -> found(q) != want }
        val share = 1.0 - missed.size.toDouble() / queries.size
        // The bar: 90 %. What is missed is printed, to see what to improve.
        assertTrue("missed: " + missed.joinToString { (q, w) -> "$q → ${found(q)} (want $w)" }, share >= 0.9)
    }
}
