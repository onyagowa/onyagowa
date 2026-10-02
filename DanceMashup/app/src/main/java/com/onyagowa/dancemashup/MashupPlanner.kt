package com.onyagowa.dancemashup

import kotlin.random.Random

/** A visual gag applied to one cut of the mashup. */
enum class FunnyEffect { NONE, MIRROR, ZOOM, GRAYSCALE, UPSIDE_DOWN }

/** One cut: [durationMs] of clip [clipIndex] starting at [startMs]. */
data class Segment(
    val clipIndex: Int,
    val startMs: Long,
    val durationMs: Long,
    val effect: FunnyEffect = FunnyEffect.NONE,
)

/**
 * Chops the input clips into beat-length cuts and shuffles them into one timeline.
 *
 * Pure Kotlin so it can be unit tested without a device.
 */
class MashupPlanner(private val random: Random = Random.Default) {

    /**
     * @param clipDurationsMs duration of each input clip.
     * @param targetMs total length of the mashup.
     * @param bpm song tempo; every cut is a whole number of beats long.
     * @param chaos 0..1 — higher means shorter cuts, more stutters and more effects.
     */
    fun plan(clipDurationsMs: List<Long>, targetMs: Long, bpm: Int, chaos: Float): List<Segment> {
        val usable = clipDurationsMs.indices.filter { clipDurationsMs[it] >= MIN_CUT_MS }
        require(usable.isNotEmpty()) { "Need at least one video longer than ${MIN_CUT_MS}ms" }
        require(targetMs >= MIN_CUT_MS) { "Mashup must be at least ${MIN_CUT_MS}ms" }
        val c = chaos.coerceIn(0f, 1f)
        val beatMs = (60_000L / bpm.coerceIn(40, 240)).coerceAtLeast(MIN_CUT_MS)
        val beatChoices = if (c > 0.66f) listOf(1, 2, 2, 4) else if (c > 0.33f) listOf(2, 2, 4, 4) else listOf(2, 4, 4, 8)

        val segments = mutableListOf<Segment>()
        val bag = ArrayDeque<Int>()
        var last = -1
        var total = 0L

        fun nextClip(): Int {
            if (bag.isEmpty()) {
                val refill = usable.shuffled(random).toMutableList()
                // Avoid showing the same clip twice in a row across refills.
                if (refill.size > 1 && refill.first() == last) refill.add(refill.removeAt(0))
                bag.addAll(refill)
            }
            return bag.removeFirst()
        }

        fun add(seg: Segment) {
            val remaining = targetMs - total
            val d = minOf(seg.durationMs, remaining)
            if (d < MIN_TAIL_MS) return
            segments += seg.copy(durationMs = d)
            total += d
        }

        while (targetMs - total >= MIN_TAIL_MS) {
            val clip = nextClip()
            last = clip
            val clipLen = clipDurationsMs[clip]
            val wanted = beatMs * beatChoices.random(random)
            val dur = wanted.coerceAtMost(clipLen).coerceAtLeast(MIN_CUT_MS)
            val start = if (clipLen > dur) random.nextLong(clipLen - dur + 1) else 0L
            val effect = pickEffect(c)

            if (random.nextFloat() < c * 0.35f && clipLen >= beatMs) {
                // Stutter: repeat the same short moment a few times — the classic meme cut.
                val stutterLen = beatMs.coerceAtMost(clipLen)
                val stutterStart = if (clipLen > stutterLen) random.nextLong(clipLen - stutterLen + 1) else 0L
                repeat(2 + random.nextInt(2)) {
                    add(Segment(clip, stutterStart, stutterLen, if (it == 1) pickEffect(1f) else effect))
                }
            } else {
                add(Segment(clip, start, dur, effect))
            }
        }
        return segments
    }

    private fun pickEffect(chaos: Float): FunnyEffect =
        if (random.nextFloat() < chaos * 0.45f) {
            EFFECTS.random(random)
        } else {
            FunnyEffect.NONE
        }

    companion object {
        const val MIN_CUT_MS = 250L
        private const val MIN_TAIL_MS = 100L
        private val EFFECTS = FunnyEffect.entries.filter { it != FunnyEffect.NONE }
    }
}
