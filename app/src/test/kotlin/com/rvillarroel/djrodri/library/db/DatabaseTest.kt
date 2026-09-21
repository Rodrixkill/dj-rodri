package com.rvillarroel.djrodri.library.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rvillarroel.djrodri.data.analysis.LyricsStatus
import com.rvillarroel.djrodri.data.analysis.TrackAnalysis
import com.rvillarroel.djrodri.data.party.PartyEventKind
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.SessionStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DatabaseTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DjDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val library = db.library()
    private val store = RoomAnalysisStore(db.analysis())

    @After
    fun close() = db.close()

    private fun track(id: String) = TrackEntity(id, "spotify:track:$id", "Tema $id", "Artista", "Album", 200_000, null)

    private fun analysis(id: String, status: LyricsStatus) = TrackAnalysis(
        trackId = id, lyricsStatus = status, syncedLrc = "[00:01.00] hola", featuresFound = true,
        genres = setOf("house", "edm"), artistMbid = "mbid-$id", tempo = 96.0,
        key = 9, mode = 0, energy = 0.8, danceability = 0.7, valence = 0.5, analyzerVersion = 2, analyzedAtMs = 5,
    )

    @Test
    fun `reloading a playlist replaces its songs but keeps songs shared with other playlists`() = runTest {
        library.upsertPlaylists(listOf(PlaylistEntity("p1", "Perreo", "me", 3, null, 1, null), PlaylistEntity("p2", "Cumbia", "me", 1, null, 2, null)))
        library.replaceTracks("p1", listOf(track("a"), track("b"), track("c")), atMs = 1)
        library.replaceTracks("p2", listOf(track("a")), atMs = 1)

        library.replaceTracks("p1", listOf(track("c"), track("d")), atMs = 2)

        assertEquals(listOf("c", "d"), library.tracksOf("p1").map { it.id })
        assertEquals(listOf("a"), library.tracksOf("p2").map { it.id })
        assertEquals(2L, library.playlist("p1")!!.tracksSyncedAtMs)
    }

    @Test
    fun `a playlist counts as analyzed only for songs whose analysis did not fail`() = runTest {
        library.upsertPlaylists(listOf(PlaylistEntity("p1", "Perreo", "me", 3, null, 1, null)))
        library.replaceTracks("p1", listOf(track("a"), track("b"), track("c")), atMs = 1)
        store.save(analysis("a", LyricsStatus.SYNCED))
        store.save(analysis("b", LyricsStatus.ERROR))

        val row = library.playlists().first().single()

        assertEquals(3, row.cachedTracks)
        assertEquals(1, row.analyzedTracks)
    }

    @Test
    fun `an analysis survives the round trip through SQLite intact`() = runTest {
        val original = analysis("a", LyricsStatus.INSTRUMENTAL)
        store.save(original)

        assertEquals(original, store.analyses(listOf("a", "missing"))["a"])
        assertNull(store.analyses(listOf("missing"))["missing"])
    }

    @Test
    fun `thousands of songs are read back despite SQLite's bound variable limit`() = runTest {
        val ids = (1..2_500).map { "t$it" }
        ids.forEach { store.save(analysis(it, LyricsStatus.NOT_FOUND)) }

        assertEquals(2_500, store.analyses(ids).size)
    }

    @Test
    fun `a party keeps its events, its final stats and your drop marks`() = runTest {
        val recorder = RoomPartyRecorder(db.party(), db.analysis())
        val sessionId = recorder.sessionStarted("p1", DjMode.HORA_LOCA, atMs = 100)
        recorder.event(sessionId, "a", PartyEventKind.DROP_ENTRY, positionMs = 120_000, atMs = 200)
        recorder.statsChanged(sessionId, SessionStats(fullPlays = 3, cuts = 2, dropEntries = 1, manualSkips = 1), endedAtMs = null)
        recorder.statsChanged(sessionId, SessionStats(fullPlays = 4, cuts = 2, dropEntries = 1, manualSkips = 1), endedAtMs = 900)
        recorder.dropMarked("a", positionMs = 52_300, atMs = 300)

        val session = db.party().recentSessions().first().single()
        assertEquals(4, session.fullPlays)
        assertEquals(900L, session.endedAtMs)
        assertEquals("HORA_LOCA", session.mode)
        assertEquals(mapOf("a" to 52_300L), store.dropMarks(listOf("a", "b")))
    }

    @Test
    fun `a drop corrected in the editor replaces the old mark, and can go back to automatic`() = runTest {
        db.analysis().upsertDropMark(DropMarkEntity("a", 52_300, markedAtMs = 1))

        db.analysis().upsertDropMark(DropMarkEntity("a", 61_000, markedAtMs = 2))
        assertEquals(mapOf("a" to 61_000L), store.dropMarks(listOf("a")))

        db.analysis().deleteDropMark("a")
        assertEquals(emptyMap<String, Long>(), store.dropMarks(listOf("a")))
    }

    @Test
    fun `the editor reads times the way you type them`() {
        assertEquals(65_000L, com.rvillarroel.djrodri.ui.parseTime("1:05"))
        assertEquals(42_000L, com.rvillarroel.djrodri.ui.parseTime("42"))
        assertNull(com.rvillarroel.djrodri.ui.parseTime("1:75"))
        assertNull(com.rvillarroel.djrodri.ui.parseTime("uno"))
    }
}
