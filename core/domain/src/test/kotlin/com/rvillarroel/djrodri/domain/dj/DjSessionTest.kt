package com.rvillarroel.djrodri.domain.dj

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DjSessionTest {

    private fun party(size: Int = 12, mode: DjMode = DjMode.FIESTA) =
        DjSession((0 until size).map { song("s$it", artist = "A$it") }, mode, Random(42))

    @Test
    fun `the party starts with the first song from its beginning`() {
        val session = party()

        val command = session.start()!!

        assertEquals(PlayCommand(session.current!!.id, 0), command)
    }

    // ---- Properties over many random nights ---------------------------------------------------------------------

    @Test
    fun `over any night the DJ never cuts two songs in a row and stays within its budget`() {
        val cutsByMode = mutableMapOf<DjMode, Int>()
        for (mode in DjMode.entries) repeat(40) { seed ->
            val session = DjSession(randomPlaylist(Random(seed)), mode, Random(seed))
            val exits = mutableListOf<Exit>()
            session.start()
            while (session.current != null) {
                exits += session.plan!!.exit
                if (session.plan!!.exit is Exit.CutAt) session.onCutPointReached() else session.onTrackEnded()
            }

            exits.zipWithNext().forEach { (a, b) -> assertFalse(a is Exit.CutAt && b is Exit.CutAt, "$mode seed $seed") }
            val cutRatio = exits.count { it is Exit.CutAt }.toDouble() / exits.size
            assertTrue(cutRatio <= mode.maxCutRatio, "$mode seed $seed cut ratio $cutRatio")
            cutsByMode.merge(mode, exits.count { it is Exit.CutAt }, Int::plus)
        }
        // The DJ must actually cut, and cut more the wilder the mode.
        assertTrue(cutsByMode.getValue(DjMode.NATURAL) in 1 until cutsByMode.getValue(DjMode.FIESTA), "$cutsByMode")
        assertTrue(cutsByMode.getValue(DjMode.FIESTA) < cutsByMode.getValue(DjMode.HORA_LOCA), "$cutsByMode")
    }

    @Test
    fun `every drop entry lands exactly on the drop the next song has`() {
        var dropEntries = 0
        repeat(40) { seed ->
            val playlist = randomPlaylist(Random(seed))
            val byId = playlist.associateBy { it.id }
            val commands = DjSession(playlist, DjMode.HORA_LOCA, Random(seed)).playThrough()

            commands.filter { it.fromMs > 0 }.forEach { command ->
                dropEntries++
                val drop = CueEstimator.estimate(byId.getValue(command.trackId)).drop
                assertEquals(drop?.positionMs, command.fromMs, "seed $seed")
                assertTrue(drop!!.confidence >= TransitionPlanner.MIN_DROP_CONFIDENCE)
            }
        }
        // Guard against a vacuous pass: the random nights must actually contain drop entries.
        assertTrue(dropEntries > 20, "only $dropEntries drop entries in 40 nights")
    }

    @Test
    fun `every song of the playlist is heard once over a full night`() {
        val playlist = randomPlaylist(Random(9))

        val commands = DjSession(playlist, DjMode.FIESTA, Random(9)).playThrough()

        assertEquals(playlist.map { it.id }.sorted(), commands.map { it.trackId }.sorted())
    }

    // ---- Controls -----------------------------------------------------------------------------------------------

    @Test
    fun `skipping counts against the DJ, does not spend its cut budget, and ends the warm-up`() {
        val session = party()
        session.start()

        repeat(3) { session.skip() }

        assertEquals(3, session.stats.manualSkips)
        assertEquals(0, session.stats.cuts)
        // Three songs heard: the night is warm, so the DJ may mix the fourth instead of playing it whole by rule.
        assertTrue(session.plan!!.exit is Exit.CutAt)
    }

    @Test
    fun `previous goes back and the song you left becomes next again`() {
        val session = party()
        val first = session.start()!!.trackId
        val second = session.skip()!!.trackId

        val back = session.previous()!!

        assertEquals(PlayCommand(first, 0), back)
        assertEquals(second, session.upNext!!.id)
    }

    @Test
    fun `previous at the first song does nothing`() {
        val session = party()
        session.start()

        assertNull(session.previous())
    }

    @Test
    fun `Completa overrides a planned cut for the current song only`() {
        val session = party(mode = DjMode.HORA_LOCA)
        session.start()
        repeat(4) { session.onTrackEnded() }
        assertTrue(session.plan!!.exit is Exit.CutAt)

        session.keepFull()
        assertEquals(Exit.Full, session.plan!!.exit)

        session.onTrackEnded()
        assertTrue(session.plan!!.exit is Exit.CutAt)
    }

    @Test
    fun `a disliked song is skipped and never comes back, not even from another playlist`() {
        val session = party()
        session.start()
        val hated = session.current!!

        session.dislike()
        session.switchPlaylist(listOf(hated, song("new1", artist = "Z1"), song("new2", artist = "Z2")))

        assertNotEquals(hated.id, session.current!!.id)
        assertFalse(session.queue.any { it.id == hated.id })
        assertEquals(1, session.stats.dislikes)
    }

    @Test
    fun `switching playlist keeps the current song and drops songs already heard`() {
        val session = party()
        val first = session.start()!!.trackId
        session.onTrackEnded()
        val playing = session.current!!
        val heard = listOf(first, playing.id)

        session.switchPlaylist((0 until 12).map { song("s$it", artist = "A$it") } + song("fresh", artist = "F"))

        assertEquals(playing, session.current)
        assertTrue(session.queue.none { it.id in heard })
        assertTrue(session.queue.any { it.id == "fresh" })
    }

    @Test
    fun `more energy makes what comes next louder than less energy would`() {
        val tracks = (0 until 20).map { song("e$it", energy = it / 19.0, artist = "A$it") }

        fun nextEnergyAfter(adjust: DjSession.() -> Unit): Double {
            val session = DjSession(tracks, DjMode.FIESTA, Random(5))
            session.start()
            session.adjust()
            session.adjust()
            return session.queue.take(3).mapNotNull { it.energy }.average()
        }

        assertTrue(nextEnergyAfter { moreEnergy() } > nextEnergyAfter { lessEnergy() })
    }

    @Test
    fun `when the playlist runs out the party ends cleanly`() {
        val session = party(size = 2)
        session.start()
        session.onTrackEnded()

        assertNull(session.onTrackEnded())
        assertNull(session.current)
        assertNull(session.plan)
    }
}
