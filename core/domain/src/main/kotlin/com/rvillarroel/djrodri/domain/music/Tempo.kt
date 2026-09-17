package com.rvillarroel.djrodri.domain.music

import kotlin.math.abs

/**
 * Sources disagree by an octave all the time (ReccoBeats reports Despacito at 178, it feels like 89),
 * so tempos are folded into one range before comparing.
 */
object Tempo {
    fun fold(bpm: Double, low: Double = 70.0, high: Double = 140.0): Double {
        require(bpm > 0) { "bpm must be positive" }
        var folded = bpm
        while (folded < low) folded *= 2
        while (folded >= high) folded /= 2
        return folded
    }

    fun areCompatible(a: Double, b: Double, tolerance: Double = 0.06): Boolean {
        val fa = fold(a)
        val fb = fold(b)
        return abs(fa - fb) / minOf(fa, fb) <= tolerance
    }
}
