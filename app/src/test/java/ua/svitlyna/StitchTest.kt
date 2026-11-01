package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.svitlyna.edit.Stitch
import kotlin.random.Random

/**
 * A long page with a fixed app bar on top and a toolbar at the bottom, "screenshotted" at several
 * scroll positions: stitching must give back exactly the page.
 */
class StitchTest {
    private val random = Random(3)
    private fun row(busy: Boolean = true) = IntArray(Stitch.SAMPLES) { if (busy) random.nextInt(256) else 250 }

    private val page = List(5000) { row(random.nextFloat() > 0.3f) }
    private val header = List(80) { row() }
    private val footer = List(60) { row() }
    private val height = 1000
    private val content = height - header.size - footer.size

    private fun shot(offset: Int): List<IntArray> = header + page.subList(offset, offset + content) + footer

    private fun rows(r: List<IntArray>) = Stitch.Rows(r.size, IntArray(r.size * Stitch.SAMPLES).also { d ->
        r.forEachIndexed { i, v -> v.copyInto(d, i * Stitch.SAMPLES) }
    })

    @Test
    fun findsHowFarThePageMovedAndRebuildsIt() {
        val offsets = listOf(0, 500, 1100, 1500)
        val shots = offsets.map { shot(it) }
        val joins = (0 until shots.size - 1).map { Stitch.find(rows(shots[it]), rows(shots[it + 1]))!! }
        assertEquals(listOf(500, 600, 400), joins.map { it.shift })
        assertEquals(80, joins[0].header)
        assertEquals(60, joins[0].footer)

        val out = Stitch.pieces(height, joins).flatMap { p -> shots[p.index].subList(p.from, p.to) }
        val expected = header + page.subList(0, offsets.last() + content) + footer
        assertEquals(expected.size, out.size)
        expected.indices.forEach { assertEquals("row $it", expected[it].toList(), out[it].toList()) }
    }

    @Test
    fun noOverlapIsReported() {
        // Two parts of the page far apart: nothing in common to join on.
        assertNull(Stitch.find(rows(shot(0)), rows(shot(3000))))
        // The same screen twice: the page didn't move.
        assertNull(Stitch.find(rows(shot(200)), rows(shot(200))))
    }

    @Test
    fun stackedKeepsEverything() {
        assertEquals(listOf(Stitch.Piece(0, 0, 10), Stitch.Piece(1, 0, 20)), Stitch.stacked(listOf(10, 20)))
    }
}
