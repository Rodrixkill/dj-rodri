package com.rvillarroel.djrodri.domain.dj

/**
 * How eager the DJ is. The budget exists so the night feels natural: most songs play whole, cuts are the exception.
 *
 * @param maxCutRatio at most this share of songs end early.
 * @param minCutConfidence a cut point below this confidence is ignored and the song plays whole.
 * @param dropEntryEvery at most one drop entry every this many transitions.
 * @param prefersEarlyCut leave at the first natural point instead of the last one.
 */
enum class DjMode(
    val maxCutRatio: Double,
    val minCutConfidence: Double,
    val dropEntryEvery: Int,
    val prefersEarlyCut: Boolean,
) {
    NATURAL(maxCutRatio = 0.25, minCutConfidence = 0.8, dropEntryEvery = 8, prefersEarlyCut = false),
    FIESTA(maxCutRatio = 0.4, minCutConfidence = 0.7, dropEntryEvery = 4, prefersEarlyCut = false),
    HORA_LOCA(maxCutRatio = 0.6, minCutConfidence = 0.6, dropEntryEvery = 2, prefersEarlyCut = true),
}

sealed interface Exit {
    /** Let Spotify finish the song; its own crossfade does the transition. */
    data object Full : Exit

    data class CutAt(val positionMs: Long) : Exit
}

sealed interface Entry {
    data object FromStart : Entry

    /** Skip straight into the next song's drop: the move that makes it sound like someone is mixing. */
    data class AtDrop(val positionMs: Long) : Entry
}

data class Transition(val exit: Exit, val entry: Entry)

object TransitionPlanner {
    /** Short songs are already radio edits; cutting them feels like a mistake. */
    const val SHORT_SONG_MS = 180_000L

    /** Landing in the wrong spot is worse than not trying, so drop entries need more certainty than cuts. */
    const val MIN_DROP_CONFIDENCE = 0.8

    /** A drop entry should lift the room, not bring it down. */
    private const val ENERGY_DROP_TOLERANCE = 0.05

    /**
     * Decides how [current] ends and how [next] begins, given the DJ's own [past] transitions this session.
     * Manual skips are not in [past]: they are your decisions, not the DJ's, and must not spend its budget.
     */
    fun plan(
        current: TrackProfile,
        next: TrackProfile?,
        past: List<Transition>,
        mode: DjMode,
        keepFull: Boolean = false,
    ): Transition {
        val exit = chooseExit(current, past, mode, keepFull)
        // A drop entry only makes sense after a cut: after a natural ending Spotify is already fading into the start.
        val entry = if (next != null && exit is Exit.CutAt) chooseEntry(current, next, past, mode) else Entry.FromStart
        return Transition(exit, entry)
    }

    private fun chooseExit(current: TrackProfile, past: List<Transition>, mode: DjMode, keepFull: Boolean): Exit {
        if (keepFull || current.durationMs < SHORT_SONG_MS) return Exit.Full
        if (past.lastOrNull()?.exit is Exit.CutAt) return Exit.Full

        // Counting this song in the ratio means the first songs of the night always play whole: a warm-up.
        val cutsIfWeCut = past.count { it.exit is Exit.CutAt } + 1
        if (cutsIfWeCut.toDouble() / (past.size + 1) > mode.maxCutRatio) return Exit.Full

        val candidates = CueEstimator.estimate(current).cuts.filter { it.confidence >= mode.minCutConfidence }
        if (candidates.isEmpty()) return Exit.Full
        val chosen = if (mode.prefersEarlyCut) candidates.first() else candidates.last()
        return Exit.CutAt(chosen.positionMs)
    }

    private fun chooseEntry(current: TrackProfile, next: TrackProfile, past: List<Transition>, mode: DjMode): Entry {
        val drop = CueEstimator.estimate(next).drop ?: return Entry.FromStart
        if (drop.confidence < MIN_DROP_CONFIDENCE) return Entry.FromStart
        if (current.energy != null && next.energy != null && next.energy < current.energy - ENERGY_DROP_TOLERANCE) {
            return Entry.FromStart
        }
        val lastDrop = past.indexOfLast { it.entry is Entry.AtDrop }
        if (lastDrop >= 0 && past.size - lastDrop < mode.dropEntryEvery) return Entry.FromStart
        return Entry.AtDrop(drop.positionMs)
    }
}
