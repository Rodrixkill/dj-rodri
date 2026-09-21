package com.rvillarroel.djrodri.spike

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rvillarroel.djrodri.domain.stats.LatencyStats
import com.rvillarroel.djrodri.spotify.LIKED_SONGS_ID
import com.rvillarroel.djrodri.spotify.PlaylistSummary
import com.rvillarroel.djrodri.spotify.RemoteController
import com.rvillarroel.djrodri.spotify.SpotifyWebApi
import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.container
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 0: answers, on the real phone, whether Spotify can be driven precisely enough for a DJ that cuts at natural
 * points and enters songs at their drop. Every probe writes numbers to the log; the report collects them.
 */
class SpikeViewModel(app: Application) : AndroidViewModel(app) {
    val log = SpikeLog()
    private val container = app.container
    private val http: OkHttpClient = container.http
    val webApi: SpotifyWebApi = container.webApi
    // The app's own connection: a second App Remote connection would fight the party for the player.
    val remote: RemoteController = container.remote
    private val coverage = CoverageProbe(http, log)

    val userName = MutableStateFlow<String?>(null)
    val playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val selectedPlaylist = MutableStateFlow<PlaylistSummary?>(null)
    val tracks = MutableStateFlow<List<SourceTrack>>(emptyList())
    val busy = MutableStateFlow<String?>(null)
    val reportPath = MutableStateFlow<String?>(null)

    private val skipLatencies = mutableListOf<Long>()
    private val cutErrors = mutableListOf<Long>()
    private val seekLatencies = mutableListOf<Long>()
    private val drifts = mutableListOf<Long>()
    private val ratings = mutableListOf<String>()
    private var coverageSummary: String? = null
    private var crossfadeSummary: String? = null
    private var authSummary: String? = null

    // ---- 1. Login + App Remote --------------------------------------------------------------------------------

    init {
        remote.logger = { log.add(it) }
        viewModelScope.launch {
            val token = container.auth.accessToken()
            if (token == null) {
                authSummary = "sin sesión"
                log.add("Web API: sin sesión, inicia sesión desde la pantalla principal")
                return@launch
            }
            authSummary = "OK (PKCE, token renovable)"
            runCatching {
                userName.value = webApi.displayName()
                playlists.value = listOf(PlaylistSummary(LIKED_SONGS_ID, "Tus me gusta", "", null)) + webApi.myPlaylists()
                log.add("Playlists visibles: ${playlists.value.size - 1}")
            }.onFailure { log.add("Web API error: ${it.message}") }
        }
    }

    fun connectRemote(context: Context) = remote.connect(context)

    fun readCrossfade() = viewModelScope.launch {
        val state = remote.crossfade()
        crossfadeSummary = state?.let { "activado=${it.isEnabled} duración=${it.duration}ms" } ?: "no respondió"
        log.add("Crossfade: $crossfadeSummary")
    }

    // ---- 2. Playlists -----------------------------------------------------------------------------------------

    fun selectPlaylist(playlist: PlaylistSummary) = work("Cargando canciones…") {
        selectedPlaylist.value = playlist
        tracks.value = webApi.tracks(playlist.id, limit = 100)
        log.add("${playlist.name}: ${tracks.value.size} canciones, ${tracks.value.count { it.isrc != null }} con ISRC")
    }

    fun playPlaylist() {
        val playlist = selectedPlaylist.value ?: return
        if (playlist.id == LIKED_SONGS_ID) tracks.value.firstOrNull()?.let { remote.play(it.uri) }
        else remote.play("spotify:playlist:${playlist.id}")
    }

    fun runCoverage() = work("Analizando cobertura (30 canciones)…") {
        val result = coverage.run(tracks.value.take(30))
        coverageSummary = result.toString()
        log.add("COBERTURA: $coverageSummary")
    }

    // ---- 3. Timing probes -------------------------------------------------------------------------------------

    /** How far the extrapolated position drifts from what Spotify reports, sampled once a second. */
    fun probeDrift() = work("Midiendo deriva de posición (20 s)…") {
        val base = remote.nowPlaying.value?.snapshot ?: return@work log.add("Deriva: no hay nada sonando")
        repeat(20) {
            delay(1_000)
            val asked = SystemClock.elapsedRealtime()
            val real = remote.playerState() ?: return@repeat
            val answered = SystemClock.elapsedRealtime()
            if (real.track?.uri != base.trackUri) return@repeat
            // Compare at the midpoint of the round trip so the call's own latency does not count as drift.
            val predicted = base.positionAt((asked + answered) / 2)
            drifts += abs(real.playbackPosition - predicted)
            log.add("deriva: real=${real.playbackPosition} predicho=$predicted ida-vuelta=${answered - asked}ms")
        }
        log.add("DERIVA: ${LatencyStats.summarize(drifts)}")
    }

    /** Natural cut: wait until the song reaches now+[seconds], then skip. Measures how late the cut really lands. */
    fun probeCut(seconds: Int) = work("Corte programado en $seconds s…") {
        val playing = remote.nowPlaying.value ?: return@work log.add("Corte: no hay nada sonando")
        val target = playing.snapshot.positionAt(SystemClock.elapsedRealtime()) + seconds * 1_000L
        val wait = playing.snapshot.msUntil(target, SystemClock.elapsedRealtime()) ?: return@work log.add("Corte: está en pausa")
        delay(wait)
        val before = remote.playerState()
        before?.let { cutErrors += abs(it.playbackPosition - target); log.add("corte: objetivo=$target real=${it.playbackPosition}") }
        measureTrackChange("skipNext") { remote.skipNext() }?.let { skipLatencies += it }
        log.add("CORTE: error ${LatencyStats.summarize(cutErrors)} · latencia salto ${LatencyStats.summarize(skipLatencies)}")
    }

    /**
     * Drop entry: jump to the next song and land straight on [dropSeconds].
     * [withPause] pauses first so the listener never hears the first instant of the next song; the rating buttons
     * record which variant sounded like a DJ and which sounded like a glitch.
     */
    fun probeDropEntry(dropSeconds: Int, withPause: Boolean) = work("Entrada en el drop (${if (withPause) "con pausa" else "directa"})…") {
        val dropMs = dropSeconds * 1_000L
        val start = SystemClock.elapsedRealtime()
        if (withPause) remote.pause()
        val changed = measureTrackChange("skipNext→drop") { remote.skipNext() } ?: return@work
        remote.seekTo(dropMs)
        val landed = withTimeoutOrNull(3_000) {
            remote.nowPlaying.first { it != null && abs(it.snapshot.positionMs - dropMs) < 1_500 }
        }
        if (withPause) remote.resume()
        val total = SystemClock.elapsedRealtime() - start
        if (landed != null) seekLatencies += total
        log.add("DROP ${if (withPause) "con pausa" else "directo"}: cambio=${changed}ms, en el drop tras ${total}ms (${if (landed != null) "ok" else "sin confirmar"})")
    }

    /** Does seeking to just before the end let Spotify's own crossfade do the transition? */
    fun probeEndSeek() = work("Saltando al final para probar crossfade…") {
        val playing = remote.nowPlaying.value ?: return@work log.add("Final: no hay nada sonando")
        val crossfadeMs = remote.crossfade()?.takeIf { it.isEnabled }?.duration?.toLong() ?: 0L
        val target = (playing.track.duration - crossfadeMs - 2_000).coerceAtLeast(0)
        remote.seekTo(target)
        log.add("Final: seek a $target ms (crossfade ${crossfadeMs}ms). Escucha si la transición fue suave y califica.")
    }

    /**
     * Spotify's own crossfade into a song the DJ picked, entering at its drop: queue the song, send the current one
     * to its last seconds so the fade starts, and once the new song is reported, jump it to [dropSeconds]
     * (0 = let it play from the start). Answers whether seeking mid-fade keeps the fade or breaks it.
     */
    fun probeFadeIntoDrop(dropSeconds: Int) = work("Fundido a la cola${if (dropSeconds > 0) " + drop" else ""}…") {
        val playing = remote.nowPlaying.value ?: return@work log.add("Fundido: no hay nada sonando")
        val next = tracks.value.filter { it.uri != playing.track.uri }.randomOrNull()
            ?: return@work log.add("Fundido: elige una playlist abajo para tener canciones")
        val crossfadeMs = remote.crossfade()?.takeIf { it.isEnabled }?.duration?.toLong()
            ?: return@work log.add("Fundido: el crossfade de Spotify está apagado")
        remote.queue(next.uri)
        // Queueing is not instant: give Spotify a moment so the fade goes to our song, not the context's next.
        delay(QUEUE_SETTLE_MS)
        val start = SystemClock.elapsedRealtime()
        remote.seekTo((playing.track.duration - crossfadeMs - FADE_MARGIN_MS).coerceAtLeast(0))
        val arrived = withTimeoutOrNull(crossfadeMs + 5_000) { remote.nowPlaying.first { it != null && it.track.uri != playing.track.uri } }
        val changedMs = SystemClock.elapsedRealtime() - start
        if (arrived == null) return@work log.add("Fundido: la canción no cambió")
        val ours = arrived.track.uri == next.uri
        log.add("FUNDIDO: cambio a ${arrived.track.name} en ${changedMs}ms · ${if (ours) "era la de la cola ✔" else "NO era la de la cola (${next.name})"}")
        if (dropSeconds > 0) {
            remote.seekTo(dropSeconds * 1_000L)
            log.add("FUNDIDO: salto al drop ${dropSeconds}s durante el fundido. Escucha si el fundido siguió o se cortó.")
        }
    }

    fun rate(what: String, verdict: String) {
        ratings += "$what: $verdict"
        log.add("CALIFICACIÓN $what → $verdict")
    }

    private suspend fun measureTrackChange(label: String, action: () -> Boolean): Long? {
        val fromUri = remote.nowPlaying.value?.track?.uri
        val start = SystemClock.elapsedRealtime()
        if (!action()) return null
        val next = withTimeoutOrNull(5_000) { remote.nowPlaying.first { it != null && it.track.uri != fromUri } }
        val elapsed = SystemClock.elapsedRealtime() - start
        log.add("$label: ${if (next != null) "nueva canción en ${elapsed}ms" else "NO cambió en 5 s"}")
        return if (next != null) elapsed else null
    }

    fun probeWebApiSkip() = work("Siguiente vía Web API…") {
        val fromUri = remote.nowPlaying.value?.track?.uri
        val start = SystemClock.elapsedRealtime()
        val code = webApi.skipNext()
        val next = withTimeoutOrNull(5_000) { remote.nowPlaying.first { it != null && it.track.uri != fromUri } }
        log.add("Web API next → HTTP $code, cambio visto en ${if (next != null) "${SystemClock.elapsedRealtime() - start}ms" else "nunca (5 s)"}")
    }

    // ---- 4. Report --------------------------------------------------------------------------------------------

    fun writeReport(context: Context) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val spotifyVersion = runCatching { context.packageManager.getPackageInfo("com.spotify.music", 0).versionName }.getOrNull()
        val report = buildString {
            appendLine("# DJ Rodri · informe fase 0 ($stamp)")
            appendLine()
            appendLine("- Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- Spotify instalado: ${spotifyVersion ?: "no encontrado"}")
            appendLine("- Login Web API: ${authSummary ?: "no probado"}")
            appendLine("- App Remote conectado: ${remote.connected.value}")
            appendLine("- PlayerState recibidos: ${remote.events.value.size}, sin canción: ${remote.events.value.count { it.second.track == null }}")
            appendLine("- Crossfade: ${crossfadeSummary ?: "no leído"}")
            appendLine("- Deriva de posición: ${LatencyStats.summarize(drifts) ?: "no medida"}")
            appendLine("- Error del corte: ${LatencyStats.summarize(cutErrors) ?: "no medido"}")
            appendLine("- Latencia de salto (App Remote): ${LatencyStats.summarize(skipLatencies) ?: "no medida"}")
            appendLine("- Entrada en drop, hasta confirmar posición: ${LatencyStats.summarize(seekLatencies) ?: "no medida"}")
            appendLine("- Cobertura: ${coverageSummary ?: "no medida"}")
            appendLine("- Calificaciones: ${ratings.ifEmpty { listOf("ninguna") }.joinToString("; ")}")
            appendLine()
            appendLine("## Log")
            appendLine("```")
            log.lines.value.forEach(::appendLine)
            appendLine("```")
        }
        val file = File(context.getExternalFilesDir(null), "spike-$stamp.md")
        file.writeText(report)
        reportPath.value = file.absolutePath
        log.add("Informe guardado: ${file.absolutePath}")
    }

    private fun work(label: String, block: suspend () -> Unit) {
        if (busy.value != null) return log.add("Espera: $label (ocupado con ${busy.value})")
        busy.value = label
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                log.add("ERROR en '$label': ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy.value = null
            }
        }
    }

    private companion object {
        const val QUEUE_SETTLE_MS = 800L
        const val FADE_MARGIN_MS = 1_000L
    }

    override fun onCleared() {
        // The connection belongs to the app; only stop writing into this screen's log.
        remote.logger = { android.util.Log.d("DJRodri", it) }
    }
}
