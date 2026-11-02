package ua.svitlyna

import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.media.DocScan

class DocScanTest {
    /** A page with a shadow on its right half and dark text strokes: the paper goes white everywhere. */
    @Test
    fun shadowRemovedTextKept() {
        val w = 200
        val h = 100
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val paper = if (x < 100) 230 else 120 // shadow
            val ink = (y % 20 == 10 && x % 10 < 6)
            val v = if (ink) paper / 4 else paper
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = DocScan.cleanPixels(px, w, h)
        fun g(x: Int, y: Int) = out[y * w + x] and 0xFF
        assertTrue(g(30, 3) == 255)   // paper in the light
        assertTrue(g(150, 3) == 255)  // paper in the shadow
        assertTrue(g(31, 10) < 80)    // ink in the light
        assertTrue(g(151, 10) < 80)   // ink in the shadow
    }

    @Test
    fun cornersAnyOrder() {
        // Dragged in a crossed order: bottom-right, top-left, bottom-left, top-right.
        val got = DocScan.ordered(floatArrayOf(90f, 80f, 10f, 5f, 12f, 85f, 95f, 8f))
        val want = floatArrayOf(10f, 5f, 95f, 8f, 90f, 80f, 12f, 85f)
        assertTrue(got.contentToString(), got.contentEquals(want))
    }
}
