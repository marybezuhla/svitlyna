package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.svitlyna.ocr.joinOcrLines

class OcrTextTest {
    @Test
    fun wordSplitAtLineEndIsJoinedBack() {
        assertEquals("медичної допомоги", joinOcrLines(listOf("меди-", "чної допомоги")))
    }

    @Test
    fun dashAfterSpaceStaysAndCapitalStartsNewLine() {
        assertEquals("свідомості –\nдалі", joinOcrLines(listOf("свідомості –", "далі")))
        assertEquals("Київ-\nОдеса", joinOcrLines(listOf("Київ-", "Одеса")))
    }

    @Test
    fun emptyLinesAreSkipped() {
        assertEquals("а\nб", joinOcrLines(listOf("а", "  ", "б")))
    }
}
