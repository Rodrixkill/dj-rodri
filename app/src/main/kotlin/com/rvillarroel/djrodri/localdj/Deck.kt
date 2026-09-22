package com.rvillarroel.djrodri.localdj

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Process
import com.rvillarroel.djrodri.domain.mix.BeatGrid
import com.rvillarroel.djrodri.domain.mix.EchoFx
import com.rvillarroel.djrodri.domain.mix.FilterFx
import com.rvillarroel.djrodri.domain.mix.LoopRegion
import com.rvillarroel.djrodri.domain.mix.ThreeBandEq
import com.rvillarroel.djrodri.domain.mix.Waveform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToLong

/** What a deck shows. Position is not here: it moves 60 times a second and is read with [Deck.positionMs]. */
data class DeckState(
    val song: LocalSong? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val playing: Boolean = false,
    val durationMs: Long = 0,
    val grid: BeatGrid? = null,
    val waveform: Waveform? = null,
    /** Speed factor, 1.0 = original. Key lock keeps the pitch whatever the speed. */
    val tempo: Double = 1.0,
    val cueMs: Long = 0,
    val loopBeats: Int? = null,
    val eqLow: Double = 0.0,
    val eqMid: Double = 0.0,
    val eqHigh: Double = 0.0,
    /** -1 low-pass .. 0 off .. 1 high-pass. */
    val filter: Double = 0.0,
    val echo: Boolean = false,
    /** Channel fader, 0..1. */
    val volume: Double = 1.0,
    val synced: Boolean = false,
    /** A tape stop or a backspin is running. */
    val effect: String? = null,
) {
    val bpm: Double? get() = grid?.bpm?.let { it * tempo }
    val loaded: Boolean get() = song != null && !loading && error == null
}

/**
 * One deck of the mixer: a song in memory, its own audio output, and the chain a DJ mixer channel has
 * (EQ → filter → echo → fader). Plays on its own high-priority thread; every knob is safe to turn while it plays.
 */
class Deck(val name: String) {
    private val _state = MutableStateFlow(DeckState())
    val state: StateFlow<DeckState> = _state

    @Volatile private var audio: DecodedAudio? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var cursor = 0L
    @Volatile private var loop: LoopRegion? = null
    @Volatile private var playing = false

    /** Set by the mixer: this deck's share of the crossfader, 0..1. */
    @Volatile var crossfaderGain = 1.0

    private val lock = Any()
    private var eq = ThreeBandEq(44_100)
    private var filter = FilterFx(44_100)
    private var echo = EchoFx(44_100)
    private var writtenSinceSeek = 0L
    private var headAtSeek = 0
    private var lastGain = 0f
    private var thread: Thread? = null

    /** A tape stop / backspin in progress: the speed ramps down (or backwards) over [effectFrames]. */
    @Volatile private var effectFrames = 0
    @Volatile private var effectDone = 0
    @Volatile private var effectBackwards = false

    // ---- Loading --------------------------------------------------------------------------------------------------

    fun beginLoading(song: LocalSong) {
        pause()
        _state.value = DeckState(song = song, loading = true, volume = _state.value.volume)
    }

    fun loaded(song: LocalSong, decoded: DecodedAudio, grid: BeatGrid?, waveform: Waveform?) {
        synchronized(lock) {
            releaseTrack()
            audio = decoded
            cursor = 0
            loop = null
            eq = ThreeBandEq(decoded.sampleRate)
            filter = FilterFx(decoded.sampleRate)
            echo = EchoFx(decoded.sampleRate)
            track = buildTrack(decoded.sampleRate)
            writtenSinceSeek = 0
            headAtSeek = 0
        }
        val cue = grid?.firstBeatMs?.takeIf { grid.confidence >= BeatGrid.MIN_MIXABLE_CONFIDENCE } ?: 0L
        _state.value = DeckState(song = song, durationMs = decoded.durationMs, grid = grid, waveform = waveform, cueMs = cue, volume = _state.value.volume)
        seekTo(cue)
        ensureThread()
    }

    /** Empties the deck and frees the song's memory: the automix does it once a song has been mixed out. */
    fun eject() {
        synchronized(lock) {
            releaseTrack()
            audio = null
            loop = null
            cursor = 0
        }
        _state.value = DeckState(volume = _state.value.volume)
    }

    fun failed(song: LocalSong, message: String) {
        _state.value = DeckState(song = song, error = message, volume = _state.value.volume)
    }

    // ---- Transport ------------------------------------------------------------------------------------------------

    fun play() {
        val t = track ?: return
        if (audio == null) return
        playing = true
        t.play()
        _state.update { it.copy(playing = true) }
    }

    fun pause() {
        playing = false
        track?.pause()
        _state.update { it.copy(playing = false) }
    }

    fun toggle() = if (playing) pause() else play()

    /** CUE the way a CDJ does it: playing, it jumps back to the cue and stops; stopped, it sets the cue here. */
    fun cue() {
        if (playing) {
            pause()
            seekTo(_state.value.cueMs)
        } else {
            val here = snapToBeat(positionMs())
            _state.update { it.copy(cueMs = here) }
            seekTo(here)
        }
    }

    fun seekTo(ms: Long) {
        val a = audio ?: return
        synchronized(lock) {
            cursor = (ms.coerceIn(0, a.durationMs) * a.sampleRate / 1000).coerceIn(0, a.frames.toLong() - 1)
            track?.let {
                val wasPlaying = playing
                it.pause()
                it.flush()
                headAtSeek = it.playbackHeadPosition
                writtenSinceSeek = 0
                if (wasPlaying) it.play()
            }
        }
    }

    /** Jump by whole beats, forward or back, keeping the phase: the jump stays on the music. */
    fun beatJump(beats: Int) {
        val beatMs = _state.value.grid?.beatMs ?: 500.0
        seekTo((positionMs() + beats * beatMs).roundToLong().coerceAtLeast(0))
    }

    /** "Otra vez": back [seconds], landed on a beat so the replay sounds like the music, not a glitch. */
    fun replay(seconds: Int) {
        val grid = _state.value.grid
        val target = positionMs() - seconds * 1000L
        seekTo((grid?.nearestBeat(target) ?: target).coerceAtLeast(0))
    }

    // ---- Tempo and sync -------------------------------------------------------------------------------------------

    fun setTempo(speed: Double, synced: Boolean = false) {
        val s = speed.coerceIn(MIN_TEMPO, MAX_TEMPO)
        applySpeed(s)
        _state.update { it.copy(tempo = s, synced = synced) }
    }

    private fun applySpeed(speed: Double) {
        // Key lock: the framework stretches time and keeps the pitch, like "master tempo" on a CDJ.
        runCatching { track?.playbackParams = PlaybackParams().setSpeed(speed.toFloat()).setPitch(1f) }
    }

    /**
     * SYNC to [master]: the same tempo (half and double time count), then the same place in the beat, so the kicks
     * land together. Needs both beat grids.
     */
    fun syncTo(master: Deck): Boolean {
        val mine = _state.value.grid ?: return false
        val theirs = master.state.value.grid ?: return false
        val targetBpm = theirs.bpm * master.state.value.tempo
        var ratio = targetBpm / mine.bpm
        while (ratio > 1.5) ratio /= 2
        while (ratio < 0.75) ratio *= 2
        setTempo(ratio, synced = true)
        // Phase: how far into its beat each deck is, then move this one by the difference.
        val phaseMaster = ((master.positionMs() - theirs.firstBeatMs) / theirs.beatMs).let { it - kotlin.math.floor(it) }
        val phaseMine = ((positionMs() - mine.firstBeatMs) / mine.beatMs).let { it - kotlin.math.floor(it) }
        var delta = phaseMaster - phaseMine
        if (delta > 0.5) delta -= 1.0
        if (delta < -0.5) delta += 1.0
        seekTo((positionMs() + delta * mine.beatMs).roundToLong().coerceAtLeast(0))
        return true
    }

    // ---- Loop -----------------------------------------------------------------------------------------------------

    fun setLoop(beats: Int?) {
        val a = audio ?: return
        loop = beats?.let { LoopRegion.onBeat(_state.value.grid, positionMs(), it, bpmFallback = 120.0, sampleRate = a.sampleRate) }
        _state.update { it.copy(loopBeats = beats) }
    }

    // ---- Mixer channel --------------------------------------------------------------------------------------------

    fun setEq(low: Double = _state.value.eqLow, mid: Double = _state.value.eqMid, high: Double = _state.value.eqHigh) {
        synchronized(lock) { eq.set(low, mid, high) }
        _state.update { it.copy(eqLow = low, eqMid = mid, eqHigh = high) }
    }

    fun setFilter(amount: Double) {
        synchronized(lock) { filter.set(amount) }
        _state.update { it.copy(filter = amount) }
    }

    fun setEcho(on: Boolean) {
        val bpm = _state.value.bpm ?: 120.0
        synchronized(lock) { echo.set(if (on) ECHO_MIX else 0.0, EchoFx.delayMs(bpm)) }
        _state.update { it.copy(echo = on) }
    }

    fun setVolume(volume: Double) = _state.update { it.copy(volume = volume.coerceIn(0.0, 1.0)) }

    /** Tape stop: the record slows to a halt in about a second, the classic end-of-a-set move. */
    fun tapeStop() = startEffect(backwards = false, "Parada de cinta")

    /** Backspin: the record is pulled back by hand and the music whooshes away. */
    fun backspin() = startEffect(backwards = true, "Backspin")

    private fun startEffect(backwards: Boolean, label: String) {
        val a = audio ?: return
        if (!playing) return
        effectBackwards = backwards
        effectDone = 0
        effectFrames = (a.sampleRate * EFFECT_SECONDS).toInt()
        _state.update { it.copy(effect = label) }
    }

    // ---- Position -------------------------------------------------------------------------------------------------

    /** Where the listener is, not where the decoder is: minus what is still in the output buffer. */
    fun positionMs(): Long {
        val a = audio ?: return 0
        val t = track
        val inFlight = if (t != null) (writtenSinceSeek - (t.playbackHeadPosition - headAtSeek).toLong()).coerceAtLeast(0) else 0
        var frame = cursor - inFlight
        loop?.let { l -> if (frame < l.startFrame && cursor >= l.startFrame) frame += l.lengthFrames }
        return (frame.coerceAtLeast(0) * 1000 / a.sampleRate)
    }

    private fun snapToBeat(ms: Long): Long = _state.value.grid?.takeIf { it.confidence >= BeatGrid.MIN_MIXABLE_CONFIDENCE }?.nearestBeat(ms)?.coerceAtLeast(0) ?: ms

    // ---- The audio thread -----------------------------------------------------------------------------------------

    private fun ensureThread() {
        if (thread != null) return
        thread = Thread({ run() }, "deck-$name").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = FloatArray(CHUNK_FRAMES * 2)
        while (!Thread.currentThread().isInterrupted) {
            val a = audio
            val t = track
            if (a == null || t == null || !playing) {
                Thread.sleep(IDLE_MS)
                continue
            }
            val frames: Int
            synchronized(lock) {
                frames = fill(a, chunk)
                eq.process(chunk, frames)
                filter.process(chunk, frames)
                echo.process(chunk, frames)
                applyGain(chunk, frames)
            }
            if (frames == 0) {
                // The song ended: stop like a player does, at the end.
                pause()
                continue
            }
            val done = t.write(chunk, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
            if (done > 0) synchronized(lock) { writtenSinceSeek += done / 2 }
        }
    }

    /** Copies the next frames from the song, through the loop and any tape effect, and moves the cursor. */
    private fun fill(a: DecodedAudio, out: FloatArray): Int {
        val samples = a.samples
        var written = 0
        var position = cursor.toDouble()
        while (written < CHUNK_FRAMES) {
            val speed = effectSpeed()
            if (speed == null) {
                // Normal play: frame by frame, wrapping in the loop.
                var frame = position.toLong()
                loop?.let { frame = it.wrap(frame) }
                if (frame >= a.frames) break
                out[written * 2] = samples[(frame * 2).toInt()]
                out[written * 2 + 1] = samples[(frame * 2 + 1).toInt()]
                position = frame + 1.0
            } else {
                // Tape effect: read at a changing speed, forward slowing or backwards, with linear interpolation.
                val frame = position.toLong().coerceIn(0, a.frames.toLong() - 2)
                val frac = (position - frame).toFloat()
                val i = (frame * 2).toInt()
                out[written * 2] = samples[i] + (samples[i + 2] - samples[i]) * frac
                out[written * 2 + 1] = samples[i + 1] + (samples[i + 3] - samples[i + 1]) * frac
                position = (position + speed).coerceAtLeast(0.0)
                effectDone++
                if (effectDone >= effectFrames) {
                    effectFrames = 0
                    playing = false
                    _state.update { it.copy(playing = false, effect = null) }
                    written++
                    break
                }
            }
            written++
        }
        cursor = position.toLong()
        return written
    }

    /** Null when no tape effect runs; otherwise frames to advance per output frame. */
    private fun effectSpeed(): Double? {
        if (effectFrames == 0) return null
        val t = effectDone.toDouble() / effectFrames
        return if (effectBackwards) -3.0 * (1 - t) else (1 - t) * (1 - t)
    }

    /** Fader × crossfader, ramped across the chunk so moving a fader never clicks. */
    private fun applyGain(chunk: FloatArray, frames: Int) {
        val target = (_state.value.volume * crossfaderGain).toFloat()
        val start = lastGain
        if (frames == 0) return
        for (i in 0 until frames) {
            val g = start + (target - start) * (i + 1) / frames
            chunk[i * 2] = (chunk[i * 2] * g).coerceIn(-1f, 1f)
            chunk[i * 2 + 1] = (chunk[i * 2 + 1] * g).coerceIn(-1f, 1f)
        }
        lastGain = target
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            )
            .setBufferSizeInBytes(maxOf(min, CHUNK_FRAMES * 8 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { t -> runCatching { t.playbackParams = PlaybackParams().setSpeed(_state.value.tempo.toFloat()).setPitch(1f) } }
    }

    private fun releaseTrack() {
        playing = false
        track?.let {
            runCatching { it.pause(); it.flush() }
            it.release()
        }
        track = null
    }

    fun release() {
        thread?.interrupt()
        thread = null
        synchronized(lock) { releaseTrack() }
        audio = null
    }

    companion object {
        const val CHUNK_FRAMES = 1_024
        const val IDLE_MS = 15L
        const val MIN_TEMPO = 0.84
        const val MAX_TEMPO = 1.16
        const val ECHO_MIX = 0.55
        const val EFFECT_SECONDS = 1.2

        fun tempoPercent(tempo: Double): String = "%+.1f %%".format((tempo - 1) * 100).replace("+0.0", "0.0")
    }
}
