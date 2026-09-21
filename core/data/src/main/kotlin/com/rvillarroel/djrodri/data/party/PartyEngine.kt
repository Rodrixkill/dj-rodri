package com.rvillarroel.djrodri.data.party

import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.domain.dj.CueEstimator
import com.rvillarroel.djrodri.domain.dj.DjIntent
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.DjSession
import com.rvillarroel.djrodri.domain.dj.DjSessionState
import com.rvillarroel.djrodri.domain.dj.Entry
import com.rvillarroel.djrodri.domain.dj.Exit
import com.rvillarroel.djrodri.domain.dj.PlayCommand
import com.rvillarroel.djrodri.domain.dj.SessionStats
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.dj.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

data class PartyTrack(val source: SourceTrack, val profile: TrackProfile)

data class PartySettings(
    val mode: DjMode = DjMode.FIESTA,
    /** Pause for an instant before jumping into a drop, so the first notes of the song are never heard. */
    val dropWithPause: Boolean = false,
    /** Fire a cut this early to compensate for the time Spotify takes to switch. Tuned from the spike. */
    val cutLeadMs: Long = 150,
    /** A song playing whole is handed over this close to its end. */
    val endLeadMs: Long = 400,
    /**
     * Let Spotify's own crossfade do every change: the next song is queued, and a cut sends the current song to its
     * last seconds so the fade starts there. Only when crossfade is on in Spotify; otherwise changes are clean cuts.
     */
    val useSpotifyFade: Boolean = true,
)

enum class PartyEventKind {
    FULL, CUT, DROP_ENTRY, SKIP, PREVIOUS, DISLIKE, KEEP_FULL, EXTERNAL_CHANGE,
    /** A song's drop replaced by the next one's, by the DJ or by you. */
    DROP_SWAP,
    /** You asked for Spotify's fade right now. */
    FADE_NOW,
    /** You sent the song back to its drop, to play it again. */
    PULL_UP,
}

/**
 * A party frozen on disk. HyperOS kills background apps without asking, so the party is saved on every song and
 * every few seconds: reopening the app offers to carry on from the exact song and second.
 */
data class PartySnapshot(
    val sessionId: Long,
    val playlistId: String,
    val settings: PartySettings,
    val currentTrackId: String,
    val positionMs: Long,
    val upcomingIds: List<String>,
    val playedIds: List<String>,
    val transitions: List<Transition>,
    val stats: SessionStats,
    val intent: DjIntent,
    val savedAtMs: Long,
)

interface PartySnapshotStore {
    suspend fun save(snapshot: PartySnapshot)
    suspend fun load(): PartySnapshot?
    suspend fun clear()
}

/** Persistence for what happens at a party. The app implements it with Room. */
interface PartyRecorder {
    suspend fun sessionStarted(playlistId: String, mode: DjMode, atMs: Long): Long
    suspend fun event(sessionId: Long, trackId: String, kind: PartyEventKind, positionMs: Long, atMs: Long)
    suspend fun statsChanged(sessionId: Long, stats: SessionStats, endedAtMs: Long?)
    suspend fun dropMarked(trackId: String, positionMs: Long, atMs: Long)
}

data class PartyState(
    val active: Boolean = false,
    val playlistId: String? = null,
    val current: SourceTrack? = null,
    val next: SourceTrack? = null,
    val plan: Transition? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPaused: Boolean = false,
    val settings: PartySettings = PartySettings(),
    val stats: SessionStats = SessionStats(),
    val intent: DjIntent = DjIntent.Default,
    /** Set when Spotify is playing something the DJ did not choose; the DJ stops deciding until you press ⏭. */
    val externalTrackUri: String? = null,
    /** Your drop swap, waiting for the current song's next drop at this position. */
    val swapAtMs: Long? = null,
    /** Why the next change is what it is: "Moderada · reggaetón → hip hop, estilos cercanos". */
    val planNote: String? = null,
)

/**
 * Runs a [DjSession] against a real player: watches the position, fires cuts and drop entries on time, hands over
 * at the end of whole songs, and never fights you — if Spotify changes song on its own or from the Spotify app,
 * the DJ steps back until you give it control again.
 */
class PartyEngine(
    private val player: PlayerPort,
    private val recorder: PartyRecorder,
    private val snapshots: PartySnapshotStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    private val tickMs: Long = 100,
) {
    private val _state = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = _state

    /** The analyzed songs of the party, so a request like "estilo Avicii" can be read off your own library. */
    val profiles: List<TrackProfile> get() = tracks.values.map { it.profile }

    private val lock = Mutex()
    private var session: DjSession? = null
    private var sessionId = 0L
    private var tracks: Map<String, PartyTrack> = emptyMap()
    private var settings = PartySettings()
    private var lastCommandAtMs = 0L
    private var external: String? = null
    private var lastSnapshotAtMs = 0L
    private var ticker: Job? = null

    /** Spotify's crossfade length while fades are in use; 0 means clean cuts. */
    private var fadeMs = 0L

    /**
     * A change Spotify is making for us: [uri] is queued and the fade goes to it. [fadeFromMs] is where a cut sends
     * the current song to its last seconds; null means the song simply runs into its own fade.
     */
    private class Handoff(
        val fromUri: String,
        val uri: String,
        val entryMs: Long,
        val fadeFromMs: Long?,
        var deadlineMs: Long,
        var lastSeenMs: Long,
    ) {
        var fadeStarted = false
    }

    private var handoff: Handoff? = null

    /** Where your drop swap fires: the current song's next drop. */
    private var pendingSwapAtMs: Long? = null

    suspend fun start(playlistId: String, playlist: List<PartyTrack>, settings: PartySettings) = lock.withLock {
        stopTicker()
        this.settings = settings
        tracks = playlist.associateBy { it.source.id }
        val newSession = DjSession(playlist.map { it.profile }, settings.mode, random)
        session = newSession
        sessionId = recorder.sessionStarted(playlistId, settings.mode, clock())
        _state.value = PartyState(active = true, playlistId = playlistId, settings = settings)
        refreshFade()
        execute(newSession.start())
        startTicker()
    }

    /**
     * Carries on a party the system killed: same order, same song, same second, same cut budget.
     * Songs missing from the playlist now (removed since) are simply dropped from what is left.
     */
    suspend fun resume(snapshot: PartySnapshot, playlist: List<PartyTrack>): Boolean = lock.withLock {
        stopTicker()
        tracks = playlist.associateBy { it.source.id }
        val profiles = tracks.mapValues { it.value.profile }
        val current = profiles[snapshot.currentTrackId] ?: return@withLock false
        settings = snapshot.settings
        sessionId = snapshot.sessionId
        session = DjSession(
            tracks = profiles.values.toList(),
            mode = snapshot.settings.mode,
            random = random,
            restored = DjSessionState(
                current = current,
                upcoming = snapshot.upcomingIds.mapNotNull { profiles[it] },
                played = snapshot.playedIds.mapNotNull { profiles[it] },
                transitions = snapshot.transitions,
                stats = snapshot.stats,
                intent = snapshot.intent,
            ),
        )
        _state.value = PartyState(active = true, playlistId = snapshot.playlistId, settings = settings)
        refreshFade()

        val uri = uriOf(current.id)
        val playing = player.state.value
        if (playing?.trackUri != uri) {
            // Spotify moved on (or stopped) while the app was dead: pick the song up where it was left.
            lastCommandAtMs = clock()
            player.play(uri)
            withTimeoutOrNull(SWITCH_TIMEOUT_MS) { player.state.first { it?.trackUri == uri } }
            if (snapshot.positionMs > 0) player.seekTo(snapshot.positionMs)
        }
        publish()
        startTicker()
        true
    }

    private fun startTicker() {
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                lock.withLock { tick() }
            }
        }
    }

    fun next() = act { s -> record(PartyEventKind.SKIP); execute(s.skip()) }
    fun previous() = act { s -> record(PartyEventKind.PREVIOUS); s.previous()?.let { execute(it) } }
    fun dislike() = act { s -> record(PartyEventKind.DISLIKE); execute(s.dislike()) }
    fun keepFull() = act { s -> record(PartyEventKind.KEEP_FULL); s.keepFull(); publish() }
    /** What you asked for, already turned into numbers by the rules or the local model. */
    fun applyIntent(intent: DjIntent) = act { s ->
        s.applyIntent(intent)
        // A request like "a fondo" also changes how boldly the DJ cuts.
        if (s.mode != settings.mode) settings = settings.copy(mode = s.mode)
        publish()
    }

    /**
     * 💥 Your drop swap: waits for the current song's next drop and, right there, the next song comes in on its
     * drop. With no drop close enough ahead, it swaps now.
     */
    fun dropSwap() = act { s ->
        val position = currentPosition(s) ?: return@act
        val song = s.current ?: return@act
        val nextDrop = CueEstimator.estimate(song).drops
            .firstOrNull { it > position + MIN_SWAP_WAIT_MS }
            ?.takeIf { it - position <= MAX_SWAP_WAIT_MS }
        if (nextDrop == null) {
            record(PartyEventKind.DROP_SWAP)
            execute(s.moveOn(s.nextDropMs()))
        } else {
            pendingSwapAtMs = nextDrop
            publish()
        }
    }

    /** 🌊 Spotify's fade into the next song, now, wherever the current one is. Without crossfade, a clean change. */
    fun fadeNow() = act { s ->
        val playing = player.state.value ?: return@act
        val position = currentPosition(s) ?: return@act
        record(PartyEventKind.FADE_NOW)
        if (fadeMs == 0L) return@act execute(s.moveOn(0))
        val now = clock()
        // The queue needs a moment before the jump, or the fade goes to Spotify's own pick.
        val fadeFrom = position + QUEUE_LEAD_MS
        beginHandoff(playing, s.moveOn(0), fadeFrom, target = fadeFrom, position, now)
        publish()
    }

    /** 🔁 Once more: the song goes back to the drop the crowd just heard, the pull-up of reggaeton DJs. */
    fun pullUp() = act { s ->
        // Mid-handoff the next song is already queued: rewinding now would fade into it at the wrong time.
        if (handoff != null) return@act
        val position = currentPosition(s) ?: return@act
        val drops = CueEstimator.estimate(s.current ?: return@act).drops
        val back = drops.lastOrNull { it <= position - PULL_UP_MIN_MS } ?: drops.firstOrNull() ?: return@act
        record(PartyEventKind.PULL_UP)
        pendingSwapAtMs = null
        lastCommandAtMs = clock()
        player.seekTo(back)
        publish()
    }

    private fun currentPosition(s: DjSession): Long? {
        val playing = player.state.value ?: return null
        val song = s.current ?: return null
        return playing.takeIf { it.trackUri == uriOf(song.id) }?.positionAt(clock())
    }

    fun moreEnergy() = act { s -> s.moreEnergy(); publish() }
    fun lessEnergy() = act { s -> s.lessEnergy(); publish() }

    fun changeMode(mode: DjMode) = act { s ->
        settings = settings.copy(mode = mode)
        s.changeMode(mode)
        publish()
    }

    fun updateSettings(newSettings: PartySettings) = act { s ->
        if (newSettings.mode != settings.mode) s.changeMode(newSettings.mode)
        settings = newSettings
        refreshFade()
        publish()
    }

    fun togglePause() = act {
        val paused = player.state.value?.isPaused ?: return@act
        if (paused) player.resume() else player.pause()
    }

    /** "¡Drop!": remember where the drop of the current song really is, for every future party. */
    fun markDrop() = act { s ->
        val song = s.current ?: return@act
        val playing = player.state.value ?: return@act
        if (playing.trackUri != uriOf(song.id)) return@act
        recorder.dropMarked(song.id, playing.positionAt(clock()), clock())
    }

    fun switchPlaylist(playlistId: String, playlist: List<PartyTrack>) = act { s ->
        tracks = tracks + playlist.associateBy { it.source.id }
        s.switchPlaylist(playlist.map { it.profile })
        _state.value = _state.value.copy(playlistId = playlistId)
        publish()
    }

    fun stop() = act { finish() }

    private fun act(block: suspend (DjSession) -> Unit) {
        scope.launch {
            lock.withLock {
                val s = session ?: return@withLock
                block(s)
                // Any command from you hands control back to the DJ.
                external = null
            }
        }
    }

    private suspend fun tick() {
        val s = session ?: return
        val song = s.current ?: return
        val playing = player.state.value ?: return
        val now = clock()

        handoff?.let { h ->
            followHandoff(h, playing, now)
            publish()
            return
        }

        if (playing.trackUri != uriOf(song.id)) {
            // Spotify takes a moment to report the song we just asked for.
            if (now - lastCommandAtMs < SWITCH_GRACE_MS) return
            if (external == null && playing.trackUri != null) {
                external = playing.trackUri
                record(PartyEventKind.EXTERNAL_CHANGE)
            }
            publish()
            return
        }

        val position = playing.positionAt(now)
        val plan = s.plan
        val cut = plan?.exit as? Exit.CutAt
        val swapAt = pendingSwapAtMs
        when {
            playing.isPaused && position >= playing.durationMs - STOPPED_AT_END_MS -> {
                // The app slept through the end and Spotify stopped: carry on as if it ended normally.
                record(PartyEventKind.FULL)
                execute(s.onTrackEnded())
            }
            playing.isPaused -> Unit
            swapAt != null && position >= swapAt - settings.cutLeadMs -> {
                // Your drop swap, on the drop you were waiting for.
                record(PartyEventKind.DROP_SWAP)
                execute(s.moveOn(s.nextDropMs()))
            }
            // The DJ's drop swap is always a dry cut: a fade would blur the hit.
            cut != null && cut.swap && position >= cut.positionMs - settings.cutLeadMs -> {
                record(PartyEventKind.DROP_SWAP)
                execute(s.onCutPointReached())
            }
            fadeMs > 0 && cut != null && cut.blend && position >= cut.positionMs - settings.cutLeadMs - QUEUE_LEAD_MS -> {
                val cutMs = cut.positionMs
                record(if (plan.entry is Entry.AtDrop) PartyEventKind.DROP_ENTRY else PartyEventKind.CUT)
                // A cut already inside the song's last seconds needs no jump: its own fade is about to start.
                val fadeFrom = cutMs.takeIf { it < fadeStartOf(playing) }
                beginHandoff(playing, s.onCutPointReached(), fadeFrom, target = fadeFrom ?: fadeStartOf(playing), position, now)
            }
            fadeMs > 0 && position >= fadeStartOf(playing) - QUEUE_LEAD_MS -> {
                record(PartyEventKind.FULL)
                beginHandoff(playing, s.onTrackEnded(), fadeFromMs = null, target = fadeStartOf(playing), position, now)
            }
            cut != null && position >= cut.positionMs - settings.cutLeadMs -> {
                record(if (plan.entry is Entry.AtDrop) PartyEventKind.DROP_ENTRY else PartyEventKind.CUT)
                execute(s.onCutPointReached())
            }
            position >= playing.durationMs - settings.endLeadMs -> {
                record(PartyEventKind.FULL)
                execute(s.onTrackEnded())
            }
        }
        if (clock() - lastSnapshotAtMs >= SNAPSHOT_EVERY_MS) saveSnapshot()
        publish()
    }

    private suspend fun execute(command: PlayCommand?) {
        if (command == null) {
            finish()
            return
        }
        // Whatever Spotify was fading into, and any swap you were waiting for, this command decides now.
        handoff = null
        pendingSwapAtMs = null
        switchNow(uriOf(command.trackId), command.fromMs)
        publish()
        session?.let { recorder.statsChanged(sessionId, it.stats, null) }
        saveSnapshot()
    }

    /** The clean change: play the song, and land on its drop if it enters there. */
    private suspend fun switchNow(uri: String, fromMs: Long) {
        lastCommandAtMs = clock()
        external = null
        if (fromMs > 0) {
            if (settings.dropWithPause) player.pause()
            player.play(uri)
            withTimeoutOrNull(SWITCH_TIMEOUT_MS) { player.state.first { it?.trackUri == uri } }
            player.seekTo(fromMs)
            if (settings.dropWithPause) player.resume()
        } else {
            player.play(uri)
        }
    }

    private suspend fun refreshFade() {
        fadeMs = if (settings.useSpotifyFade) player.crossfadeMs() ?: 0 else 0
    }

    /** Where Spotify starts fading out of [playing] on its own. */
    private fun fadeStartOf(playing: PlayerState) = (playing.durationMs - fadeMs).coerceAtLeast(0)

    /**
     * Hands the change to Spotify: the next song goes to the queue a moment before the fade, so the fade goes to it.
     * [target] is the position of the current song where the fade will start; past it plus a margin, the DJ stops
     * waiting and switches by itself.
     */
    private suspend fun beginHandoff(playing: PlayerState, command: PlayCommand?, fadeFromMs: Long?, target: Long, position: Long, now: Long) {
        if (command == null) {
            finish()
            return
        }
        val uri = uriOf(command.trackId)
        player.queue(uri)
        lastCommandAtMs = now
        handoff = Handoff(
            fromUri = playing.trackUri ?: "",
            uri = uri,
            entryMs = command.fromMs,
            fadeFromMs = fadeFromMs,
            deadlineMs = now + (target - position).coerceAtLeast(0) + HANDOFF_TIMEOUT_MS,
            lastSeenMs = now,
        )
        session?.let { recorder.statsChanged(sessionId, it.stats, null) }
    }

    private suspend fun followHandoff(h: Handoff, playing: PlayerState, now: Long) {
        // A pause stops the song, so it also stops the wait.
        if (playing.isPaused) h.deadlineMs += now - h.lastSeenMs
        h.lastSeenMs = now
        when {
            playing.trackUri == h.uri -> {
                // Spotify is fading into our song. Jumping to its drop now keeps the fade: tested on the phone.
                handoff = null
                lastCommandAtMs = now
                if (h.entryMs > 0) player.seekTo(h.entryMs)
                saveSnapshot()
            }
            playing.trackUri != h.fromUri || now >= h.deadlineMs -> {
                // Spotify faded into its own pick, or never faded: make the change the clean way.
                handoff = null
                switchNow(h.uri, h.entryMs)
                saveSnapshot()
            }
            playing.isPaused -> Unit
            h.fadeFromMs != null && !h.fadeStarted && playing.positionAt(now) >= h.fadeFromMs - settings.cutLeadMs -> {
                // The cut: send the song to where Spotify starts its fade, a second early so the fade begins at once.
                h.fadeStarted = true
                val fadeAt = (fadeStartOf(playing) - FADE_MARGIN_MS).coerceAtLeast(0)
                // Already past it (a fade asked for in the last seconds): the song's own fade is starting anyway.
                if (fadeAt > playing.positionAt(now)) player.seekTo(fadeAt)
            }
        }
    }

    /** Saved on every song and every few seconds, so a killed app loses seconds, not the party. */
    private suspend fun saveSnapshot() {
        val s = session ?: return
        val state = s.snapshot()
        val current = state.current ?: return
        lastSnapshotAtMs = clock()
        snapshots.save(
            PartySnapshot(
                sessionId = sessionId,
                playlistId = _state.value.playlistId ?: return,
                settings = settings,
                currentTrackId = current.id,
                positionMs = player.state.value?.takeIf { it.trackUri == uriOf(current.id) }?.positionAt(clock()) ?: 0,
                upcomingIds = state.upcoming.map { it.id },
                playedIds = state.played.map { it.id },
                transitions = state.transitions,
                stats = state.stats,
                intent = state.intent,
                savedAtMs = clock(),
            ),
        )
    }

    private suspend fun finish() {
        val s = session ?: return
        stopTicker()
        recorder.statsChanged(sessionId, s.stats, clock())
        // A party that ended on purpose must not be offered again when the app opens.
        snapshots.clear()
        session = null
        _state.value = _state.value.copy(active = false, current = null, next = null, plan = null, stats = s.stats)
    }

    private fun stopTicker() {
        // May run inside the ticker itself (the last song ended): cancelling is safe, it stops at its next delay.
        val job = ticker
        ticker = null
        job?.cancel()
    }

    private suspend fun record(kind: PartyEventKind) {
        val s = session ?: return
        val song = s.current ?: return
        val position = player.state.value?.takeIf { it.trackUri == uriOf(song.id) }?.positionAt(clock()) ?: 0
        recorder.event(sessionId, song.id, kind, position, clock())
    }

    private fun publish() {
        val s = session ?: return
        val playing = player.state.value
        val song = s.current
        val isCurrent = song != null && playing?.trackUri == uriOf(song.id)
        _state.value = _state.value.copy(
            active = true,
            current = song?.let { tracks[it.id]?.source },
            next = s.upNext?.let { tracks[it.id]?.source },
            plan = s.plan,
            positionMs = if (isCurrent) playing!!.positionAt(clock()) else 0,
            durationMs = song?.durationMs ?: 0,
            isPaused = playing?.isPaused ?: false,
            settings = settings,
            stats = s.stats,
            intent = s.intent,
            externalTrackUri = external,
            swapAtMs = pendingSwapAtMs,
            planNote = s.note,
        )
    }

    private fun uriOf(trackId: String) = tracks[trackId]?.source?.uri ?: "spotify:track:$trackId"

    private companion object {
        const val SWITCH_GRACE_MS = 3_000L
        const val SWITCH_TIMEOUT_MS = 3_000L
        const val STOPPED_AT_END_MS = 1_500L
        const val SNAPSHOT_EVERY_MS = 10_000L
        /** How early the next song is queued: the spike showed Spotify needs well under a second to take it. */
        const val QUEUE_LEAD_MS = 1_500L
        /** A cut lands this far before the fade start, so the fade begins the moment the jump lands. */
        const val FADE_MARGIN_MS = 1_000L
        const val HANDOFF_TIMEOUT_MS = 4_000L
        /** A drop closer than this cannot be waited for: the swap would fire before you let go of the button. */
        const val MIN_SWAP_WAIT_MS = 1_500L
        /** Nobody waits a minute for a swap they asked for: beyond this it happens now. */
        const val MAX_SWAP_WAIT_MS = 45_000L
        /** "Once more" replays the drop just heard, not one that only just started. */
        const val PULL_UP_MIN_MS = 2_000L
    }
}
