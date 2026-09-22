package com.rvillarroel.djrodri.data.analysis

import com.rvillarroel.djrodri.data.sources.AudioFeatures
import com.rvillarroel.djrodri.data.sources.Lyrics
import com.rvillarroel.djrodri.data.sources.SourceException
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaylistAnalyzerTest {

    private class MemoryStore : AnalysisStore {
        val saved = mutableMapOf<String, TrackAnalysis>()
        val marks = mutableMapOf<String, Long>()
        val styles = mutableMapOf<String, String>()
        override suspend fun analyses(trackIds: Collection<String>) = saved.filterKeys { it in trackIds }
        override suspend fun save(analysis: TrackAnalysis) { saved[analysis.trackId] = analysis }
        override suspend fun dropMarks(trackIds: Collection<String>) = marks.filterKeys { it in trackIds }
        override suspend fun genreOverrides(trackIds: Collection<String>) = styles.filterKeys { it in trackIds }
    }

    private val chorusLrc = buildString {
        var t = 10
        fun line(text: String) { appendLine("[%02d:%02d.00] %s".format(t / 60, t % 60, text)); t += 4 }
        listOf("verso uno", "verso dos").forEach(::line)
        repeat(3) { i ->
            listOf("este es el coro", "que todos cantan", "con las manos arriba", "hasta el final").forEach(::line)
            listOf("puente $i a", "puente $i b", "puente $i c").forEach(::line)
        }
    }

    private fun track(id: String, durationMs: Long = 230_000) =
        SourceTrack(id, "spotify:track:$id", "Tema $id", "Artista $id", "Album", durationMs, "ISRC$id")

    private val lyricsCalls = mutableListOf<String>()
    private val featureCalls = mutableListOf<List<String>>()
    private val store = MemoryStore()
    private var genresNow = setOf("house")

    private fun analyzer(
        lyrics: (String) -> Lyrics = { Lyrics.Synced(chorusLrc) },
        features: (List<String>) -> Map<String, AudioFeatures> = { ids -> ids.associateWith { AudioFeatures(96.0, 9, 0, 0.8, 0.7, 0.6) } },
    ) = PlaylistAnalyzer(
        lyricsSource = { q -> lyricsCalls += q.title; lyrics(q.title) },
        featuresSource = { ids -> featureCalls += ids; features(ids) },
        genreSource = object : com.rvillarroel.djrodri.data.sources.GenreSource {
            override suspend fun genresByIsrc(isrc: String) = com.rvillarroel.djrodri.data.sources.ArtistTags("mbid", "Artista", genresNow)
            override suspend fun genresByArtistName(name: String) = null
        },
        store = store,
        clock = { 1_000 },
        politenessDelayMs = 0,
    )

    @Test
    fun `a playlist is analyzed song by song and the result says what the DJ can work with`() = runTest {
        val tracks = listOf(track("a"), track("b"), track("short", durationMs = 130_000))

        val progress = analyzer().analyze(tracks).toList()

        val finished = progress.last() as AnalysisProgress.Finished
        assertEquals(3, finished.coverage.total)
        assertEquals(3, finished.coverage.withChorus)
        assertEquals(3, finished.coverage.withKey)
        assertEquals(1, finished.coverage.alwaysWhole) // the short one
        assertEquals(listOf(0, 0, 1, 2), progress.filterIsInstance<AnalysisProgress.Working>().map { it.done })
    }

    @Test
    fun `a song analyzed once is never fetched again`() = runTest {
        analyzer().analyze(listOf(track("a"), track("b"))).last()
        lyricsCalls.clear()
        featureCalls.clear()

        analyzer().analyze(listOf(track("a"), track("b"), track("c"))).last()

        assertEquals(listOf("Tema c"), lyricsCalls)
        assertEquals(listOf(listOf("c")), featureCalls)
    }

    @Test
    fun `songs that failed because a source was down are retried next time, clean misses are not`() = runTest {
        analyzer(lyrics = { title -> if (title == "Tema a") throw SourceException("503") else Lyrics.NotFound }).analyze(listOf(track("a"), track("b"))).last()
        assertEquals(LyricsStatus.ERROR, store.saved.getValue("a").lyricsStatus)
        assertEquals(LyricsStatus.NOT_FOUND, store.saved.getValue("b").lyricsStatus)
        lyricsCalls.clear()

        analyzer().analyze(listOf(track("a"), track("b"))).last()

        assertEquals(listOf("Tema a"), lyricsCalls)
        assertEquals(LyricsStatus.SYNCED, store.saved.getValue("a").lyricsStatus)
    }

    @Test
    fun `if features are down the lyrics are still kept and the song is marked for retry`() = runTest {
        analyzer(features = { throw SourceException("down") }).analyze(listOf(track("a"))).last()

        val saved = store.saved.getValue("a")
        assertEquals(LyricsStatus.ERROR, saved.lyricsStatus)
        assertNotNull(saved.syncedLrc)
    }

    @Test
    fun `your drop mark and the detected chorus both reach the profile`() = runTest {
        val analyzer = analyzer()
        analyzer.analyze(listOf(track("a"))).last()
        store.marks["a"] = 61_500

        val profile = analyzer.profiles(listOf(track("a"))).single()

        assertEquals(61_500L, profile.userDropMs)
        assertEquals(3, profile.chorus!!.occurrences.size)
        assertEquals(96.0, profile.bpm)
    }

    @Test
    fun `a song never analyzed still plays, it just has nothing to cut on`() = runTest {
        val profile = analyzer().profiles(listOf(track("new"))).single()

        assertNull(profile.chorus)
        assertTrue(profile.durationMs > 0)
    }

    @Test
    fun `when only the genre sources changed, genres are asked again and lyrics and numbers are kept`() = runTest {
        analyzer().analyze(listOf(track("a"))).last()
        // An analysis finished by the previous version, before genres were decided by votes.
        store.saved["a"] = store.saved.getValue("a").copy(analyzerVersion = TrackAnalysis.GENRES_ONLY_FROM)
        lyricsCalls.clear()
        featureCalls.clear()
        genresNow = setOf("pop")

        analyzer().analyze(listOf(track("a"))).last()

        val saved = store.saved.getValue("a")
        assertEquals(setOf("pop"), saved.genres)
        assertEquals(TrackAnalysis.CURRENT_VERSION, saved.analyzerVersion)
        assertNotNull(saved.syncedLrc)
        assertEquals(emptyList<String>(), lyricsCalls)
        assertEquals(emptyList<List<String>>(), featureCalls)
    }

    @Test
    fun `updating the library's genres never analyzes songs that were never analyzed`() = runTest {
        analyzer().analyze(listOf(track("a"))).last()
        lyricsCalls.clear()

        analyzer().analyze(listOf(track("a"), track("never")), onlyAnalyzed = true).last()

        assertEquals(emptyList<String>(), lyricsCalls)
        assertNull(store.saved["never"])
    }

    @Test
    fun `the style you set by hand beats what the sources say`() = runTest {
        val analyzer = analyzer()
        analyzer.analyze(listOf(track("a"))).last()
        assertEquals(com.rvillarroel.djrodri.domain.dj.StyleFamily.ELECTRONIC, com.rvillarroel.djrodri.domain.dj.StyleFamily.of(analyzer.profiles(listOf(track("a"))).single()))

        store.styles["a"] = "POP"

        assertEquals(com.rvillarroel.djrodri.domain.dj.StyleFamily.POP, com.rvillarroel.djrodri.domain.dj.StyleFamily.of(analyzer.profiles(listOf(track("a"))).single()))
    }
}
