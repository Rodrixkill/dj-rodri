package com.rvillarroel.djrodri.data.party

import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.Entry
import com.rvillarroel.djrodri.domain.dj.Exit
import com.rvillarroel.djrodri.domain.dj.SessionStats
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.lyrics.ChorusAnalysis
import com.rvillarroel.djrodri.domain.lyrics.TimeRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

private const val SPOTIFY_OWN_PICK = "spotify:track:spotifyPick"

class PartyEngineTest {

    /**
     * Spotify as the engine sees it: plays instantly, and the clock is the test's virtual time. With [crossfade] set
     * it fades like the real app: in a song's last seconds it moves on to the queued song, or to its own pick.
     */
    private class FakePlayer(
        private val clock: () -> Long,
        private val durations: Map<String, Long>,
        scope: CoroutineScope,
    ) : PlayerPort {
        override val state = MutableStateFlow<PlayerState?>(null)
        val calls = mutableListOf<String>()
        var crossfade: Long? = null
        /** False plays Spotify's own next song even when something was queued. */
        var honorsQueue = true
        /** Like the real one: first in, first out, and nothing takes a song out except playing it. */
        val queued = ArrayDeque<String>()

        init {
            scope.launch {
                while (true) {
                    delay(50)
                    fadeIfDue()
                }
            }
        }

        private fun fadeIfDue() {
            val fade = crossfade ?: return
            val s = state.value?.takeIf { !it.isPaused } ?: return
            if (s.positionAt(clock()) < s.durationMs - fade) return
            val next = if (honorsQueue) queued.removeFirstOrNull() ?: SPOTIFY_OWN_PICK else SPOTIFY_OWN_PICK
            calls += "fade $next"
            state.value = PlayerState(next, durations[next] ?: 200_000, 0, isPaused = false, capturedAtMs = clock())
        }

        override fun queue(uri: String) {
            calls += "queue $uri"
            queued.addLast(uri)
        }

        override fun skipNext() {
            val next = queued.removeFirstOrNull() ?: SPOTIFY_OWN_PICK
            calls += "skipNext → $next"
            state.value = PlayerState(next, durations[next] ?: 200_000, 0, isPaused = false, capturedAtMs = clock())
        }

        override suspend fun crossfadeMs() = crossfade

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
        val player = FakePlayer({ scope.testScheduler.currentTime }, playlist.associate { it.source.uri to it.source.durationMs }, scope.backgroundScope)
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
    fun `when Spotify comes back after being closed, the same song plays again from where it stopped`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        val song = h.currentUri
        advanceTimeBy(22_000)
        h.engine.togglePause()
        runCurrent()

        h.engine.reconnected()
        runCurrent()

        assertEquals(listOf("play $song", "seek 22000"), h.player.calls.takeLast(2))
        assertFalse(h.player.state.value!!.isPaused)
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
    fun `in hora loca the second song is swapped on its second drop for the drop of the third`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA))

        advanceTimeBy(durationMs) // first song: warm-up, plays whole
        runCurrent()
        val second = h.engine.state.value
        assertEquals(Exit.CutAt(100_000, swap = true), second.plan!!.exit)
        assertEquals(Entry.AtDrop(40_000), second.plan!!.entry)

        advanceTimeBy(100_000)
        runCurrent()

        assertEquals(listOf("play ${h.currentUri}", "seek 40000"), h.player.calls.takeLast(2))
        assertEquals(PartyEventKind.DROP_SWAP, h.recorder.events.last().second)
    }

    @Test
    fun `the DJ's drop swap is a dry cut even with Spotify's crossfade on, because the hit is the point`() = runTest {
        val h = harness()
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA))
        advanceTimeBy(durationMs - fade + 500)
        runCurrent()
        val callsBefore = h.player.calls.size

        advanceTimeBy(100_000)
        runCurrent()

        assertEquals(listOf("play ${h.currentUri}", "seek 40000"), h.player.calls.drop(callsBefore))
    }

    // ---- Your own moves -----------------------------------------------------------------------------------------

    @Test
    fun `your drop swap waits for the next drop of the song and lands the next song on its drop`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        advanceTimeBy(10_000)
        runCurrent()

        h.engine.dropSwap()
        runCurrent()
        assertEquals(40_000L, h.engine.state.value.swapAtMs) // the first chorus starts at 0:40
        val first = h.currentUri

        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(h.currentUri != first)
        assertEquals(listOf("play ${h.currentUri}", "seek 40000"), h.player.calls.takeLast(2))
        assertEquals(PartyEventKind.DROP_SWAP, h.recorder.events.last().second)
        // A move of yours, not a rejection of the song: it is not counted as a skip.
        assertEquals(0, h.engine.state.value.stats.manualSkips)
        assertEquals(null, h.engine.state.value.swapAtMs)
    }

    @Test
    fun `with no drop left in the song, your drop swap waits for the next song instead of jumping now`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        advanceTimeBy(190_000) // past the last chorus
        runCurrent()
        val first = h.currentUri
        val callsBefore = h.player.calls.size

        h.engine.dropSwap()
        runCurrent()

        // Found on the phone: swapping on the spot sounded like a glitch. Now nothing happens yet, and it says so.
        assertEquals(callsBefore, h.player.calls.size)
        assertTrue(h.engine.state.value.swapNextSong)
        assertNotNull(h.engine.state.value.notice)
        assertEquals(first, h.currentUri)

        advanceTimeBy(durationMs - 190_000) // the song ends; the next one starts from the top
        runCurrent()
        assertEquals(40_000L, h.engine.state.value.swapAtMs) // armed on the new song's first drop
        assertEquals(false, h.engine.state.value.swapNextSong)

        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(listOf("play ${h.currentUri}", "seek 40000"), h.player.calls.takeLast(2))
        assertEquals(PartyEventKind.DROP_SWAP, h.recorder.events.last().second)
    }

    @Test
    fun `tapping drop swap again cancels it`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        advanceTimeBy(10_000)
        runCurrent()

        h.engine.dropSwap()
        runCurrent()
        h.engine.dropSwap()
        runCurrent()

        assertEquals(null, h.engine.state.value.swapAtMs)
    }

    @Test
    fun `fade waits for the end of the chorus, never cuts a line in half`() = runTest {
        val h = harness()
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        advanceTimeBy(30_000)
        runCurrent()
        val callsBefore = h.player.calls.size

        h.engine.fadeNow()
        runCurrent()
        // Found on the phone: fading on the spot felt like skipping. The first chorus ends at 1:00, so the fade waits.
        assertEquals(60_000L, h.engine.state.value.fadeAtMs)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(callsBefore, h.player.calls.size)

        advanceTimeBy(10_000 + 3_000) // past 1:00: queued at 0:56, sent to the fade start at 1:00, Spotify fades
        runCurrent()

        val second = h.currentUri
        assertEquals(listOf("queue $second", "seek ${durationMs - fade - 2_000}", "fade $second"), h.player.calls.drop(callsBefore))
        assertEquals(PartyEventKind.FADE_NOW, h.recorder.events.last().second)
        assertEquals(null, h.engine.state.value.fadeAtMs)
    }

    @Test
    fun `dragging the bar moves the song, and past the DJ's change the song is left whole`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA))
        advanceTimeBy(durationMs) // warm-up song ends; the second is planned to swap at 1:40
        runCurrent()
        assertEquals(Exit.CutAt(100_000, swap = true), h.engine.state.value.plan!!.exit)
        val song = h.currentUri

        h.engine.seek(150_000)
        runCurrent()

        assertEquals("seek 150000", h.player.calls.last())
        assertEquals(Exit.Full, h.engine.state.value.plan!!.exit)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(song, h.currentUri) // no instant cut after landing past the planned point
    }

    @Test
    fun `once more sends the song back to the drop the crowd just heard`() = runTest {
        val h = harness()
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        advanceTimeBy(70_000) // the first chorus (drop) ran from 0:40 to 1:00
        runCurrent()
        val song = h.currentUri

        h.engine.pullUp()
        runCurrent()

        assertEquals("seek 40000", h.player.calls.last())
        assertEquals(song, h.currentUri)
        assertEquals(PartyEventKind.PULL_UP, h.recorder.events.last().second)
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

    // ---- Spotify's own crossfade ------------------------------------------------------------------------------

    private val fade = 12_000L

    @Test
    fun `with crossfade on, a whole song fades into the song the DJ queued, without the DJ touching play`() = runTest {
        val h = harness()
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        val first = h.currentUri

        advanceTimeBy(durationMs - fade + 500)
        runCurrent()

        val second = h.currentUri
        assertTrue(second != first)
        assertEquals(listOf("play $first", "queue $second", "fade $second"), h.player.calls)
        assertEquals(PartyEventKind.FULL, h.recorder.events.last().second)
        // Spotify changing song here is the DJ's own doing, not someone in the Spotify app.
        assertEquals(null, h.engine.state.value.externalTrackUri)
    }

    @Test
    fun `a cut sends the song to its fade, and the next one lands on its drop mid-fade`() = runTest {
        // Songs whose second chorus comes too early to swap on: the plan is a plain cut after it, into a drop.
        val early = ChorusAnalysis(
            listOf(TimeRange(20_000, 35_000), TimeRange(45_000, 60_000), TimeRange(100_000, 120_000), TimeRange(160_000, 180_000)),
            4,
            0.9,
        )
        // House: a style made for long blends, so the cut is Spotify's fade.
        val house = setOf("house", "edm", "electronic")
        val h = Harness(this, 6) { id -> partyTrack(id).let { it.copy(profile = it.profile.copy(chorus = early, genres = house)) } }
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA))
        advanceTimeBy(durationMs - fade + 500) // first song: warm-up, fades out whole
        runCurrent()
        assertEquals(Exit.CutAt(60_000, blend = true), h.engine.state.value.plan!!.exit)
        assertEquals(Entry.AtDrop(20_000), h.engine.state.value.plan!!.entry)
        val callsBefore = h.player.calls.size

        advanceTimeBy(65_000)
        runCurrent()

        val third = h.currentUri
        val fadeStart = durationMs - fade - 2_000
        assertEquals(listOf("queue $third", "seek $fadeStart", "fade $third", "seek 20000"), h.player.calls.drop(callsBefore))
        assertEquals(PartyEventKind.DROP_ENTRY, h.recorder.events.last().second)
        assertEquals(1, h.engine.state.value.stats.dropEntries)
    }

    @Test
    fun `a reggaeton cut stays dry on the phrase even with crossfade on, no fade over the vocals`() = runTest {
        val early = ChorusAnalysis(
            listOf(TimeRange(20_000, 35_000), TimeRange(45_000, 60_000), TimeRange(100_000, 120_000), TimeRange(160_000, 180_000)),
            4,
            0.9,
        )
        val reggaeton = setOf("reggaeton", "latin")
        val h = Harness(this, 6) { id -> partyTrack(id).let { it.copy(profile = it.profile.copy(chorus = early, genres = reggaeton)) } }
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.HORA_LOCA))
        advanceTimeBy(durationMs - fade + 500)
        runCurrent()
        assertEquals(Exit.CutAt(60_000), h.engine.state.value.plan!!.exit)
        val callsBefore = h.player.calls.size

        advanceTimeBy(65_000)
        runCurrent()

        assertEquals(listOf("play ${h.currentUri}", "seek 20000"), h.player.calls.drop(callsBefore))
    }

    @Test
    fun `if Spotify fades into its own pick instead of ours, the DJ switches to its song the clean way`() = runTest {
        val h = harness()
        h.player.crossfade = fade
        h.player.honorsQueue = false
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))

        advanceTimeBy(durationMs - fade + 500)
        runCurrent()

        val second = h.currentUri
        // Ours is still first in Spotify's queue: moving on uses it up instead of leaving it to play again later.
        assertEquals(listOf("fade $SPOTIFY_OWN_PICK", "skipNext → $second"), h.player.calls.takeLast(2))
        assertTrue(h.player.queued.isEmpty())
        assertEquals(null, h.engine.state.value.externalTrackUri)
    }

    @Test
    fun `with the fade switched off in Ajustes, changes stay clean cuts even if Spotify has crossfade on`() = runTest {
        val h = harness()
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA, useSpotifyFade = false))

        advanceTimeBy(durationMs - fade + 500)
        runCurrent()

        assertTrue(h.player.calls.none { it.startsWith("queue") })
    }

    @Test
    fun `pressing next while a fade is being prepared wins over the fade`() = runTest {
        val h = harness()
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        advanceTimeBy(durationMs - fade - 1_000) // the next song is already queued, the fade has not started
        runCurrent()
        val skipped = h.player.calls.last().removePrefix("queue ")
        assertTrue(h.player.calls.last().startsWith("queue"))

        h.engine.next()
        runCurrent()

        // The song you skipped was already in Spotify's queue: it is used up while paused, never heard later.
        assertEquals(listOf("pause", "skipNext → $skipped", "play ${h.currentUri}", "resume"), h.player.calls.takeLast(4))
        assertTrue(h.player.queued.isEmpty())
        assertEquals(1, h.engine.state.value.stats.manualSkips)
    }

    @Test
    fun `after a skip mid-fade, the next fades still go to the DJ's songs and not to a leftover`() = runTest {
        // Songs with no lyrics play whole, so every change is a fade.
        val h = Harness(this, 6) { id -> partyTrack(id).let { it.copy(profile = it.profile.copy(chorus = null)) } }
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        advanceTimeBy(durationMs - fade - 1_000)
        runCurrent()
        h.engine.next()
        runCurrent()
        val afterSkip = h.currentUri

        advanceTimeBy(durationMs - fade + 500)
        runCurrent()

        val next = h.currentUri
        assertTrue(next != afterSkip)
        assertEquals(listOf("queue $next", "fade $next"), h.player.calls.takeLast(2))
    }

    @Test
    fun `a leftover in Spotify's queue that is still to come is moved up, so the fade uses it up`() = runTest {
        val h = harness()
        h.player.crossfade = fade
        h.engine.start("pl", h.playlist, PartySettings(DjMode.FIESTA))
        runCurrent()
        // Something queued earlier and not yet heard, which the set would only play later.
        val later = h.engine.profiles.map { "spotify:track:${it.id}" }.last { it != h.currentUri && it != h.engine.state.value.next?.uri }
        h.player.queue(later)
        h.engine.javaClass.getDeclaredField("queued").apply { isAccessible = true }.let {
            @Suppress("UNCHECKED_CAST")
            (it.get(h.engine) as ArrayDeque<String>).addLast(later)
        }

        advanceTimeBy(durationMs - fade + 500)
        runCurrent()

        assertEquals(later, h.currentUri)
        assertEquals("fade $later", h.player.calls.last())
        assertEquals(1, h.player.calls.count { it == "queue $later" })
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
