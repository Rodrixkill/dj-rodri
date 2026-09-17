package com.rvillarroel.djrodri.domain.dj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransitionPlannerTest {

    private val full = Transition(Exit.Full, Entry.FromStart)
    private val warmedUp = List(5) { full }

    @Test
    fun `the first songs of the night play whole as a warm-up`() {
        val plan = TransitionPlanner.plan(song("a"), song("b"), past = emptyList(), mode = DjMode.FIESTA)

        assertEquals(Exit.Full, plan.exit)
    }

    @Test
    fun `once warmed up a confident song is cut at its last natural exit`() {
        val plan = TransitionPlanner.plan(song("a"), song("b"), past = warmedUp, mode = DjMode.FIESTA)

        assertEquals(Exit.CutAt(180_000), plan.exit)
    }

    @Test
    fun `hora loca leaves at the first natural exit`() {
        val plan = TransitionPlanner.plan(song("a"), song("b"), past = warmedUp, mode = DjMode.HORA_LOCA)

        assertEquals(Exit.CutAt(120_000), plan.exit)
    }

    @Test
    fun `never two cuts in a row`() {
        val past = warmedUp + Transition(Exit.CutAt(120_000), Entry.FromStart)

        assertEquals(Exit.Full, TransitionPlanner.plan(song("a"), song("b"), past, DjMode.HORA_LOCA).exit)
    }

    @Test
    fun `short songs, weak lyrics and Completa all mean the song plays whole`() {
        assertEquals(Exit.Full, TransitionPlanner.plan(song("a", durationMs = 170_000), song("b"), warmedUp, DjMode.HORA_LOCA).exit)
        assertEquals(Exit.Full, TransitionPlanner.plan(song("a", chorusConfidence = 0.65), song("b"), warmedUp, DjMode.FIESTA).exit)
        assertEquals(Exit.Full, TransitionPlanner.plan(song("a"), song("b"), warmedUp, DjMode.FIESTA, keepFull = true).exit)
    }

    @Test
    fun `after a cut the next song can come in straight on its drop`() {
        val plan = TransitionPlanner.plan(song("a"), song("b", userDropMs = 52_000), warmedUp, DjMode.FIESTA)

        assertEquals(Entry.AtDrop(52_000), plan.entry)
    }

    @Test
    fun `no drop entry into a calmer song or an uncertain drop`() {
        val calmer = TransitionPlanner.plan(song("a", energy = 0.8), song("b", energy = 0.5), warmedUp, DjMode.FIESTA)
        val unsure = TransitionPlanner.plan(song("a"), song("b", chorusConfidence = 0.75), warmedUp, DjMode.FIESTA)

        assertEquals(Entry.FromStart, calmer.entry)
        assertEquals(Entry.FromStart, unsure.entry)
    }

    @Test
    fun `a natural ending never jumps into the next drop`() {
        val plan = TransitionPlanner.plan(song("a", durationMs = 170_000), song("b", userDropMs = 52_000), warmedUp, DjMode.HORA_LOCA)

        assertEquals(Transition(Exit.Full, Entry.FromStart), plan)
    }

    @Test
    fun `drop entries are rationed by mode`() {
        val recentDrop = List(4) { full } + Transition(Exit.CutAt(180_000), Entry.AtDrop(40_000)) + full + full

        // Three transitions since the last drop: fine for Hora loca (every 2), too soon for Fiesta (every 4).
        assertEquals(Entry.FromStart, TransitionPlanner.plan(song("a"), song("b"), recentDrop, DjMode.FIESTA).entry)
        assertEquals(Entry.AtDrop(40_000), TransitionPlanner.plan(song("a"), song("b"), recentDrop, DjMode.HORA_LOCA).entry)
    }
}
