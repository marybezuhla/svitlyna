package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.svitlyna.edit.NameDate
import java.time.LocalDateTime

class NameDateTest {
    private fun at(name: String) = NameDate.parse(name, latestYear = 2026)

    @Test
    fun cameraAndScreenshotNames() {
        assertEquals(NameDate.Found(LocalDateTime.of(2024, 5, 12, 14, 30, 21), true), at("IMG_20240512_143021.jpg"))
        assertEquals(LocalDateTime.of(2024, 5, 12, 14, 30, 21), at("PXL_20240512_143021123.jpg")!!.at)
        assertEquals(LocalDateTime.of(2024, 5, 12, 14, 30, 21), at("Screenshot_20240512-143021_Chrome.jpg")!!.at)
    }

    @Test
    fun messengerNames() {
        // WhatsApp keeps only the date: noon, marked as date only.
        assertEquals(NameDate.Found(LocalDateTime.of(2024, 5, 12, 12, 0), false), at("IMG-20240512-WA0001.jpg"))
        assertEquals(LocalDateTime.of(2024, 5, 12, 14, 30, 21), at("photo_2024-05-12_14-30-21.jpg")!!.at)
        assertEquals(LocalDateTime.of(2024, 5, 12, 14, 30, 21), at("signal-2024-05-12-143021.jpg")!!.at)
    }

    @Test
    fun nonsenseIsNotADate() {
        assertNull(at("DSC01234.JPG"))
        assertNull(at("IMG_20241332_250000.jpg"))
        assertNull(at("photo_2099-01-01.jpg"))
        assertNull(at("receipt.png"))
    }
}
