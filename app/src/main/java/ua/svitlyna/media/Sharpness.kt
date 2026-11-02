package ua.svitlyna.media

/**
 * How sharp a picture is: the variance of the Laplacian of its brightness (Pech-Pacheco et al., 2000;
 * the usual quick blur measure). Edges give large Laplacian values, a blurred picture has few. A plain
 * subject (sky, a wall) also scores low, so the result is only a suggestion to check.
 */
object Sharpness {
    /** Below this (on a picture about 384 px across) a photo is offered as "possibly blurred". */
    const val BLURRY = 30.0

    /** [gray] is brightness 0..255, row by row, [w]×[h]. */
    fun variance(gray: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return Double.MAX_VALUE
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]).toDouble()
                sum += lap
                sq += lap * lap
                n++
            }
        }
        val mean = sum / n
        return sq / n - mean * mean
    }

    /** Brightness from ARGB pixels (Rec. 601 weights). */
    fun gray(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }
}
