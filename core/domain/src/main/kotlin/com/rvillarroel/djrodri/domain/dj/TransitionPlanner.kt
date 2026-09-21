package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.music.Genres
import com.rvillarroel.djrodri.domain.music.Tempo
import kotlin.math.abs

/** House, techno, EDM: music built in phrases, where a drop swap is the most natural move there is. */
val TrackProfile.isElectronic: Boolean
    get() = Genres.ELECTRONIC in genres || Genres.EDM in genres || Genres.HOUSE in genres || Genres.TECHNO in genres

/**
 * How eager the DJ is. The budget exists so the night feels natural: most songs play whole, cuts are the exception.
 *
 * @param maxCutRatio at most this share of songs end early.
 * @param minCutConfidence a cut point below this confidence is ignored and the song plays whole.
 * @param dropEntryEvery at most one drop entry every this many transitions.
 * @param prefersEarlyCut leave at the first natural point instead of the last one.
 * @param dropSwapEvery at most one drop swap every this many transitions (half that between electronic tracks).
 */
enum class DjMode(
    val maxCutRatio: Double,
    val minCutConfidence: Double,
    val dropEntryEvery: Int,
    val prefersEarlyCut: Boolean,
    val dropSwapEvery: Int,
) {
    NATURAL(maxCutRatio = 0.25, minCutConfidence = 0.8, dropEntryEvery = 8, prefersEarlyCut = false, dropSwapEvery = 6),
    FIESTA(maxCutRatio = 0.5, minCutConfidence = 0.7, dropEntryEvery = 4, prefersEarlyCut = false, dropSwapEvery = 4),
    HORA_LOCA(maxCutRatio = 0.6, minCutConfidence = 0.6, dropEntryEvery = 2, prefersEarlyCut = true, dropSwapEvery = 2),
}

sealed interface Exit {
    /** Let Spotify finish the song; its own crossfade does the transition. */
    data object Full : Exit

    /**
     * Leave the song at [positionMs]. With [swap] it is a drop swap: the song builds up to its drop and, right where
     * that drop would hit, the next song's drop hits instead. A dry cut, because the hit is the whole point.
     */
    data class CutAt(val positionMs: Long, val swap: Boolean = false) : Exit
}

sealed interface Entry {
    data object FromStart : Entry

    /** Skip straight into the next song's drop: the move that makes it sound like someone is mixing. */
    data class AtDrop(val positionMs: Long) : Entry
}

data class Transition(val exit: Exit, val entry: Entry)

object TransitionPlanner {
    /**
     * Shorter songs are already radio edits; cutting them feels like a mistake. Most reggaeton and pop runs
     * 2:30 to 3:00, so the line sits below that: at 3:00 the phone test had almost every song playing whole.
     */
    const val SHORT_SONG_MS = 140_000L

    /** A drop swap between tempos further apart than this sounds like a mistake, not a trick. */
    const val MAX_SWAP_TEMPO_GAP = 0.15

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
        // The last song of the night always plays to its end: cutting it would only cut the party short.
        if (next == null) return Transition(Exit.Full, Entry.FromStart)
        chooseSwap(current, next, past, mode, keepFull)?.let { return it }
        val exit = chooseExit(current, past, mode, keepFull)
        // A drop entry only makes sense after a cut: after a natural ending Spotify is already fading into the start.
        val entry = if (next != null && exit is Exit.CutAt) chooseEntry(current, next, past, mode) else Entry.FromStart
        return Transition(exit, entry)
    }

    /** What any early exit needs: not "Completa", not a radio edit, not right after a cut, and budget left. */
    private fun mayCut(current: TrackProfile, past: List<Transition>, mode: DjMode, keepFull: Boolean): Boolean {
        if (keepFull || current.durationMs < SHORT_SONG_MS) return false
        if (past.lastOrNull()?.exit is Exit.CutAt) return false
        // Counting this song in the ratio means the first songs of the night always play whole: a warm-up.
        val cutsIfWeCut = past.count { it.exit is Exit.CutAt } + 1
        return cutsIfWeCut.toDouble() / (past.size + 1) <= mode.maxCutRatio
    }

    /**
     * The drop swap: the current song plays through its first drop, builds up to the second and, where it would
     * hit, the next song's drop hits instead. Needs trusted points on both sides, close tempos and no fall in energy.
     *
     * Between two electronic tracks it is the natural move: they are built in eight-bar phrases, so a drop guessed
     * from the tempo lands where the music turns, and the swap is allowed twice as often.
     */
    private fun chooseSwap(current: TrackProfile, next: TrackProfile, past: List<Transition>, mode: DjMode, keepFull: Boolean): Transition? {
        if (!mayCut(current, past, mode, keepFull)) return null
        val electronic = current.isElectronic && next.isElectronic
        val every = if (electronic) (mode.dropSwapEvery + 1) / 2 else mode.dropSwapEvery
        val lastSwap = past.indexOfLast { (it.exit as? Exit.CutAt)?.swap == true }
        if (lastSwap >= 0 && past.size - lastSwap < every) return null

        val minDrop = if (electronic) CueEstimator.GRID_DROP_CONFIDENCE else MIN_DROP_CONFIDENCE
        val minSwap = if (electronic) CueEstimator.GRID_DROP_CONFIDENCE else mode.minCutConfidence
        val swapAt = CueEstimator.estimate(current).swapPoint?.takeIf { it.confidence >= minSwap } ?: return null
        val drop = CueEstimator.estimate(next).drop?.takeIf { it.confidence >= minDrop } ?: return null
        if (current.energy != null && next.energy != null && next.energy < current.energy - ENERGY_DROP_TOLERANCE) return null
        if (current.bpm != null && next.bpm != null) {
            val a = Tempo.fold(current.bpm)
            val b = Tempo.fold(next.bpm)
            if (abs(a - b) / a > MAX_SWAP_TEMPO_GAP) return null
        }
        return Transition(Exit.CutAt(swapAt.positionMs, swap = true), Entry.AtDrop(drop.positionMs))
    }

    private fun chooseExit(current: TrackProfile, past: List<Transition>, mode: DjMode, keepFull: Boolean): Exit {
        if (!mayCut(current, past, mode, keepFull)) return Exit.Full

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
