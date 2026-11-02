package ua.svitlyna

import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.media.Sharpness

class SharpnessTest {
    private val w = 64
    private val h = 64

    /** A checkerboard of 4-px squares: many hard edges. */
    private fun sharp() = IntArray(w * h) { i -> if (((i % w) / 4 + (i / w) / 4) % 2 == 0) 20 else 230 }

    /** The same board blurred several times with a 3×3 box. */
    private fun blurred(): IntArray {
        var g = sharp()
        repeat(6) {
            val out = g.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                var s = 0
                for (dy in -1..1) for (dx in -1..1) s += g[(y + dy) * w + x + dx]
                out[y * w + x] = s / 9
            }
            g = out
        }
        return g
    }

    @Test
    fun sharpScoresHigh() = assertTrue(Sharpness.variance(sharp(), w, h) > Sharpness.BLURRY * 10)

    @Test
    fun blurredScoresLow() = assertTrue(Sharpness.variance(blurred(), w, h) < Sharpness.variance(sharp(), w, h) / 20)

    @Test
    fun flatIsBlurry() = assertTrue(Sharpness.variance(IntArray(w * h) { 128 }, w, h) < Sharpness.BLURRY)

    @Test
    fun grayWeights() {
        val g = Sharpness.gray(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt()))
        assertTrue(g[0] == 255 && g[1] == 0 && g[2] in 75..77)
    }
}
