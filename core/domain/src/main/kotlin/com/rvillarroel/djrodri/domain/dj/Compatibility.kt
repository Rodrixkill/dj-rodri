package com.rvillarroel.djrodri.domain.dj

import com.rvillarroel.djrodri.domain.music.Tempo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Not a quality ranking: how much the change asks of the DJ. A challenging change can be the best moment of the
 * night; it just needs a clean cut on a phrase instead of a blend.
 */
enum class Difficulty(val label: String) {
    SMOOTH("Suave"),
    MODERATE("Moderada"),
    CREATIVE("Creativa"),
    CHALLENGING("Difícil"),
}

/**
 * How [score] (0..1) was reached, factor by factor, and the reasons in words. Technical fit (tempo, key) and musical
 * fit (style, energy, mood) are both in it, because a change that syncs is not necessarily a change that sounds good.
 */
data class Match(
    val score: Double,
    val difficulty: Difficulty,
    val tempo: Double,
    val genre: Double,
    val key: Double,
    val energy: Double,
    val mood: Double,
    val reasons: List<String>,
) {
    /** One line for the party screen: "Moderada · +3 BPM, sube la energía". */
    fun explain(): String =
        if (reasons.isEmpty()) difficulty.label else difficulty.label + " · " + reasons.take(MAX_REASONS).joinToString(", ")

    private companion object {
        const val MAX_REASONS = 2
    }
}

/**
 * How well [b] follows [a]. BPM is never the only rule: tempo (half and double time count as the same pulse),
 * style, energy, key and mood all weigh in. Unknown data scores in the middle, so a song without analysis is
 * neither preferred nor excluded.
 */
object Compatibility {
    private const val TEMPO_WEIGHT = 0.25
    private const val GENRE_WEIGHT = 0.25
    private const val ENERGY_WEIGHT = 0.2
    private const val KEY_WEIGHT = 0.15
    private const val MOOD_WEIGHT = 0.15
    private const val UNKNOWN = 0.5
    private const val NOTICEABLE = 0.15
    private const val MOOD_SHIFT = 0.35

    fun score(a: TrackProfile, b: TrackProfile): Double = match(a, b).score

    fun match(a: TrackProfile, b: TrackProfile): Match {
        val reasons = mutableListOf<String>()
        val tempo = tempo(a, b, reasons)
        val genre = genre(a, b, reasons)
        val energy = energy(a, b, reasons)
        val key = key(a, b, reasons)
        val mood = mood(a, b, reasons)
        val score = TEMPO_WEIGHT * tempo + GENRE_WEIGHT * genre + ENERGY_WEIGHT * energy + KEY_WEIGHT * key + MOOD_WEIGHT * mood
        val difficulty = when {
            tempo >= 0.8 && genre >= 0.8 -> Difficulty.SMOOTH
            tempo >= 0.5 && genre >= 0.5 -> Difficulty.MODERATE
            tempo >= 0.5 || genre >= 0.5 -> Difficulty.CREATIVE
            else -> Difficulty.CHALLENGING
        }
        return Match(score, difficulty, tempo, genre, key, energy, mood, reasons)
    }

    private fun tempo(a: TrackProfile, b: TrackProfile, reasons: MutableList<String>): Double {
        if (a.bpm == null || b.bpm == null) return UNKNOWN
        // 90 and 180 are the same pulse felt differently, as reggaeton and drum & bass show.
        val fa = Tempo.fold(a.bpm)
        val fb = Tempo.fold(b.bpm)
        val gap = abs(fb - fa) / fa
        val delta = (fb - fa).roundToInt()
        return when {
            gap <= 0.02 -> 1.0.also { reasons += "mismo tempo" }
            gap <= 0.06 -> 0.8.also { reasons += "%+d BPM".format(delta) }
            gap <= 0.12 -> 0.5.also { reasons += "tempo algo distinto (%+d BPM)".format(delta) }
            else -> 0.15.also { reasons += "tempos muy distintos (${a.bpm.roundToInt()} → ${b.bpm.roundToInt()})" }
        }
    }

    private fun genre(a: TrackProfile, b: TrackProfile, reasons: MutableList<String>): Double {
        val fa = StyleFamily.of(a)
        val fb = StyleFamily.of(b)
        val affinity = StyleFamily.affinity(fa, fb)
        if (fa != fb && fa != StyleFamily.UNKNOWN && fb != StyleFamily.UNKNOWN) {
            reasons += when {
                affinity >= 0.7 -> "${fa.label} → ${fb.label}, estilos cercanos"
                affinity <= 0.3 -> "${fa.label} → ${fb.label}, cambio grande de estilo"
                else -> "${fa.label} → ${fb.label}"
            }
        }
        return affinity
    }

    private fun energy(a: TrackProfile, b: TrackProfile, reasons: MutableList<String>): Double {
        if (a.energy == null || b.energy == null) return UNKNOWN
        val delta = b.energy - a.energy
        if (delta >= NOTICEABLE) reasons += "sube la energía"
        if (delta <= -NOTICEABLE) reasons += "baja la energía"
        return 1.0 - abs(delta)
    }

    private fun key(a: TrackProfile, b: TrackProfile, reasons: MutableList<String>): Double {
        if (a.key == null || b.key == null) return UNKNOWN
        return if (a.key.isCompatibleWith(b.key)) 1.0.also { reasons += "tonos compatibles" } else 0.4
    }

    /** Valence stands for mood: a melancholic song follows another melancholic one even at a slightly other tempo. */
    private fun mood(a: TrackProfile, b: TrackProfile, reasons: MutableList<String>): Double {
        if (a.valence == null || b.valence == null) return UNKNOWN
        val delta = abs(b.valence - a.valence)
        if (delta >= MOOD_SHIFT) reasons += "cambia el ánimo (${moodOf(a.valence)} → ${moodOf(b.valence)})"
        return 1.0 - delta
    }

    private fun moodOf(valence: Double) = when {
        valence >= 0.6 -> "alegre"
        valence <= 0.35 -> "oscuro"
        else -> "neutro"
    }
}
