package ua.svitlyna.edit

import kotlin.math.abs

/**
 * Finding how screenshots of one long page fit together. Plain numbers in, plain numbers out, so it
 * is checked by tests without a phone.
 *
 * Each screenshot is reduced to "row signatures": for every row, [SAMPLES] grey values at evenly
 * spaced columns. Two screenshots of a scrolled page share a fixed top (status bar, app bar) and a
 * fixed bottom (navigation, a toolbar), and between them the page moved up by some number of rows.
 * That number is found by sliding one over the other and counting rows that match, ignoring plain
 * rows (white space matches anything and proves nothing).
 */
object Stitch {
    const val SAMPLES = 48

    /** Grey values of [SAMPLES] columns for each of [height] rows, row after row. */
    class Rows(val height: Int, val data: IntArray) {
        init {
            require(data.size == height * SAMPLES)
        }

        fun same(i: Int, other: Rows, j: Int, tolerance: Int = 10): Boolean {
            val a = i * SAMPLES
            val b = j * SAMPLES
            for (k in 0 until SAMPLES) if (abs(data[a + k] - other.data[b + k]) > tolerance) return false
            return true
        }

        /** A row with something on it (text, a picture edge), not plain background. */
        fun busy(i: Int): Boolean {
            val a = i * SAMPLES
            var min = 255
            var max = 0
            for (k in 0 until SAMPLES) {
                val v = data[a + k]
                if (v < min) min = v
                if (v > max) max = v
            }
            return max - min > 24
        }
    }

    /**
     * How screenshot B continues screenshot A: both have [header] fixed rows at the top and [footer]
     * at the bottom; the page in B is [shift] rows further down than in A.
     */
    data class Join(val header: Int, val footer: Int, val shift: Int)

    fun find(a: Rows, b: Rows): Join? {
        if (a.height != b.height || a.height < 100) return null
        val h = a.height
        var header = 0
        while (header < h / 3 && a.same(header, b, header)) header++
        var footer = 0
        while (footer < h / 3 && a.same(h - 1 - footer, b, h - 1 - footer)) footer++
        val start = header
        val end = h - footer
        val content = end - start
        // Nothing moved (the same screen twice), or almost no room for the page.
        if (content < 60) return null
        val minOverlap = maxOf(40, content / 10)

        for (shift in 1..content - minOverlap) {
            val overlap = content - shift
            var busy = 0
            var bad = 0
            var row = 0
            val allowedBad = overlap / 200 + 1
            while (row < overlap) {
                // Row `row` of B's page is row `row + shift` of A's page.
                val ia = start + shift + row
                val ib = start + row
                if (!a.same(ia, b, ib)) {
                    bad++
                    if (bad > allowedBad) break
                } else if (b.busy(ib)) {
                    busy++
                }
                row += 2
            }
            // The first (smallest) shift that matches everywhere, with enough real content in common.
            if (bad <= allowedBad && busy >= 12) return Join(header, footer, shift)
        }
        return null
    }

    /** A part of one screenshot that goes into the long one: rows [from] until [to] of picture [index]. */
    data class Piece(val index: Int, val from: Int, val to: Int)

    /**
     * The parts that make up the long screenshot, top to bottom, for pictures of [height] rows joined
     * by [joins] (one per neighbouring pair): the first picture down to where its fixed bottom starts,
     * each next one only its new rows, and the last one's fixed bottom at the very end.
     */
    fun pieces(height: Int, joins: List<Join>): List<Piece> {
        val n = joins.size + 1
        return (0 until n).mapNotNull { k ->
            val top = if (k == 0) 0 else height - joins[k - 1].footer - joins[k - 1].shift
            val bottom = if (k == n - 1) height else height - joins[k].footer
            if (bottom > top) Piece(k, top, bottom) else null
        }
    }

    /** Simply one under the other, when no overlap is found (or the person wants it so). */
    fun stacked(heights: List<Int>): List<Piece> = heights.mapIndexed { i, h -> Piece(i, 0, h) }
}
