package com.rvillarroel.djrodri.domain.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound-shaping of a DJ deck, on plain float samples (stereo, interleaved L R L R, -1..1). No Android here:
 * every filter is tested on the JVM, and the phone only feeds it buffers.
 */

/**
 * A second-order filter (RBJ Audio EQ Cookbook). Keeps its own state per channel, so it runs across buffers
 * without clicks.
 */
class Biquad private constructor(
    private var b0: Double,
    private var b1: Double,
    private var b2: Double,
    private var a1: Double,
    private var a2: Double,
) {
    // Two channels, two samples of memory each.
    private val x1 = DoubleArray(2)
    private val x2 = DoubleArray(2)
    private val y1 = DoubleArray(2)
    private val y2 = DoubleArray(2)

    fun process(samples: FloatArray, frames: Int = samples.size / 2) {
        for (i in 0 until frames) {
            for (ch in 0..1) {
                val index = i * 2 + ch
                val x = samples[index].toDouble()
                val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
                x2[ch] = x1[ch]; x1[ch] = x
                y2[ch] = y1[ch]; y1[ch] = y
                samples[index] = y.toFloat()
            }
        }
    }

    /** Swap in new coefficients but keep the memory: turning a knob must not click. */
    fun retune(other: Biquad) {
        b0 = other.b0; b1 = other.b1; b2 = other.b2; a1 = other.a1; a2 = other.a2
    }

    /** Gain of the filter at [frequency], for tests and for drawing curves. */
    fun magnitudeAt(frequency: Double, sampleRate: Int): Double {
        val w = 2 * PI * frequency / sampleRate
        val (cr, ci) = cos(w) to -sin(w)
        val (c2r, c2i) = cos(2 * w) to -sin(2 * w)
        val nr = b0 + b1 * cr + b2 * c2r
        val ni = b1 * ci + b2 * c2i
        val dr = 1 + a1 * cr + a2 * c2r
        val di = a1 * ci + a2 * c2i
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun lowShelf(sampleRate: Int, frequency: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * frequency / sampleRate
            val alpha = sin(w) / 2 * sqrt(2.0)
            val c = cos(w)
            val s = 2 * sqrt(a) * alpha
            return normalized(
                a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
                (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s,
            )
        }

        fun highShelf(sampleRate: Int, frequency: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * frequency / sampleRate
            val alpha = sin(w) / 2 * sqrt(2.0)
            val c = cos(w)
            val s = 2 * sqrt(a) * alpha
            return normalized(
                a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s,
            )
        }

        fun peaking(sampleRate: Int, frequency: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * frequency / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalized(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun lowPass(sampleRate: Int, frequency: Double, q: Double = 0.707): Biquad {
            val w = 2 * PI * frequency / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalized((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(sampleRate: Int, frequency: Double, q: Double = 0.707): Biquad {
            val w = 2 * PI * frequency / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
    }
}

/**
 * The three knobs of a mixer channel: LOW, MID, HIGH, from -26 dB (a kill: the band is gone) to +6 dB.
 * Bands split where DJ mixers split them: bass under 250 Hz, highs over 4 kHz.
 */
class ThreeBandEq(private val sampleRate: Int) {
    var lowDb = 0.0
        private set
    var midDb = 0.0
        private set
    var highDb = 0.0
        private set

    private val low = Biquad.lowShelf(sampleRate, LOW_HZ, 0.0)
    private val mid = Biquad.peaking(sampleRate, MID_HZ, MID_Q, 0.0)
    private val high = Biquad.highShelf(sampleRate, HIGH_HZ, 0.0)

    fun set(lowDb: Double, midDb: Double, highDb: Double) {
        this.lowDb = lowDb.coerceIn(KILL_DB, MAX_DB)
        this.midDb = midDb.coerceIn(KILL_DB, MAX_DB)
        this.highDb = highDb.coerceIn(KILL_DB, MAX_DB)
        low.retune(Biquad.lowShelf(sampleRate, LOW_HZ, this.lowDb))
        mid.retune(Biquad.peaking(sampleRate, MID_HZ, MID_Q, this.midDb))
        high.retune(Biquad.highShelf(sampleRate, HIGH_HZ, this.highDb))
    }

    val flat: Boolean get() = lowDb == 0.0 && midDb == 0.0 && highDb == 0.0

    fun process(samples: FloatArray, frames: Int) {
        if (flat) return
        low.process(samples, frames)
        mid.process(samples, frames)
        high.process(samples, frames)
    }

    companion object {
        const val LOW_HZ = 250.0
        const val MID_HZ = 1_000.0
        const val HIGH_HZ = 4_000.0
        const val MID_Q = 0.7
        const val KILL_DB = -26.0
        const val MAX_DB = 6.0
    }
}

/**
 * The one-knob filter every DJ mixer has: centre is off, left closes a low-pass (muffled, the bass stays), right
 * opens a high-pass (thin, the bass goes). Sweeps are exponential, the way the ear hears frequency.
 */
class FilterFx(private val sampleRate: Int) {
    /** -1..1; 0 is bypass. */
    var amount = 0.0
        private set
    private var filter: Biquad? = null

    fun set(amount: Double) {
        this.amount = amount.coerceIn(-1.0, 1.0)
        val a = this.amount
        val next = when {
            kotlin.math.abs(a) < DEAD_ZONE -> null
            a < 0 -> Biquad.lowPass(sampleRate, sweep(OPEN_HZ, CLOSED_LOW_HZ, -a), RESONANCE)
            else -> Biquad.highPass(sampleRate, sweep(CLOSED_HIGH_START_HZ, CLOSED_HIGH_HZ, a), RESONANCE)
        }
        val current = filter
        filter = when {
            next == null -> null
            current == null -> next
            else -> current.also { it.retune(next) }
        }
    }

    fun process(samples: FloatArray, frames: Int) {
        filter?.process(samples, frames)
    }

    /** Where the cutoff is, for the knob's label. Null when off. */
    val cutoffHz: Double?
        get() = when {
            kotlin.math.abs(amount) < DEAD_ZONE -> null
            amount < 0 -> sweep(OPEN_HZ, CLOSED_LOW_HZ, -amount)
            else -> sweep(CLOSED_HIGH_START_HZ, CLOSED_HIGH_HZ, amount)
        }

    private fun sweep(from: Double, to: Double, t: Double) = exp(ln(from) + (ln(to) - ln(from)) * t)

    private companion object {
        const val DEAD_ZONE = 0.02
        const val OPEN_HZ = 18_000.0
        const val CLOSED_LOW_HZ = 180.0
        const val CLOSED_HIGH_START_HZ = 30.0
        const val CLOSED_HIGH_HZ = 5_000.0
        const val RESONANCE = 1.1
    }
}

/**
 * Echo in time with the music: a delay of a fraction of a beat, fed back so it repeats and fades. With [tail] on,
 * the input can stop (an "echo out") and the repeats keep ringing.
 */
class EchoFx(private val sampleRate: Int, maxSeconds: Double = 2.0) {
    private val buffer = FloatArray((sampleRate * maxSeconds).toInt() * 2)
    private var writeIndex = 0
    var delayFrames = sampleRate / 2
        private set
    var feedback = 0.45
    /** 0 dry .. 1 only echoes. */
    var mix = 0.0
        private set

    fun set(mix: Double, delayMs: Double) {
        this.mix = mix.coerceIn(0.0, 1.0)
        delayFrames = (delayMs / 1000 * sampleRate).toInt().coerceIn(1, buffer.size / 2 - 1)
    }

    val active: Boolean get() = mix > 0.0

    fun process(samples: FloatArray, frames: Int) {
        if (!active) return
        val size = buffer.size
        for (i in 0 until frames) {
            for (ch in 0..1) {
                val index = i * 2 + ch
                val readIndex = ((writeIndex - delayFrames * 2 + ch) % size + size) % size
                val delayed = buffer[readIndex]
                val dry = samples[index]
                buffer[(writeIndex + ch) % size] = dry + delayed * feedback.toFloat()
                samples[index] = (dry * (1 - mix * 0.5) + delayed * mix).toFloat()
            }
            writeIndex = (writeIndex + 2) % size
        }
    }

    companion object {
        /** Musical delay: [beats] of a beat at [bpm] (3/4 of a beat is the classic dub echo). */
        fun delayMs(bpm: Double, beats: Double = 0.75) = 60_000.0 / bpm * beats
    }
}

/** A loop of whole beats, in frames of the track: where the playhead jumps back and how long it lasts. */
data class LoopRegion(val startFrame: Long, val lengthFrames: Long) {
    val endFrame: Long get() = startFrame + lengthFrames

    /** The frame to play after [frame]: inside the loop it wraps, before it nothing changes. */
    fun wrap(frame: Long): Long = if (frame >= endFrame) startFrame + (frame - startFrame) % lengthFrames else frame

    companion object {
        val BEAT_CHOICES = listOf(1, 2, 4, 8, 16)

        /** A loop of [beats] starting on the beat nearest [positionMs], so it sounds like the music, not a glitch. */
        fun onBeat(grid: BeatGrid?, positionMs: Long, beats: Int, bpmFallback: Double, sampleRate: Int): LoopRegion {
            val beatMs = grid?.beatMs ?: (60_000.0 / bpmFallback)
            val start = grid?.nearestBeat(positionMs)?.coerceAtLeast(0) ?: positionMs
            return LoopRegion(start * sampleRate / 1000, (beatMs * beats * sampleRate / 1000).toLong().coerceAtLeast(1))
        }
    }
}

/**
 * What a track looks like, for drawing: [points] columns, each with the level of its bass, mids and highs (0..1).
 * Built once from the decoded audio; the deck shows it in red where it has played and grey where it has not.
 */
class Waveform(val low: FloatArray, val mid: FloatArray, val high: FloatArray) {
    val size: Int get() = low.size

    /**
     * Where the music really ends, 0..1 of the file: many files carry seconds of silence after the last note, and a
     * mix planned against the file's end would play that silence.
     */
    fun lastSound(threshold: Float = SILENCE): Float {
        for (i in size - 1 downTo 0) if (maxOf(low[i], mid[i], high[i]) > threshold) return (i + 1).toFloat() / size
        return 1f
    }

    companion object {
        const val SILENCE = 0.03f

        /** From mono samples: one-pole splits into bands, the peak of each band per column, normalized. */
        fun of(mono: FloatArray, sampleRate: Int, points: Int = 1_200): Waveform {
            val low = FloatArray(points)
            val mid = FloatArray(points)
            val high = FloatArray(points)
            if (mono.isEmpty()) return Waveform(low, mid, high)
            val lowCoef = onePole(LOW_SPLIT_HZ, sampleRate)
            val highCoef = onePole(HIGH_SPLIT_HZ, sampleRate)
            var lp = 0f
            var lp2 = 0f
            val perPoint = (mono.size.toDouble() / points).coerceAtLeast(1.0)
            for (i in mono.indices) {
                val x = mono[i]
                lp += lowCoef * (x - lp)
                lp2 += highCoef * (x - lp2)
                val bass = kotlin.math.abs(lp)
                val treble = kotlin.math.abs(x - lp2)
                val middle = kotlin.math.abs(lp2 - lp)
                val column = (i / perPoint).toInt().coerceAtMost(points - 1)
                if (bass > low[column]) low[column] = bass
                if (middle > mid[column]) mid[column] = middle
                if (treble > high[column]) high[column] = treble
            }
            normalize(low); normalize(mid); normalize(high)
            return Waveform(low, mid, high)
        }

        private fun onePole(cutoff: Double, sampleRate: Int) = (1 - exp(-2 * PI * cutoff / sampleRate)).toFloat()

        private fun normalize(values: FloatArray) {
            val max = values.maxOrNull()?.takeIf { it > 0f } ?: return
            for (i in values.indices) values[i] = values[i] / max
        }

        private const val LOW_SPLIT_HZ = 200.0
        private const val HIGH_SPLIT_HZ = 3_000.0
    }
}

/**
 * The onset envelope [BeatTracker] reads: how much the sound rises every [hopMs]. Energy per hop, log-compressed
 * so quiet passages still count, from the bass and low-mids where kick drums live.
 */
object OnsetEnvelope {
    fun of(mono: FloatArray, sampleRate: Int, hopMs: Int = 10): FloatArray {
        val hop = (sampleRate * hopMs / 1000).coerceAtLeast(1)
        val coef = (1 - exp(-2 * PI * KICK_HZ / sampleRate)).toFloat()
        var lp = 0f
        val frames = mono.size / hop
        val envelope = FloatArray(frames)
        for (f in 0 until frames) {
            var energy = 0.0
            for (i in f * hop until (f + 1) * hop) {
                lp += coef * (mono[i] - lp)
                energy += (lp * lp).toDouble()
            }
            envelope[f] = ln(1 + 100 * energy / hop).toFloat()
        }
        return envelope
    }

    private const val KICK_HZ = 150.0
}
