package com.rvillarroel.djrodri.localdj

import android.content.Context
import com.rvillarroel.djrodri.domain.mix.BeatGrid
import com.rvillarroel.djrodri.domain.mix.BeatTracker
import com.rvillarroel.djrodri.domain.mix.OnsetEnvelope
import com.rvillarroel.djrodri.domain.mix.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the analysis of one of your files found: its pulse, its shape, and how loud it runs. */
data class SongAnalysis(val grid: BeatGrid?, val waveform: Waveform, val energy: Double)

/**
 * Analyzes your files from their own audio (this is the one place the app is allowed to: they are yours) and
 * keeps the result next to the app, keyed by the file's modification date, so a song is analyzed once.
 */
class LocalAnalysis(context: Context) {
    private val dir = File(context.filesDir, "localdj/analysis").apply { mkdirs() }

    fun cached(song: LocalSong): SongAnalysis? = runCatching {
        val file = file(song)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        if (json.getLong("modified") != song.modifiedAt || json.getInt("version") != VERSION) return null
        val grid = json.optJSONObject("grid")?.let { BeatGrid(it.getLong("first"), it.getDouble("bpm"), it.getDouble("confidence")) }
        SongAnalysis(grid, Waveform(floats(json.getJSONArray("low")), floats(json.getJSONArray("mid")), floats(json.getJSONArray("high"))), json.getDouble("energy"))
    }.getOrNull()

    /** Bpm from the cache only, for lists: never decodes anything. */
    fun cachedBpm(song: LocalSong): Double? = cached(song)?.grid?.takeIf { it.confidence >= BeatGrid.MIN_MIXABLE_CONFIDENCE }?.bpm

    suspend fun analyze(song: LocalSong, audio: DecodedAudio): SongAnalysis = withContext(Dispatchers.Default) {
        val mono = audio.mono()
        // A silent or odd file can give 0/0: a pulse that isn't a number is no pulse, and JSON refuses NaN anyway.
        val grid = BeatTracker.estimate(OnsetEnvelope.of(mono, audio.sampleRate), hopMs = HOP_MS)
            ?.takeIf { it.bpm.isFinite() && it.confidence.isFinite() }
        val waveform = Waveform.of(mono, audio.sampleRate)
        val energy = rms(mono).takeIf { it.isFinite() } ?: 0.0
        val analysis = SongAnalysis(grid, waveform, energy)
        save(song, analysis)
        analysis
    }

    private fun save(song: LocalSong, a: SongAnalysis) {
        val json = JSONObject()
            .put("version", VERSION)
            .put("modified", song.modifiedAt)
            .put("energy", a.energy)
            .put("low", ints(a.waveform.low))
            .put("mid", ints(a.waveform.mid))
            .put("high", ints(a.waveform.high))
        a.grid?.let { json.put("grid", JSONObject().put("first", it.firstBeatMs).put("bpm", it.bpm).put("confidence", it.confidence)) }
        file(song).writeText(json.toString())
    }

    private fun file(song: LocalSong) = File(dir, "${song.id}.json")

    private fun ints(values: FloatArray) = JSONArray().apply { values.forEach { put(if (it.isFinite()) (it * 255).toInt() else 0) } }

    private fun floats(array: JSONArray) = FloatArray(array.length()) { array.getInt(it) / 255f }

    private fun rms(mono: FloatArray): Double {
        if (mono.isEmpty()) return 0.0
        var sum = 0.0
        val step = maxOf(1, mono.size / 200_000)
        var n = 0
        for (i in mono.indices step step) { sum += mono[i] * mono[i]; n++ }
        return kotlin.math.sqrt(sum / n)
    }

    private companion object {
        /** 3: 16-bit files read right and tempos counted the dancefloor way (126, not 63): older analyses are redone. */
        const val VERSION = 3
        const val HOP_MS = 10
    }
}
