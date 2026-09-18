package com.rvillarroel.djrodri.domain.mix

import com.rvillarroel.djrodri.domain.music.Tempo
import kotlin.math.abs
import kotlin.math.roundToLong

/** A local audio file, analyzed: this is the only music DJ Rodri is allowed to actually mix. */
data class LocalTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val grid: BeatGrid?,
    /** Where the track really gets going, for bringing it in. */
    val mixInMs: Long? = null,
    /** Where it starts winding down, for taking it out. */
    val mixOutMs: Long? = null,
)

sealed interface MixPlan {
    /** No usable pulse on either side: play one after the other, like the Spotify DJ does. */
    data class Cut(val startBMs: Long) : MixPlan

    /**
     * A real mix: [fadeStartAMs] is where A starts fading while B comes in at [startBMs], both on the beat,
     * with B nudged by [speedRatioB] so the two tempos match.
     */
    data class Blend(
        val fadeStartAMs: Long,
        val startBMs: Long,
        val fadeMs: Long,
        val speedRatioB: Double,
        val beatsAligned: Boolean,
    ) : MixPlan
}

/**
 * Plans the transition between two of your own files: where A leaves, where B comes in, how long they overlap and
 * how much B has to be nudged to run at A's tempo.
 *
 * Nothing here touches Spotify. It exists only for the local DJ module, where mixing audio is allowed.
 */
object MixPlanner {
    /** Beyond this the nudge is audible, so the DJ cuts instead of blending. */
    const val MAX_TEMPO_STRETCH = 0.06

    fun plan(a: LocalTrack, b: LocalTrack, fadeBars: Int = 4): MixPlan {
        val gridA = a.grid?.takeIf { it.confidence >= BeatGrid.MIN_MIXABLE_CONFIDENCE }
        val gridB = b.grid?.takeIf { it.confidence >= BeatGrid.MIN_MIXABLE_CONFIDENCE }
        val startB = b.mixInMs ?: 0
        if (gridA == null || gridB == null) return MixPlan.Cut(gridB?.nearestBeat(startB) ?: startB)

        val ratio = tempoRatio(gridA.bpm, gridB.bpm)
        if (ratio == null) return MixPlan.Cut(gridB.phraseStartAfter(startB))

        val fadeMs = (gridA.barMs * fadeBars).roundToLong()
        // A leaves from a phrase boundary, so the two tracks stay in step bar by bar.
        val exit = a.mixOutMs ?: (a.durationMs - fadeMs - TAIL_GUARD_MS)
        val fadeStart = gridA.phraseStartBefore(exit.coerceIn(0, a.durationMs - fadeMs))
        return MixPlan.Blend(
            fadeStartAMs = fadeStart,
            startBMs = gridB.phraseStartAfter(startB),
            fadeMs = fadeMs,
            speedRatioB = ratio,
            beatsAligned = true,
        )
    }

    /** How much B must speed up or slow down to sit on A's tempo, or null when that would be heard. */
    internal fun tempoRatio(bpmA: Double, bpmB: Double): Double? {
        val a = Tempo.fold(bpmA)
        val b = Tempo.fold(bpmB)
        val ratio = a / b
        return ratio.takeIf { abs(it - 1.0) <= MAX_TEMPO_STRETCH }
    }

    /** The volume of each track during the fade: equal power, so the middle does not sound quiet. */
    fun gains(progress: Double): Pair<Double, Double> {
        val p = progress.coerceIn(0.0, 1.0)
        val angle = p * Math.PI / 2
        return Math.cos(angle) to Math.sin(angle)
    }

    private const val TAIL_GUARD_MS = 2_000L
}
