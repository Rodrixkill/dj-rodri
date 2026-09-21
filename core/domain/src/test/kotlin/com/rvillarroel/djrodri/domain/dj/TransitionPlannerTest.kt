package com.rvillarroel.djrodri.domain.dj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransitionPlannerTest {

    private val full = Transition(Exit.Full, Entry.FromStart)
    private val warmedUp = List(5) { full }

    /** A next song with no known drop, so the plan is a plain cut and never a drop swap. */
    private val noDrop = song("b", chorusConfidence = null, bpm = null)

    /** A next song too far off in tempo to swap into. */
    private val otherTempo = song("b", bpm = 128.0)

    private val electronic = setOf("house", "edm", "electronic")

    @Test
    fun `once warmed up the DJ swaps the second drop of a song for the drop of the next one`() {
        val plan = TransitionPlanner.plan(song("a"), song("b"), past = warmedUp, mode = DjMode.FIESTA)

        // Second chorus of "a" starts at 1:40; the first chorus of "b" (its drop) at 0:40.
        assertEquals(Transition(Exit.CutAt(100_000, swap = true), Entry.AtDrop(40_000)), plan)
    }

    @Test
    fun `drop swaps are rationed, in Fiesta at most one every four transitions`() {
        val swap = Transition(Exit.CutAt(100_000, swap = true), Entry.AtDrop(40_000))
        val past = warmedUp + swap + full + full

        val plan = TransitionPlanner.plan(song("a"), song("b"), past, DjMode.FIESTA)

        assertEquals(false, (plan.exit as? Exit.CutAt)?.swap ?: false)
    }

    @Test
    fun `between electronic tracks the swap works off the tempo grid alone, and twice as often`() {
        val swap = Transition(Exit.CutAt(100_000, swap = true), Entry.AtDrop(40_000))
        val past = warmedUp + swap + full + full
        val a = song("a", chorusConfidence = null, bpm = 126.0).copy(genres = electronic)
        val b = song("b", chorusConfidence = null, bpm = 128.0).copy(genres = electronic)

        val plan = TransitionPlanner.plan(a, b, past, DjMode.FIESTA)

        // At 126 BPM a phrase is 15.2 s: the phrase nearest the middle of a 3:50 track starts at 2:01.9.
        // At 128 BPM a phrase is 15 s: the one nearest a quarter of the track is at 1:00.
        assertEquals(Transition(Exit.CutAt(121_904, swap = true), Entry.AtDrop(60_000)), plan)
    }

    @Test
    fun `songs without lyrics that are not electronic are never swapped on a guess`() {
        val a = song("a", chorusConfidence = null, bpm = 126.0)
        val b = song("b", chorusConfidence = null, bpm = 128.0)

        val plan = TransitionPlanner.plan(a, b, warmedUp, DjMode.FIESTA)

        assertEquals(false, (plan.exit as? Exit.CutAt)?.swap ?: false)
    }

    @Test
    fun `no drop swap between tempos far apart, reggaeton at 95 into house at 128`() {
        val plan = TransitionPlanner.plan(song("a"), otherTempo, warmedUp, DjMode.HORA_LOCA)

        assertEquals(false, (plan.exit as? Exit.CutAt)?.swap ?: false)
    }

    @Test
    fun `a skipped first song still ends the warm-up, or a night of skipping would never be mixed`() {
        // Found on the phone: skipping through songs left every one of them on "Completa".
        val plan = TransitionPlanner.plan(song("a"), song("b"), past = emptyList(), mode = DjMode.FIESTA, heard = 1)

        assertEquals(true, (plan.exit as Exit.CutAt).swap)
    }

    @Test
    fun `the first songs of the night play whole as a warm-up`() {
        val plan = TransitionPlanner.plan(song("a"), song("b"), past = emptyList(), mode = DjMode.FIESTA)

        assertEquals(Exit.Full, plan.exit)
    }

    @Test
    fun `once warmed up a confident song is cut at its last natural exit`() {
        val plan = TransitionPlanner.plan(song("a"), noDrop, past = warmedUp, mode = DjMode.FIESTA)

        assertEquals(Exit.CutAt(180_000), plan.exit)
    }

    @Test
    fun `hora loca leaves at the first natural exit`() {
        val plan = TransitionPlanner.plan(song("a"), noDrop, past = warmedUp, mode = DjMode.HORA_LOCA)

        assertEquals(Exit.CutAt(120_000), plan.exit)
    }

    @Test
    fun `never two cuts in a row`() {
        val past = warmedUp + Transition(Exit.CutAt(120_000), Entry.FromStart)

        assertEquals(Exit.Full, TransitionPlanner.plan(song("a"), song("b"), past, DjMode.HORA_LOCA).exit)
    }

    @Test
    fun `short songs, weak lyrics and Completa all mean the song plays whole`() {
        assertEquals(Exit.Full, TransitionPlanner.plan(song("a", durationMs = 130_000), song("b"), warmedUp, DjMode.HORA_LOCA).exit)
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
        val plan = TransitionPlanner.plan(song("a", durationMs = 130_000), song("b", userDropMs = 52_000), warmedUp, DjMode.HORA_LOCA)

        assertEquals(Transition(Exit.Full, Entry.FromStart), plan)
    }

    @Test
    fun `drop entries are rationed by mode`() {
        val recentDrop = List(4) { full } + Transition(Exit.CutAt(180_000), Entry.AtDrop(40_000)) + full + full

        // Three transitions since the last drop: fine for Hora loca (every 2), too soon for Fiesta (every 4).
        assertEquals(Entry.FromStart, TransitionPlanner.plan(song("a"), otherTempo, recentDrop, DjMode.FIESTA).entry)
        assertEquals(Entry.AtDrop(40_000), TransitionPlanner.plan(song("a"), otherTempo, recentDrop, DjMode.HORA_LOCA).entry)
    }
}
