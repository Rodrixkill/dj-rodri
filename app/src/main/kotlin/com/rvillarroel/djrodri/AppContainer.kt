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
import com.rvillarroel.djrodri.data.party.PartySnapshot
import com.rvillarroel.djrodri.data.party.PartyTrack
import com.rvillarroel.djrodri.data.sources.LrclibClient
import com.rvillarroel.djrodri.data.sources.DeezerClient
import com.rvillarroel.djrodri.data.sources.MusicBrainzClient
import com.rvillarroel.djrodri.data.sources.VotingGenreSource
import com.rvillarroel.djrodri.data.sources.ReccoBeatsClient
import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.domain.dj.DjIntent
import com.rvillarroel.djrodri.domain.dj.StyleFamily
import com.rvillarroel.djrodri.library.LibraryRepository
import com.rvillarroel.djrodri.library.db.DjDatabase
import com.rvillarroel.djrodri.library.db.RoomAnalysisStore
import com.rvillarroel.djrodri.library.db.RoomPartyRecorder
import com.rvillarroel.djrodri.library.db.RoomPartySnapshotStore
import com.rvillarroel.djrodri.library.db.toSource
import com.rvillarroel.djrodri.party.PartyService
import com.rvillarroel.djrodri.settings.SettingsRepository
import com.rvillarroel.djrodri.spotify.AppRemotePlayer
import com.rvillarroel.djrodri.spotify.RemoteController
import com.rvillarroel.djrodri.spotify.LIKED_SONGS_ID
import com.rvillarroel.djrodri.spotify.SpotifyAuth
import com.rvillarroel.djrodri.spotify.SpotifyMixSession
import com.rvillarroel.djrodri.spotify.SpotifyWebApi
import com.rvillarroel.djrodri.spotify.TokenStore
import com.rvillarroel.djrodri.voice.VoiceInput
import com.rvillarroel.djrodri.ui.TrackActions
import com.rvillarroel.djrodri.localdj.DjMixer
import com.rvillarroel.djrodri.localdj.LocalAnalysis
import com.rvillarroel.djrodri.localdj.LocalLibrary
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

    /** Deezer's label genre and MusicBrainz's voted tags, decided together. */
    private val genres by lazy { VotingGenreSource(listOf(DeezerClient(politeHttp), musicBrainz), musicBrainz) }
    private val styleResolver by lazy { ArtistStyleResolver(genres) }
    val analyzer by lazy {
        PlaylistAnalyzer(
            lyricsSource = LrclibClient(politeHttp),
            featuresSource = ReccoBeatsClient(politeHttp),
            genreSource = genres,
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

    /** Hear, queue, fix a drop: the same actions from every screen that shows a song. */
    val trackActions by lazy { TrackActions(this) }

    /** The DJ decks for your own files: two songs decoded in memory (hence the large heap in the manifest). */
    val dj by lazy { DjMixer(context, appScope, LocalLibrary(context), LocalAnalysis(context), database.localPlaylists()) }

    val remote = RemoteController()
    val party by lazy {
        PartyEngine(
            player = AppRemotePlayer(remote, appScope),
            recorder = RoomPartyRecorder(database.party(), database.analysis()),
            snapshots = RoomPartySnapshotStore(database.partySnapshot()),
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

    /** Spotify's own Mix: the whole playlist, in order, with the transitions made in Spotify. DJ Rodri only watches. */
    val spotifyMix by lazy { SpotifyMixSession(remote, library, appScope) }

    suspend fun startSpotifyMix(activityContext: Context, playlistId: String): String? {
        if (playlistId == LIKED_SONGS_ID) return "El Mix de Spotify es para playlists: «Tus me gusta» no tiene transiciones"
        if (!ensureRemoteConnected(activityContext)) return "No se pudo conectar con la app de Spotify. ¿Está instalada y con sesión?"
        // One DJ at a time: the party would cut and skip over Spotify's transitions.
        if (party.state.value.active) party.stop()
        val name = database.library().playlist(playlistId)?.name.orEmpty()
        spotifyMix.start(playlistId, name)
        return null
    }

    suspend fun startParty(activityContext: Context, playlistId: String): String? {
        if (!ensureRemoteConnected(activityContext)) return "No se pudo conectar con la app de Spotify. ¿Está instalada y con sesión?"
        spotifyMix.stop()
        val tracks = runCatching { partyTracks(playlistId) }.getOrElse { return it.message ?: "No se pudieron cargar las canciones" }
        if (tracks.isEmpty()) return "Esta playlist no tiene canciones que Spotify nos deje leer"
        settings.rememberPlaylist(playlistId)
        party.start(playlistId, tracks, settings.party.value)
        ContextCompat.startForegroundService(context, PartyService.startIntent(context))
        return null
    }

    /**
     * "Made for you": your analyzed songs grouped by style, the biggest groups first. Built only from your own
     * library, since Spotify no longer offers recommendations to apps like this one.
     */
    suspend fun styleMixes(): List<StyleMix> {
        // Only read what an analysis left behind. The one exception: the first launch after this was added,
        // when songs analyzed before get grouped once, so nobody has to analyze again.
        val index = settings.styleIndex ?: rebuildStyleIndex()
        return index.mapNotNull { (family, ids) ->
            val tracks = ids.chunked(MAX_SQL_IDS).flatMap { database.library().tracksByIds(it) }.map { it.toSource() }
            val byId = tracks.associateBy { it.id }
            StyleMix(family, ids.mapNotNull { byId[it] }).takeIf { it.tracks.size >= MIN_MIX_SONGS }
        }.sortedByDescending { it.tracks.size }
    }

    /**
     * Groups every analyzed song by style and saves it. Runs when an analysis you asked for finishes, never on its
     * own: grouping means reading the analysis of the whole library.
     */
    suspend fun rebuildStyleIndex(): Map<StyleFamily, List<String>> {
        val tracks = library.allTracks()
        val index = analyzer.profiles(tracks)
            .groupBy { StyleFamily.of(it) }
            .filterKeys { it != StyleFamily.UNKNOWN }
            .mapValues { (_, songs) -> songs.map { it.id } }
        settings.saveStyleIndex(index)
        return index
    }

    /** Plays a style mix: the playlist holding most of its songs, with the DJ asked for that style. */
    suspend fun startMix(activityContext: Context, mix: StyleMix): String? {
        val playlistId = database.library().playlistHoldingMost(mix.tracks.take(MAX_SQL_IDS).map { it.id })
            ?: return "No encontré una playlist tuya con estas canciones"
        startParty(activityContext, playlistId)?.let { return it }
        party.applyIntent(DjIntent(label = "Mezcla ${mix.family.label}", genres = StyleFamily.genresOf(mix.family)))
        return null
    }

    /** Refreshes the name and picture of the greeting; keeps the old ones when offline. */
    suspend fun refreshProfile() {
        runCatching { webApi.profile() }.getOrNull()?.let(settings::rememberProfile)
    }

    /** A party the system killed, still worth offering to carry on. */
    suspend fun restorableParty(): PartySnapshot? = RoomPartySnapshotStore(database.partySnapshot()).load()
        ?.takeIf { !party.state.value.active && System.currentTimeMillis() - it.savedAtMs < RESUMABLE_FOR_MS }

    suspend fun resumeParty(activityContext: Context): String? {
        val snapshot = restorableParty() ?: return "Ya no hay una fiesta que retomar"
        if (!ensureRemoteConnected(activityContext)) return "No se pudo conectar con la app de Spotify"
        val tracks = runCatching { partyTracks(snapshot.playlistId) }.getOrElse { return it.message }
        // The snapshot does not store the fade switch: what is in Ajustes now wins.
        val withFade = snapshot.copy(settings = snapshot.settings.copy(useSpotifyFade = settings.party.value.useSpotifyFade))
        if (!party.resume(withFade, tracks)) return "La canción que sonaba ya no está en la playlist"
        ContextCompat.startForegroundService(context, PartyService.startIntent(context))
        return null
    }

    /** Throw away the interrupted party: you did not want to carry it on. */
    suspend fun forgetParty() = RoomPartySnapshotStore(database.partySnapshot()).clear()

    suspend fun switchParty(playlistId: String): String? {
        val tracks = runCatching { partyTracks(playlistId) }.getOrElse { return it.message }
        settings.rememberPlaylist(playlistId)
        party.switchPlaylist(playlistId, tracks)
        return null
    }

    private companion object {
        const val REMOTE_CONNECT_TIMEOUT_MS = 20_000L
        const val MIN_MIX_SONGS = 8
        const val MAX_SQL_IDS = 900

        /** After this long the party is over, whatever the phone did. */
        const val RESUMABLE_FOR_MS = 3 * 60 * 60 * 1000L
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

/** "Made for you": your songs of one style, playable as a party that leans on that style. */
data class StyleMix(val family: StyleFamily, val tracks: List<SourceTrack>) {
    val covers: List<String> get() = tracks.mapNotNull { it.imageUrl }.distinct().take(4)
}
