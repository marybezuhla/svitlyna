package ua.svitlyna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.svitlyna.media.Similar
import kotlin.random.Random

class SimilarTest {
    private fun picture(seed: Int) = Random(seed).let { r -> IntArray(72) { r.nextInt(100_000) } }

    @Test
    fun brighterLeftSetsTheBit() {
        val grey = IntArray(72) { i -> if (i % 9 == 0) 10 else 0 }
        // Only the first pixel of each row is brighter than its neighbour: bits 0, 8, 16…
        val expected = (0 until 8).fold(0L) { acc, y -> acc or (1L shl (y * 8)) }
        assertEquals(expected, Similar.dHash(grey))
    }

    @Test
    fun nearHashesGroupFarOnesDont() {
        val a = Similar.dHash(picture(1))
        val b = a xor 0b10101L // 3 bits apart: the same shot, a little different
        val c = a xor (1L shl 40) xor (1L shl 63) xor 0b111L // 5 bits: still similar
        val d = Similar.dHash(picture(2)) // another picture
        val groups = Similar.group(listOf(a, b, c, d), listOf(1.33f, 1.33f, 1.33f, 1.33f))
        assertEquals(listOf(listOf(0, 1, 2)), groups.map { it.sorted() })
    }

    @Test
    fun differentShapeIsNotTheSamePhoto() {
        val a = Similar.dHash(picture(5))
        val groups = Similar.group(listOf(a, a), listOf(1.33f, 0.75f))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun plainPicturesSayNothing() {
        // All one colour: every hash is 0, which would make all plain pictures "similar".
        val groups = Similar.group(listOf(0L, 0L, 0L), listOf(1f, 1f, 1f))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun sixBitsApartIsNotSimilar() {
        val a = Similar.dHash(picture(9))
        val b = a xor 0b111111L
        assertTrue(Similar.group(listOf(a, b), listOf(1f, 1f)).isEmpty())
    }
}
