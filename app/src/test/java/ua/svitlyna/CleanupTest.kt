package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.ui.Cleanup

class CleanupTest {
    @Test
    fun messengerFolders() {
        for (p in listOf(
            "/storage/emulated/0/Android/media/org.telegram.messenger/Telegram/Telegram Images/1.jpg",
            "/storage/emulated/0/Pictures/Telegram/2.jpg",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/3.jpg",
            "/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/4.jpg",
            "/storage/emulated/0/Viber/media/Viber Images/5.jpg",
            "/storage/emulated/0/Pictures/Signal/6.jpg",
        )) assertTrue(p, Cleanup.fromMessenger(p, ""))
        assertTrue(Cleanup.fromMessenger("", "Telegram"))
    }

    @Test
    fun notMessengers() {
        for (p in listOf(
            "/storage/emulated/0/DCIM/Camera/IMG_1.jpg",
            "/storage/emulated/0/Pictures/Telegraph/1.jpg",
            "/storage/emulated/0/Pictures/MySignals/2.jpg",
        )) assertFalse(p, Cleanup.fromMessenger(p, "Camera"))
    }

    @Test
    fun estimate() {
        assertEquals(1, Cleanup.estimateMinutes(1))
        assertEquals(1, Cleanup.estimateMinutes(2000))
        assertEquals(50, Cleanup.estimateMinutes(100_000))
    }
}
