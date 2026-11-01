package ua.svitlyna.media

/** Plain numbers in, plain numbers out: grouping of look-alike photos, checked by tests without a phone. */
object Similar {
    /** [grey]: 9 columns × 8 rows of brightness. Bit k is set when pixel k is brighter than its right neighbour. */
    fun dHash(grey: IntArray): Long {
        require(grey.size == 72)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (grey[y * 9 + x] > grey[y * 9 + x + 1]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    /**
     * Groups (indexes) of hashes at most [maxBits] bits from the group's first photo, with almost the
     * same shape ([ratios]: width / height). Measured from the first, not from any member, so that
     * look-alike chains (one dark shot like the next, and so on) don't grow into one huge group.
     * Six bands of the hash: two hashes 5 bits apart share at least one whole band, so only hashes
     * sharing a band are compared (from Iris Gallery, Apache 2.0). Plain pictures (all one colour)
     * say nothing about what is on them and are left out.
     */
    fun group(hashes: List<Long>, ratios: List<Float>, maxBits: Int = Duplicates.SIMILAR_BITS): List<List<Int>> {
        // For each photo: the first photo of its group (itself when it starts one).
        val head = IntArray(hashes.size) { -1 }
        val bands = HashMap<Long, MutableList<Int>>()
        fun keys(h: Long) = (0 until 6).map { band ->
            val start = band * 11
            val width = if (band == 5) 9 else 11
            (band.toLong() shl 56) or ((h ushr start) and ((1L shl width) - 1))
        }
        for (i in hashes.indices) {
            val h = hashes[i]
            if (h == 0L || h == -1L) continue
            val ks = keys(h)
            // Only the first photos of groups are in the bands: each new photo is measured against them.
            var best = -1
            var bestBits = maxBits + 1
            for (k in ks) for (j in bands[k].orEmpty()) {
                val bits = java.lang.Long.bitCount(h xor hashes[j])
                if (bits < bestBits && kotlin.math.abs(ratios[i] - ratios[j]) < 0.035f) {
                    best = j
                    bestBits = bits
                }
            }
            if (best >= 0) {
                head[i] = best
            } else {
                head[i] = i
                ks.forEach { k -> bands.getOrPut(k) { mutableListOf() } += i }
            }
        }
        return hashes.indices.filter { head[it] >= 0 }.groupBy { head[it] }.values.filter { it.size > 1 }
    }
}
