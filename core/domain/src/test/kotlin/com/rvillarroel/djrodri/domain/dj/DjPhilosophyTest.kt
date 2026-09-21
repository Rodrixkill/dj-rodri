package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.lyrics.LrcLine
import com.rvillarroel.djrodri.domain.lyrics.TimeRange
import com.rvillarroel.djrodri.domain.lyrics.VocalAnalyzer
import com.rvillarroel.djrodri.domain.lyrics.VocalProfile
import com.rvillarroel.djrodri.domain.music.CamelotKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/filosofia-dj.md turned into checks: how a real DJ would move between these two songs. */
class DjPhilosophyTest {

    private val full = Transition(Exit.Full, Entry.FromStart)
    private val warmedUp = List(5) { full }

    private fun styled(id: String, vararg genres: String, bpm: Double? = 95.0, energy: Double? = 0.6, vocals: VocalProfile? = null) =
        song(id, bpm = bpm, energy = energy).copy(genres = genres.toSet(), vocals = vocals)

    private val heavyVocals = VocalProfile(0.8, emptyList())

    // ---- Families ------------------------------------------------------------------------------------------------

    @Test
    fun `the most specific style wins over the family it belongs to`() {
        assertEquals(StyleFamily.REGGAETON, StyleFamily.of(styled("a", "reggaeton", "latin")))
        assertEquals(StyleFamily.ELECTRONIC, StyleFamily.of(styled("a", "house", "edm", "electronic")))
        assertEquals(StyleFamily.BASS, StyleFamily.of(styled("a", "dubstep", "edm", "electronic")))
        assertEquals(StyleFamily.TROPICAL, StyleFamily.of(styled("a", "salsa", "latin")))
        assertEquals(StyleFamily.AFRO_CARIBBEAN, StyleFamily.of(styled("a", "amapiano", "afrobeats")))
        assertEquals(StyleFamily.ROCK, StyleFamily.of(styled("a", "indie", "rock")))
        assertEquals(StyleFamily.UNKNOWN, StyleFamily.of(styled("a")))
    }

    // ---- Compatibility: BPM is never the only rule ---------------------------------------------------------------

    @Test
    fun `house into house one BPM apart is a smooth change`() {
        val match = Compatibility.match(styled("a", "house", bpm = 124.0), styled("b", "house", bpm = 125.0))

        assertEquals(Difficulty.SMOOTH, match.difficulty)
    }

    @Test
    fun `reggaeton at 90 and drum and bass at 180 share a pulse, so tempo alone does not make it hard`() {
        val match = Compatibility.match(styled("a", "reggaeton", bpm = 90.0), styled("b", "drum and bass", bpm = 180.0))

        assertEquals(1.0, match.tempo)
        assertTrue(match.reasons.contains("mismo tempo"))
    }

    @Test
    fun `reggaeton into house is creative, close enough in style, far in tempo`() {
        val match = Compatibility.match(styled("a", "reggaeton", bpm = 92.0), styled("b", "house", bpm = 126.0))

        assertEquals(Difficulty.CREATIVE, match.difficulty)
        assertTrue(match.reasons.any { it.startsWith("tempos muy distintos") }, "${match.reasons}")
    }

    @Test
    fun `rock into reggaeton at a far tempo is challenging, and it says why`() {
        val match = Compatibility.match(styled("a", "rock", bpm = 140.0), styled("b", "reggaeton", bpm = 92.0))

        assertEquals(Difficulty.CHALLENGING, match.difficulty)
        assertTrue(match.explain().startsWith("Difícil · "), match.explain())
        assertTrue(match.reasons.any { it.contains("cambio grande de estilo") }, "${match.reasons}")
    }

    @Test
    fun `the same tempo does not make two styles a good match`() {
        val sameStyle = Compatibility.match(styled("a", "reggaeton"), styled("b", "hip hop"))
        val farStyle = Compatibility.match(styled("a", "reggaeton"), styled("b", "rock"))

        assertTrue(sameStyle.score > farStyle.score, "${sameStyle.score} vs ${farStyle.score}")
    }

    @Test
    fun `energy, key and mood are part of the reasons`() {
        val a = song("a", energy = 0.5, key = CamelotKey(8, isMinor = true)).copy(valence = 0.8)
        val b = song("b", energy = 0.8, key = CamelotKey(9, isMinor = true)).copy(valence = 0.2)

        val reasons = Compatibility.match(a, b).reasons

        assertTrue(reasons.containsAll(listOf("sube la energía", "tonos compatibles", "cambia el ánimo (alegre → oscuro)")), "$reasons")
    }

    // ---- The move follows the style ------------------------------------------------------------------------------

    @Test
    fun `house into house is a long blend on Spotify's fade`() {
        val plan = TransitionPlanner.plan(styled("a", "house"), styled("b", "house", bpm = 140.0), warmedUp, DjMode.FIESTA)

        assertEquals(true, (plan.exit as Exit.CutAt).blend)
    }

    @Test
    fun `two songs full of vocals are never blended, even in house`() {
        val plan = TransitionPlanner.plan(
            styled("a", "house", vocals = heavyVocals),
            styled("b", "house", bpm = 140.0, vocals = heavyVocals),
            warmedUp,
            DjMode.FIESTA,
        )

        assertEquals(false, (plan.exit as Exit.CutAt).blend)
    }

    @Test
    fun `reggaeton cuts are dry, on the end of a phrase`() {
        val plan = TransitionPlanner.plan(styled("a", "reggaeton"), styled("b", "reggaeton", bpm = 130.0), warmedUp, DjMode.FIESTA)

        assertEquals(Exit.CutAt(180_000), plan.exit)
    }

    @Test
    fun `reggaeton into hip hop can be a drop swap`() {
        val plan = TransitionPlanner.plan(styled("a", "reggaeton"), styled("b", "hip hop", bpm = 96.0), warmedUp, DjMode.FIESTA)

        assertEquals(true, (plan.exit as Exit.CutAt).swap)
    }

    @Test
    fun `salsa and rock are never drop swapped`() {
        val salsa = TransitionPlanner.plan(styled("a", "salsa"), styled("b", "salsa"), warmedUp, DjMode.HORA_LOCA)
        val rock = TransitionPlanner.plan(styled("a", "rock"), styled("b", "rock"), warmedUp, DjMode.HORA_LOCA)

        assertFalse((salsa.exit as? Exit.CutAt)?.swap ?: false)
        assertFalse((rock.exit as? Exit.CutAt)?.swap ?: false)
    }

    @Test
    fun `rock is cut half as often, a song, not a loop`() {
        // Two cuts in the last five: fine for Fiesta (50 %), over budget for rock (25 %).
        val cut = Transition(Exit.CutAt(180_000), Entry.FromStart)
        val past = listOf(cut, full, cut, full, full)

        val reggaeton = TransitionPlanner.plan(styled("a", "reggaeton"), styled("b", "reggaeton", bpm = 130.0), past, DjMode.FIESTA)
        val rock = TransitionPlanner.plan(styled("a", "rock"), styled("b", "rock"), past, DjMode.FIESTA)

        assertTrue(reggaeton.exit is Exit.CutAt)
        assertEquals(Exit.Full, rock.exit)
    }

    @Test
    fun `a change always comes with its reason`() {
        val note = TransitionPlanner.explain(styled("a", "reggaeton"), styled("b", "hip hop", bpm = 96.0))

        assertNotNull(note)
        assertTrue(note!!.contains("reggaetón → hip hop, estilos cercanos"), note)
    }

    // ---- Vocals from the lyrics ----------------------------------------------------------------------------------

    @Test
    fun `lyrics tell where the song is sung and where it breathes`() {
        // Lines from 0:10 to 0:30 (the last one sung until 0:34), a beat break, lines again from 1:00 to 1:20, a long outro.
        val lines = (10..30 step 4).map { LrcLine(it * 1_000L, "linea $it") } + (60..80 step 4).map { LrcLine(it * 1_000L, "linea $it") }

        val vocals = VocalAnalyzer.analyze(lines, durationMs = 180_000)!!

        assertEquals(
            listOf(TimeRange(0, 10_000), TimeRange(34_000, 60_000), TimeRange(84_000, 180_000)),
            vocals.instrumentals,
        )
        assertFalse(vocals.heavy)
        assertEquals(TimeRange(34_000, 60_000), vocals.instrumentalAt(45_000))
    }

    @Test
    fun `after the hook is heard twice, an instrumental stretch becomes a clean way out`() {
        val vocals = VocalProfile(0.4, listOf(TimeRange(130_000, 150_000)))

        val cuts = CueEstimator.estimate(song("a").copy(vocals = vocals)).cuts.map { it.positionMs }

        assertTrue(130_000L in cuts, "$cuts")
    }
}
