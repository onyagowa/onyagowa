package com.onyagowa.dancemashup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MashupPlannerTest {

    private val clips = listOf(4_000L, 12_000L, 900L, 30_000L)

    @Test
    fun totalLengthMatchesTarget() {
        for (seed in 0 until 50) {
            for (chaos in listOf(0f, 0.5f, 1f)) {
                val plan = MashupPlanner(Random(seed)).plan(clips, 30_000L, 120, chaos)
                val total = plan.sumOf { it.durationMs }
                assertTrue("seed=$seed chaos=$chaos total=$total", total in 29_900L..30_000L)
            }
        }
    }

    @Test
    fun cutsStayInsideTheirClips() {
        for (seed in 0 until 50) {
            MashupPlanner(Random(seed)).plan(clips, 60_000L, 140, 1f).forEach {
                assertTrue(it.startMs >= 0)
                assertTrue("$it", it.startMs + it.durationMs <= clips[it.clipIndex])
                assertTrue(it.durationMs >= 100)
            }
        }
    }

    @Test
    fun everyClipGetsUsed() {
        val plan = MashupPlanner(Random(7)).plan(clips, 30_000L, 120, 0.5f)
        assertEquals(clips.indices.toSet(), plan.map { it.clipIndex }.toSet())
    }

    @Test
    fun tooShortClipsAreIgnored() {
        val plan = MashupPlanner(Random(1)).plan(listOf(100L, 5_000L), 10_000L, 120, 0.5f)
        assertTrue(plan.all { it.clipIndex == 1 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun failsWithNoUsableClips() {
        MashupPlanner().plan(listOf(50L), 10_000L, 120, 0.5f)
    }
}
