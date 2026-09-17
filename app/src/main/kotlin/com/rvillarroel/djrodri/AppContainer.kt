package com.rvillarroel.djrodri

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.rvillarroel.djrodri.ai.LiteRtLlmEngine
import com.rvillarroel.djrodri.analysis.AnalysisScheduler
import com.rvillarroel.djrodri.data.ai.DjContext
import com.rvillarroel.djrodri.data.ai.InterpretedIntent
import com.rvillarroel.djrodri.data.ai.ArtistStyleResolver
import com.rvillarroel.djrodri.data.ai.LlmIntentInterpreter
import com.rvillarroel.djrodri.data.ai.RulesIntentInterpreter
import com.rvillarroel.djrodri.data.analysis.PlaylistAnalyzer
import com.rvillarroel.djrodri.data.http.PoliteHttp
import com.rvillarroel.djrodri.data.party.PartyEngine
import com.rvillarroel.djrodri.data.party.PartyTrack
import com.rvillarroel.djrodri.data.sources.LrclibClient
import com.rvillarroel.djrodri.data.sources.MusicBrainzClient
import com.rvillarroel.djrodri.data.sources.ReccoBeatsClient
import com.rvillarroel.djrodri.library.LibraryRepository
import com.rvillarroel.djrodri.library.db.DjDatabase
import com.rvillarroel.djrodri.library.db.RoomAnalysisStore
import com.rvillarroel.djrodri.library.db.RoomPartyRecorder
import com.rvillarroel.djrodri.party.PartyService
import com.rvillarroel.djrodri.settings.SettingsRepository
import com.rvillarroel.djrodri.spotify.AppRemotePlayer
import com.rvillarroel.djrodri.spotify.RemoteController
import com.rvillarroel.djrodri.spotify.SpotifyAuth
import com.rvillarroel.djrodri.spotify.SpotifyWebApi
import com.rvillarroel.djrodri.spotify.TokenStore
import com.rvillarroel.djrodri.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Hand-wired dependencies: one app, one phone, a dozen objects. A DI framework would add build time and no clarity.
 * Everything that must outlive a screen (the party, the Spotify connection) lives here.
 */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val database by lazy { DjDatabase.create(context) }
    val settings = SettingsRepository(context)
    val auth = SpotifyAuth(TokenStore(context), http)
    val webApi = SpotifyWebApi(http, token = { force -> auth.accessToken(force) })
    val library by lazy { LibraryRepository(webApi, database.library()) }

    private val politeHttp = PoliteHttp(http)
    private val musicBrainz by lazy { MusicBrainzClient(politeHttp) }
    private val styleResolver by lazy { ArtistStyleResolver(musicBrainz) }
    val analyzer by lazy {
        PlaylistAnalyzer(
            lyricsSource = LrclibClient(politeHttp),
            featuresSource = ReccoBeatsClient(politeHttp),
            genreSource = musicBrainz,
            store = RoomAnalysisStore(database.analysis()),
        )
    }
    val analysisScheduler by lazy { AnalysisScheduler(context) }

    val llm by lazy { LiteRtLlmEngine(context) }
    private val rulesInterpreter = RulesIntentInterpreter()
    private val llmInterpreter by lazy { LlmIntentInterpreter(llm, rulesInterpreter) }

    /** Your sentence in, numbers out. The model is used only if it is installed and you left it on. */
    suspend fun interpret(request: String): InterpretedIntent {
        val djContext = DjContext(
            hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            currentEnergy = party.state.value.intent.targetEnergy,
            mode = settings.party.value.mode,
        )
        val interpreter = if (settings.useLocalAi.value) llmInterpreter else rulesInterpreter
        val interpreted = interpreter.interpret(request, djContext)
        // "estilo Avicii" is worked out from his songs in your own library, or from MusicBrainz genres.
        return interpreted.copy(intent = styleResolver.resolve(interpreted.intent, party.profiles))
    }

    val voice by lazy { VoiceInput(context) }

    val remote = RemoteController()
    val party by lazy {
        PartyEngine(
            player = AppRemotePlayer(remote, appScope),
            recorder = RoomPartyRecorder(database.party(), database.analysis()),
            scope = appScope,
            clock = SystemClock::elapsedRealtime,
        )
    }

    /** Connects to Spotify if needed. Needs an Activity the first time, for Spotify's own permission screen. */
    suspend fun ensureRemoteConnected(activityContext: Context): Boolean {
        if (remote.connected.value) return true
        remote.connect(activityContext)
        return withTimeoutOrNull(REMOTE_CONNECT_TIMEOUT_MS) { remote.connected.first { it } } ?: false
    }

    suspend fun partyTracks(playlistId: String): List<PartyTrack> {
        val tracks = library.tracks(playlistId)
        val profiles = analyzer.profiles(tracks).associateBy { it.id }
        return tracks.distinctBy { it.id }.map { PartyTrack(it, profiles.getValue(it.id)) }
    }

    suspend fun startParty(activityContext: Context, playlistId: String): String? {
        if (!ensureRemoteConnected(activityContext)) return "No se pudo conectar con la app de Spotify. ¿Está instalada y con sesión?"
        val tracks = runCatching { partyTracks(playlistId) }.getOrElse { return it.message ?: "No se pudieron cargar las canciones" }
        if (tracks.isEmpty()) return "Esta playlist no tiene canciones que Spotify nos deje leer"
        settings.rememberPlaylist(playlistId)
        party.start(playlistId, tracks, settings.party.value)
        ContextCompat.startForegroundService(context, PartyService.startIntent(context))
        return null
    }

    suspend fun switchParty(playlistId: String): String? {
        val tracks = runCatching { partyTracks(playlistId) }.getOrElse { return it.message }
        settings.rememberPlaylist(playlistId)
        party.switchPlaylist(playlistId, tracks)
        return null
    }

    private companion object {
        const val REMOTE_CONNECT_TIMEOUT_MS = 20_000L
    }
}

class DjRodriApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as DjRodriApplication).container
