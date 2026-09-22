package com.rvillarroel.djrodri.localdj

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.rvillarroel.djrodri.domain.mix.LocalTrack
import com.rvillarroel.djrodri.domain.mix.MixPlan
import com.rvillarroel.djrodri.domain.mix.MixPlanner
import com.rvillarroel.djrodri.domain.mix.Pad
import com.rvillarroel.djrodri.library.db.LocalPlaylistDao
import com.rvillarroel.djrodri.library.db.LocalPlaylistEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

enum class Side { A, B }

data class MixerState(
    /** 0 = only A, 1 = only B, equal power in between. */
    val crossfader: Double = 0.0,
    val automix: Boolean = false,
    /** What the automix plays next, in order. */
    val queue: List<LocalSong> = emptyList(),
    /** A mix the automix is doing right now, in words: "Mezclando a B en 4 compases". */
    val automixStatus: String? = null,
    val padVolume: Double = 0.8,
    val message: String? = null,
)

/**
 * The DJ mixer for your own files: two decks, a crossfader, sound pads, and an automix that beat-matches and
 * blends on phrase boundaries with [MixPlanner]. Spotify is never involved here; its audio can't be mixed and
 * isn't. Grabbing audio focus makes Spotify pause by itself while the decks play.
 */
class DjMixer(
    private val context: Context,
    private val scope: CoroutineScope,
    val library: LocalLibrary,
    val analysis: LocalAnalysis,
    val playlists: LocalPlaylistDao,
) {
    val deckA = Deck("A")
    val deckB = Deck("B")
    val sampler = Sampler()

    private val _state = MutableStateFlow(MixerState())
    val state: StateFlow<MixerState> = _state

    private var automixJob: Job? = null
    private val loadJobs = mutableMapOf<Side, Job>()

    fun deck(side: Side) = if (side == Side.A) deckA else deckB
    private fun other(side: Side) = if (side == Side.A) deckB else deckA

    init {
        applyCrossfader(0.0)
        sampler.prepare()
    }

    // ---- Loading --------------------------------------------------------------------------------------------------

    /** Decode and analyze (once; the analysis is cached) and put the song on [side]. */
    fun load(side: Side, song: LocalSong, onLoaded: (() -> Unit)? = null) {
        val deck = deck(side)
        loadJobs[side]?.cancel()
        deck.beginLoading(song)
        loadJobs[side] = scope.launch {
            runCatching {
                val audio = AudioDecoder.decode(context, song.uri)
                val analysisResult = analysis.cached(song) ?: analysis.analyze(song, audio)
                deck.loaded(song, audio, analysisResult.grid, analysisResult.waveform)
                onLoaded?.invoke()
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                deck.failed(song, "No se pudo abrir el archivo: ${error.message ?: "formato no soportado"}")
            }
        }
    }

    // ---- Transport ------------------------------------------------------------------------------------------------

    fun togglePlay(side: Side) {
        val deck = deck(side)
        if (!deck.state.value.playing) takeAudioFocus()
        deck.toggle()
    }

    /** SYNC: tempo and beat of [side] follow the other deck. */
    fun sync(side: Side) {
        val done = deck(side).syncTo(other(side))
        if (!done) message("Para sincronizar, las dos canciones necesitan un pulso claro")
    }

    fun setCrossfader(position: Double) {
        applyCrossfader(position)
        _state.update { it.copy(crossfader = position.coerceIn(0.0, 1.0)) }
    }

    private fun applyCrossfader(position: Double) {
        val (a, b) = MixPlanner.gains(position.coerceIn(0.0, 1.0))
        deckA.crossfaderGain = a
        deckB.crossfaderGain = b
    }

    fun pad(pad: Pad) {
        takeAudioFocus()
        sampler.trigger(pad)
    }

    fun setPadVolume(volume: Double) {
        sampler.volume = volume
        _state.update { it.copy(padVolume = volume) }
    }

    // ---- Queue and automix ----------------------------------------------------------------------------------------

    fun enqueue(songs: List<LocalSong>) = _state.update { it.copy(queue = it.queue + songs) }

    fun clearQueue() = _state.update { it.copy(queue = emptyList()) }

    fun removeFromQueue(index: Int) = _state.update { s -> s.copy(queue = s.queue.filterIndexed { i, _ -> i != index }) }

    /**
     * Automix: the playing deck runs until its mix point, the idle deck gets the next song from the queue already
     * synced and cued on a phrase, and the crossfader moves over [MixPlanner]'s fade. When the tempos are too far
     * apart for a clean blend it cuts on the phrase instead.
     */
    fun setAutomix(on: Boolean) {
        _state.update { it.copy(automix = on, automixStatus = null) }
        automixJob?.cancel()
        if (!on) return
        automixJob = scope.launch {
            while (isActive) {
                runCatching { automixStep() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                delay(AUTOMIX_TICK_MS)
            }
        }
    }

    private suspend fun automixStep() {
        val playingSide = when {
            deckA.state.value.playing && _state.value.crossfader < 0.5 -> Side.A
            deckB.state.value.playing && _state.value.crossfader >= 0.5 -> Side.B
            deckA.state.value.playing -> Side.A
            deckB.state.value.playing -> Side.B
            else -> null
        }
        if (playingSide == null) {
            // Nothing plays: start the first loaded deck, or load the queue's first song.
            val loaded = listOf(Side.A, Side.B).firstOrNull { deck(it).state.value.loaded }
            if (loaded != null) {
                setCrossfader(if (loaded == Side.A) 0.0 else 1.0)
                togglePlay(loaded)
            } else {
                nextFromQueue()?.let { load(Side.A, it) }
            }
            return
        }
        val outgoing = deck(playingSide)
        val incomingSide = if (playingSide == Side.A) Side.B else Side.A
        val incoming = deck(incomingSide)
        val out = outgoing.state.value

        // Keep the idle deck ready: the next song loaded well before it is needed.
        if (!incoming.state.value.loaded && !incoming.state.value.loading) {
            val next = nextFromQueue()
            if (next != null) load(incomingSide, next)
            else _state.update { it.copy(automixStatus = "La cola está vacía: agrega canciones para seguir mezclando") }
            return
        }
        if (!incoming.state.value.loaded) return

        val plan = MixPlanner.plan(out.toLocalTrack(), incoming.state.value.toLocalTrack())
        val fadeStart = when (plan) {
            is MixPlan.Blend -> plan.fadeStartAMs
            is MixPlan.Cut -> out.audibleEndMs() - CUT_BEFORE_END_MS
        }
        val position = outgoing.positionMs()
        _state.update { it.copy(automixStatus = "Siguiente mezcla en ${formatSeconds(fadeStart - position)}") }
        if (position < fadeStart - LEAD_MS) return

        when (plan) {
            is MixPlan.Blend -> blend(playingSide, incomingSide, plan)
            is MixPlan.Cut -> {
                incoming.seekTo(plan.startBMs)
                takeAudioFocus()
                incoming.play()
                setCrossfader(if (incomingSide == Side.B) 1.0 else 0.0)
                outgoing.eject()
            }
        }
    }

    private suspend fun blend(outSide: Side, inSide: Side, plan: MixPlan.Blend) {
        val incoming = deck(inSide)
        val outgoing = deck(outSide)
        incoming.seekTo(plan.startBMs)
        takeAudioFocus()
        incoming.play()
        // Same tempo as what is playing (with its own speed), and the kicks lined up.
        incoming.syncTo(outgoing)
        _state.update { it.copy(automixStatus = "Mezclando hacia ${inSide.name}") }
        val from = _state.value.crossfader
        val to = if (inSide == Side.B) 1.0 else 0.0
        val steps = (plan.fadeMs / FADE_STEP_MS).coerceAtLeast(1)
        for (i in 1..steps) {
            setCrossfader(from + (to - from) * i / steps)
            delay(FADE_STEP_MS)
        }
        // The song mixed out leaves the deck, which takes the next one from the queue.
        outgoing.eject()
        _state.update { it.copy(automixStatus = null) }
    }

    private fun nextFromQueue(): LocalSong? {
        val next = _state.value.queue.firstOrNull() ?: return null
        _state.update { it.copy(queue = it.queue.drop(1)) }
        return next
    }

    // ---- Playlists of your files -----------------------------------------------------------------------------------

    suspend fun createPlaylist(name: String): Long = playlists.insert(LocalPlaylistEntity(name = name.trim().ifBlank { "Mi set" }, createdAtMs = System.currentTimeMillis()))

    suspend fun addToPlaylist(playlistId: Long, song: LocalSong) = playlists.add(playlistId, song.id)

    suspend fun songsOf(playlistId: Long, all: List<LocalSong>): List<LocalSong> {
        val byId = all.associateBy { it.id }
        return playlists.songIds(playlistId).mapNotNull { byId[it] }
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: Long) {
        playlists.replace(playlistId, playlists.songIds(playlistId).filter { it != songId })
    }

    suspend fun deletePlaylist(playlistId: Long) = playlists.delete(playlistId)

    // ---- Housekeeping ---------------------------------------------------------------------------------------------

    fun message(text: String?) = _state.update { it.copy(message = text) }

    private var focusRequest: AudioFocusRequest? = null

    /** The decks are music: taking focus makes Spotify (or anything else) pause instead of playing on top. */
    private fun takeAudioFocus() {
        if (focusRequest != null) return
        val manager = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    focusRequest = null
                    deckA.pause()
                    deckB.pause()
                }
            }
            .build()
        if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = request
    }

    private fun DeckState.toLocalTrack() = LocalTrack(
        id = song?.id?.toString() ?: "",
        title = song?.title.orEmpty(),
        artist = song?.artist.orEmpty(),
        durationMs = audibleEndMs(),
        // Positions in the song's own time; the speed is set by SYNC from the tempo actually playing.
        grid = grid,
        mixInMs = grid?.firstBeatMs,
    )

    /** The file's end minus its trailing silence: what a DJ would call the end of the song. */
    private fun DeckState.audibleEndMs(): Long = waveform?.let { (it.lastSound() * durationMs).toLong() } ?: durationMs

    private fun formatSeconds(ms: Long): String {
        val s = (ms / 1000.0).roundToLong().coerceAtLeast(0)
        return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "$s s"
    }

    private companion object {
        const val AUTOMIX_TICK_MS = 250L
        const val LEAD_MS = 150L
        const val FADE_STEP_MS = 40L
        const val CUT_BEFORE_END_MS = 2_000L
    }
}
