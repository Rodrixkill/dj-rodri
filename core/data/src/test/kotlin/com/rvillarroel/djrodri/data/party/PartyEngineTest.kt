package com.rvillarroel.djrodri.data.party

import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.Entry
import com.rvillarroel.djrodri.domain.dj.Exit
import com.rvillarroel.djrodri.domain.dj.SessionStats
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.lyrics.ChorusAnalysis
import com.rvillarroel.djrodri.domain.lyrics.TimeRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PartyEngineTest {

    /** Spotify as the engine sees it: plays instantly, and the clock is the test's virtual time. */
    private class FakePlayer(private val clock: () -> Long, private val durations: Map<String, Long>) : PlayerPort {
        override val state = MutableStateFlow<PlayerState?>(null)
        val calls = mutableListOf<String>()

        override fun play(uri: String) {
            calls += "play $uri"
            state.value = PlayerState(uri, durations.getValue(uri), 0, isPaused = false, capturedAtMs = clock())
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek $positionMs"
            state.value = state.value!!.copy(positionMs = positionMs, capturedAtMs = clock())
        }

        override fun pause() {
            calls += "pause"
            val s = state.value ?: return
            state.value = s.copy(positionMs = s.positionAt(clock()), isPaused = true, capturedAtMs = clock())
        }

        override fun resume() {
            calls += "resume"
            state.value = state.value?.copy(isPaused = false, capturedAtMs = clock())
        }

        /** Someone picked a song in the Spotify app. */
        fun externalPlay(uri: String) {
            state.value = PlayerState(uri, 200_000, 0, isPaused = false, capturedAtMs = clock())
        }
    }

    private class MemorySnapshots : PartySnapshotStore {
        var saved: PartySnapshot? = null
        var cleared = false
        override suspend fun save(snapshot: PartySnapshot) { saved = snapshot; cleared = false }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null; cleared = true }
    }

    private class MemoryRecorder : PartyRecorder {
        val events = mutableListOf<Pair<String, PartyEventKind>>()
        val drops = mutableMapOf<String, Long>()
        var ended: SessionStats? = null
        override suspend fun sessionStarted(playlistId: String, mode: DjMode, atMs: Long) = 7L
        override suspend fun event(sessionId: Long, trackId: String, kind: PartyEventKind, positionMs: Long, atMs: Long) {
            events += trackId to kind
        }
        override suspend fun statsChanged(sessionId: Long, stats: SessionStats, endedAtMs: Long?) {
            if (endedAtMs != null) ended = stats
        }
        override suspend fun dropMarked(trackId: String, positionMs: Long, atMs: Long) {
            drops[trackId] = positionMs
        }
    }

    private val durationMs = 230_000L

    private fun partyTrack(id: String) = PartyTrack(
        SourceTrack(id, "spotify:track:$id", "Tema $id", "Artista $id", "Album", durationMs, null),
        TrackProfile(
            id = id,
            artist = "Artista $id",
            durationMs = durationMs,
            energy = 0.6,
            chorus = ChorusAnalysis(listOf(TimeRange(40_000, 60_000), TimeRange(100_000, 120_000), TimeRange(160_000, 180_000)), 4, 0.9),
        ),
    )

    private class Harness(scope: TestScope, size: Int, factory: (String) -> PartyTrack) {
        val playlist = (1..size).map { factory("t$it") }
        val player = FakePlayer({ scope.testScheduler.currentTime }, playlist.associate { it.source.uri to it.source.durationMs })
        val recorder = MemoryRecorder()
        val snapshots = MemorySnapshots()
        val engine = PartyEngine(player, recorder, snapshots, scope.backgroundScope, { scope.testScheduler.currentTime }, Random(1))
        val currentUri get() = engine.state.value.current?.uri
    }

    private fun TestScope.harness(size: Int = 6) = Harness(this, size, ::partyTrack)

    @Test
    fun `the party starts by playing its first song from the beginning`() = runTest {
        val h = harness()

        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))

        assertEquals(listOf("play ${h.currentUri}"), h.player.calls)
        assertTrue(h.engine.state.value.active)
    }

    @Test
    fun `a whole song hands over to the next one right at its end`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        val first = h.currentUri

        advanceTimeBy(durationMs - 1_000)
        runCurrent()
        assertEquals(first, h.currentUri)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(h.currentUri != first)
        assertEquals("play ${h.currentUri}", h.player.calls.last())
        assertEquals(PartyEventKind.FULL, h.recorder.events.last().second)
    }

    @Test
    fun `in hora loca the second song is cut after its second chorus and the third enters on its drop`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA))

        advanceTimeBy(durationMs) // first song: warm-up, plays whole
        runCurrent()
        val second = h.engine.state.value
        assertEquals(Exit.CutAt(120_000), second.plan!!.exit)
        assertEquals(Entry.AtDrop(40_000), second.plan!!.entry)

        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(listOf("play ${h.currentUri}", "seek 40000"), h.player.calls.takeLast(2))
        assertEquals(PartyEventKind.DROP_ENTRY, h.recorder.events.last().second)
    }

    @Test
    fun `with pause enabled the jump into a drop is never heard`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA, dropWithPause = true))

        advanceTimeBy(durationMs + 120_000)
        runCurrent()

        assertEquals(listOf("pause", "play ${h.currentUri}", "seek 40000", "resume"), h.player.calls.takeLast(4))
    }

    @Test
    fun `when you pick a song in the Spotify app the DJ steps back instead of fighting you`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA))
        advanceTimeBy(10_000)
        runCurrent()
        val callsBefore = h.player.calls.size

        h.player.externalPlay("spotify:track:somethingElse")
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(callsBefore, h.player.calls.size)
        assertEquals("spotify:track:somethingElse", h.engine.state.value.externalTrackUri)
        assertTrue(h.recorder.events.any { it.second == PartyEventKind.EXTERNAL_CHANGE })
    }

    @Test
    fun `pressing next takes control back and counts as your skip`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        h.player.externalPlay("spotify:track:somethingElse")
        advanceTimeBy(5_000)
        runCurrent()

        h.engine.next()
        runCurrent()

        assertEquals("play ${h.currentUri}", h.player.calls.last())
        assertEquals(null, h.engine.state.value.externalTrackUri)
        assertEquals(1, h.engine.state.value.stats.manualSkips)
    }

    @Test
    fun `tapping Drop remembers where the drop really is`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        val songId = h.engine.state.value.current!!.id

        advanceTimeBy(52_300)
        h.engine.markDrop()
        runCurrent()

        assertEquals(52_300L, h.recorder.drops[songId])
    }

    @Test
    fun `if the app slept through the end and Spotify stopped, the party carries on`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        val first = h.currentUri
        h.player.state.value = h.player.state.value!!.copy(positionMs = durationMs, isPaused = true)

        advanceTimeBy(200)
        runCurrent()

        assertTrue(h.currentUri != first)
    }

    @Test
    fun `a killed party is saved with the song, the second and the budget it had`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA))
        advanceTimeBy(durationMs + 30_000)
        runCurrent()

        val snapshot = h.snapshots.saved!!

        assertEquals("pl", snapshot.playlistId)
        assertEquals(h.engine.state.value.current!!.id, snapshot.currentTrackId)
        assertEquals(30_000L, snapshot.positionMs)
        assertEquals(1, snapshot.transitions.size)
        assertEquals(h.playlist.size - 2, snapshot.upcomingIds.size)
    }

    @Test
    fun `carrying on after a kill puts the same song back at the same second`() = runTest {
        val first = harness()
        first.engine.start("pl", first.playlist, PartySettings(DjMode.HORA_LOCA))
        advanceTimeBy(durationMs + 45_000)
        runCurrent()
        val snapshot = first.snapshots.saved!!
        val songWhenKilled = first.engine.state.value.current!!.id
        val whatWasComing = first.engine.state.value.next!!.id

        // A new process: new engine, nothing playing, same playlist and the snapshot from disk.
        val second = harness()
        val resumed = second.engine.resume(snapshot, second.playlist)
        runCurrent()

        assertTrue(resumed)
        assertEquals(songWhenKilled, second.engine.state.value.current!!.id)
        assertEquals(whatWasComing, second.engine.state.value.next!!.id)
        // The snapshot is saved every few seconds, so it carries on from the last second saved, not from the kill.
        assertEquals(listOf("play spotify:track:" + songWhenKilled, "seek " + snapshot.positionMs), second.player.calls)
        assertEquals(first.engine.state.value.stats, second.engine.state.value.stats)
        assertEquals(first.engine.state.value.plan, second.engine.state.value.plan)
    }

    @Test
    fun `carrying on is refused when the song is no longer in the playlist`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        runCurrent()
        val snapshot = h.snapshots.saved!!.copy(currentTrackId = "borrada")

        assertFalse(harness().engine.resume(snapshot, h.playlist))
    }

    @Test
    fun `ending the party on purpose leaves nothing to carry on`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        runCurrent()

        h.engine.stop()
        runCurrent()

        assertTrue(h.snapshots.cleared)
        assertEquals(null, h.snapshots.saved)
    }

    @Test
    fun `when the last song ends the party is over and its stats are saved`() = runTest {
        val h = harness(size = 2)
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))

        advanceTimeBy(durationMs * 2 + 1_000)
        runCurrent()

        assertFalse(h.engine.state.value.active)
        assertNotNull(h.recorder.ended)
        assertEquals(2, h.recorder.ended!!.fullPlays)
    }
}
