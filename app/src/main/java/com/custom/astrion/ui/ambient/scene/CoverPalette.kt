package com.custom.astrion.ui.ambient.scene

/**
 * One telling colour pulled from a cover: its [color] in Oklab, how much of the
 * image it covers ([population], 0..1) and a [weight] that lifts colourful
 * clusters over large dull ones — a small splash of red on a grey sleeve should
 * still paint the backdrop.
 */
data class Swatch(val color: Oklab, val population: Float, val weight: Float)

/**
 * k-means over a cover's pixels in Oklab, the first stage of the backdrop bake.
 *
 * Pixels come in already downscaled (the caller decodes the art to ~96px), as
 * packed 0xRRGGBB ints. The result is at most k swatches, sorted by [weight]
 * (chroma-weighted, not population alone), with near-duplicates merged so two
 * clusters are never closer than [minDistance] in Oklab.
 *
 * Deterministic: the same pixels always give the same swatches. Initial centres
 * are seeded by a fixed stride through the (sorted) distinct colours rather than
 * at random, so there is nothing to seed and tests are stable. Pure JVM — no
 * android.graphics — so it runs under a plain unit test.
 */
object CoverPalette {

    /** Clusters closer than this in Oklab are the "same" colour and get merged. */
    const val MIN_DISTANCE = 0.09f

    /**
     * A neutral cluster's chroma is ~0, so a floor keeps it present and
     * comparable rather than weightless; kept small so any real colour still
     * outranks grey. See [weightOf].
     */
    private const val CHROMA_FLOOR = 0.02f

    private const val MAX_ITERATIONS = 12

    /**
     * @param pixels packed 0xRRGGBB, ideally a downscaled cover (~96px square).
     * @param k how many clusters to seek before merging (default 5).
     * @param minDistance the distinctness floor in Oklab.
     */
    fun of(pixels: IntArray, k: Int = 5, minDistance: Float = MIN_DISTANCE): List<Swatch> {
        if (pixels.isEmpty()) return emptyList()
        val labs = Array(pixels.size) { OklabConvert.toOklab(pixels[it]) }

        // Seed from distinct colours spread evenly through the sorted set, so a
        // flat image with one colour seeds one real centre, not k copies.
        val distinct = labs.distinctBy { (it.l * 100).toInt() to (it.a * 100).toInt() to (it.b * 100).toInt() }
        val seeds = seed(distinct, k)
        if (seeds.isEmpty()) return emptyList()

        var centres = seeds
        val assign = IntArray(labs.size)
        repeat(MAX_ITERATIONS) {
            var moved = false
            for (i in labs.indices) {
                var best = 0; var bestD = Float.MAX_VALUE
                for (c in centres.indices) {
                    val d = labs[i].distance(centres[c])
                    if (d < bestD) { bestD = d; best = c }
                }
                if (assign[i] != best) { assign[i] = best; moved = true }
            }
            val next = recompute(labs, assign, centres.size)
            centres = next.mapIndexed { idx, c -> c ?: centres[idx] }
            if (!moved) return@repeat
        }

        // Turn each non-empty cluster into a swatch, then merge near-duplicates.
        val counts = IntArray(centres.size)
        for (a in assign) counts[a]++
        val total = labs.size.toFloat()
        val raw = centres.indices
            .filter { counts[it] > 0 }
            .map { idx ->
                val pop = counts[idx] / total
                val c = centres[idx]
                Swatch(c, pop, weight = weightOf(pop, c.chroma))
            }
            .sortedByDescending { it.weight }

        return merge(raw, minDistance)
    }

    /**
     * Chroma outranks size: population enters only by its square root, while
     * chroma scales the whole thing, so a small vivid splash leads a large dull
     * field. [CHROMA_FLOOR] keeps neutrals present but last.
     */
    private fun weightOf(population: Float, chroma: Float): Float =
        kotlin.math.sqrt(population) * (chroma + CHROMA_FLOOR)

    private fun seed(distinct: List<Oklab>, k: Int): List<Oklab> {
        if (distinct.isEmpty()) return emptyList()
        if (distinct.size <= k) return distinct
        val step = distinct.size.toFloat() / k
        return (0 until k).map { distinct[(it * step).toInt().coerceIn(0, distinct.size - 1)] }
    }

    private fun recompute(labs: Array<Oklab>, assign: IntArray, n: Int): List<Oklab?> {
        val sl = FloatArray(n); val sa = FloatArray(n); val sb = FloatArray(n); val cnt = IntArray(n)
        for (i in labs.indices) {
            val c = assign[i]
            sl[c] += labs[i].l; sa[c] += labs[i].a; sb[c] += labs[i].b; cnt[c]++
        }
        return (0 until n).map { if (cnt[it] == 0) null else Oklab(sl[it] / cnt[it], sa[it] / cnt[it], sb[it] / cnt[it]) }
    }

    /** Fold clusters closer than [minDistance] into the heavier one. */
    private fun merge(sorted: List<Swatch>, minDistance: Float): List<Swatch> {
        val kept = mutableListOf<Swatch>()
        for (s in sorted) {
            val near = kept.firstOrNull { it.color.distance(s.color) < minDistance }
            if (near == null) kept += s
        }
        return kept
    }
}
