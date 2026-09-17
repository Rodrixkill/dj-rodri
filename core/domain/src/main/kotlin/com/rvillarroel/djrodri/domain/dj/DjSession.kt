package com.rvillarroel.djrodri.domain.dj

import kotlin.random.Random

/** What the player adapter must do next. How (App Remote skip, play, seek) is the adapter's business. */
data class PlayCommand(val trackId: String, val fromMs: Long)

data class SessionStats(
    val fullPlays: Int = 0,
    val cuts: Int = 0,
    val dropEntries: Int = 0,
    /** The key quality metric: every time you skip, the DJ chose badly. */
    val manualSkips: Int = 0,
    val dislikes: Int = 0,
)

/**
 * One party: the virtual queue and every control you have over it. Pure state, no Spotify, no clock —
 * the adapter reports what happened ([onCutPointReached], [onTrackEnded]) and executes the returned [PlayCommand].
 */
class DjSession(
    tracks: List<TrackProfile>,
    mode: DjMode = DjMode.FIESTA,
    private val random: Random = Random.Default,
) {
    var mode: DjMode = mode
        private set

    var current: TrackProfile? = null
        private set

    /** How [current] ends and how the next song begins. Recomputed whenever anything changes. */
    var plan: Transition? = null
        private set

    var stats = SessionStats()
        private set

    /** The last thing you asked for ("algo para perrear"), still shaping what comes next. */
    var intent: DjIntent = DjIntent.Default
        private set

    private var upcoming: MutableList<TrackProfile> = SetBuilder.order(tracks, random = random).toMutableList()
    private val played = ArrayDeque<TrackProfile>()
    private val djTransitions = mutableListOf<Transition>()
    private val disliked = mutableSetOf<String>()
    private var keepCurrentFull = false
    private var energyBias = 0.0

    val upNext: TrackProfile? get() = upcoming.firstOrNull()
    val queue: List<TrackProfile> get() = upcoming.toList()

    fun start(): PlayCommand? {
        val first = upcoming.removeFirstOrNull() ?: return null
        current = first
        replan()
        return PlayCommand(first.id, 0)
    }

    /** The adapter saw the position reach the planned cut. */
    fun onCutPointReached(): PlayCommand? {
        val transition = plan ?: return null
        if (transition.exit !is Exit.CutAt) return null
        stats = stats.copy(
            cuts = stats.cuts + 1,
            dropEntries = stats.dropEntries + if (transition.entry is Entry.AtDrop) 1 else 0,
        )
        return advance(transition)
    }

    /** The song ended on its own (planned Full, or the cut was missed). */
    fun onTrackEnded(): PlayCommand? {
        stats = stats.copy(fullPlays = stats.fullPlays + 1)
        return advance(Transition(Exit.Full, Entry.FromStart))
    }

    /** ⏭ You skipped: counted against the DJ, but it does not spend the cut budget. */
    fun skip(): PlayCommand? {
        stats = stats.copy(manualSkips = stats.manualSkips + 1)
        return advance(recorded = null)
    }

    /** "No va": skip, and this song does not come back tonight, not even from another playlist. */
    fun dislike(): PlayCommand? {
        val song = current ?: return null
        disliked += song.id
        upcoming.removeAll { it.id in disliked }
        stats = stats.copy(dislikes = stats.dislikes + 1)
        return advance(recorded = null)
    }

    /** ⏮ Back to the previous song; the one playing now becomes next again. */
    fun previous(): PlayCommand? {
        val back = played.removeLastOrNull() ?: return null
        current?.let { upcoming.add(0, it) }
        current = back
        keepCurrentFull = false
        replan()
        return PlayCommand(back.id, 0)
    }

    /** "Completa": this song plays to the end, whatever the plan was. */
    fun keepFull() {
        keepCurrentFull = true
        replan()
    }

    /** "Pon algo para perrear": reorders what is left, and may change the mode if you asked for something wilder. */
    fun applyIntent(newIntent: DjIntent) {
        intent = newIntent
        newIntent.mode?.let { mode = it }
        reorderUpcoming()
        replan()
    }

    fun moreEnergy() = shiftEnergy(+ENERGY_STEP)

    fun lessEnergy() = shiftEnergy(-ENERGY_STEP)

    fun changeMode(newMode: DjMode) {
        mode = newMode
        replan()
    }

    /** Switch playlists mid-party: the current song keeps playing and the new set flows from it. */
    fun switchPlaylist(tracks: List<TrackProfile>) {
        val alreadyHeard = played.map { it.id }.toSet() + listOfNotNull(current?.id)
        upcoming = tracks.filter { it.id !in disliked && it.id !in alreadyHeard }.toMutableList()
        reorderUpcoming()
        replan()
    }

    private fun shiftEnergy(step: Double) {
        energyBias = (energyBias + step).coerceIn(-MAX_ENERGY_BIAS, MAX_ENERGY_BIAS)
        reorderUpcoming()
        replan()
    }

    private fun reorderUpcoming() {
        upcoming = SetBuilder.order(upcoming, after = current, energyBias = energyBias, intent = intent, random = random).toMutableList()
    }

    private fun advance(recorded: Transition?): PlayCommand? {
        val finished = current ?: return null
        recorded?.let { djTransitions += it }
        played.addLast(finished)
        val next = upcoming.removeFirstOrNull()
        current = next
        keepCurrentFull = false
        if (next == null) {
            plan = null
            return null
        }
        replan()
        val fromMs = (recorded?.entry as? Entry.AtDrop)?.positionMs ?: 0
        return PlayCommand(next.id, fromMs)
    }

    private fun replan() {
        val song = current
        plan = song?.let { TransitionPlanner.plan(it, upcoming.firstOrNull(), djTransitions, mode, keepCurrentFull) }
    }

    private companion object {
        const val ENERGY_STEP = 0.15
        const val MAX_ENERGY_BIAS = 0.3
    }
}
