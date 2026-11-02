package ua.svitlyna.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A document photographed at an angle, made straight: the four corners the person placed are
 * stretched to a rectangle; optionally "clean" turns it into a crisp black-and-white page with the
 * shadows taken out (each pixel divided by the page's own brightness around it).
 */
object DocScan {
    /** Corners in picture pixels: top-left, top-right, bottom-right, bottom-left (x, y pairs). */
    fun straighten(src: Bitmap, corners: FloatArray): Bitmap {
        require(corners.size == 8)
        fun d(a: Int, b: Int) = hypot(corners[2 * a] - corners[2 * b], corners[2 * a + 1] - corners[2 * b + 1])
        val w = ((d(0, 1) + d(3, 2)) / 2).roundToInt().coerceIn(16, 6000)
        val h = ((d(0, 3) + d(1, 2)) / 2).roundToInt().coerceIn(16, 6000)
        val target = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        check(m.setPolyToPoly(corners, 0, target, 0, 4))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /** The clean black-and-white page. */
    fun clean(page: Bitmap): Bitmap {
        val w = page.width
        val h = page.height
        val px = IntArray(w * h)
        page.getPixels(px, 0, w, 0, 0, w, h)
        val out = cleanPixels(px, w, h)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Pure part, for tests: brightness divided by its local background, then stretched. */
    fun cleanPixels(argb: IntArray, w: Int, h: Int): IntArray {
        val gray = IntArray(w * h) { i ->
            val p = argb[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        // The background: the brightest nearby values (a box of about 1/20 of the page), so paper is
        // found under shadows while thin text doesn't count.
        val r = (maxOf(w, h) / 40).coerceAtLeast(4)
        val bg = boxMax(gray, w, h, r)
        val out = IntArray(w * h)
        for (i in gray.indices) {
            val b = bg[i].coerceAtLeast(1)
            var v = (gray[i] * 255 / b).coerceIn(0, 255)
            // A soft curve: paper goes white, ink goes dark, edges stay smooth.
            v = when {
                v > 210 -> 255
                v < 110 -> (v * 0.6).toInt()
                else -> ((v - 110) * 255 / 100).coerceIn(0, 255)
            }
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return out
    }

    /** Maximum over a (2r+1)² box, as two one-way passes, each in linear time (sliding window). */
    private fun boxMax(g: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val line = IntArray(maxOf(w, h))
        val res = IntArray(maxOf(w, h))
        for (y in 0 until h) {
            for (x in 0 until w) line[x] = g[y * w + x]
            slidingMax(line, w, r, res)
            for (x in 0 until w) tmp[y * w + x] = res[x]
        }
        val out = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) line[y] = tmp[y * w + x]
            slidingMax(line, h, r, res)
            for (y in 0 until h) out[y * w + x] = res[y]
        }
        return out
    }

    /** res[i] = max of a[i-r..i+r] (within 0..n-1), with a queue of candidates. */
    private fun slidingMax(a: IntArray, n: Int, r: Int, res: IntArray) {
        val q = IntArray(n)
        var head = 0
        var tail = 0
        var next = 0
        for (i in 0 until n) {
            val hi = minOf(n - 1, i + r)
            while (next <= hi) {
                while (tail > head && a[q[tail - 1]] <= a[next]) tail--
                q[tail++] = next
                next++
            }
            while (q[head] < i - r) head++
            res[i] = a[q[head]]
        }
    }
}
