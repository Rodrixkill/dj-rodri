package com.rvillarroel.djrodri.localdj

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.rvillarroel.djrodri.domain.mix.Pad
import com.rvillarroel.djrodri.domain.mix.PadSounds

/**
 * Plays the sound pads on top of the decks: its own output, several sounds at once, each one from the start when
 * tapped again. Sounds are synthesized once, on first use.
 */
class Sampler {
    private val sounds = HashMap<Pad, FloatArray>()
    private class Voice(val sound: FloatArray, var index: Int = 0)
    private val voices = mutableListOf<Voice>()
    private val lock = Any()
    @Volatile var volume = 0.8
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun trigger(pad: Pad) {
        val sound = synchronized(sounds) { sounds.getOrPut(pad) { PadSounds.render(pad) } }
        synchronized(lock) { voices += Voice(sound) }
        ensureRunning()
    }

    /** Builds every sound ahead of time, so the first tap is instant. */
    fun prepare() = Thread { Pad.entries.forEach { pad -> synchronized(sounds) { sounds.getOrPut(pad) { PadSounds.render(pad) } } } }.start()

    private fun ensureRunning() {
        if (thread != null) return
        val rate = PadSounds.SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, CHUNK * 8 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        t.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = FloatArray(CHUNK * 2)
            while (!Thread.currentThread().isInterrupted) {
                val any = synchronized(lock) {
                    buffer.fill(0f)
                    val g = volume.toFloat()
                    val done = mutableListOf<Voice>()
                    for (v in voices) {
                        val n = minOf(buffer.size, v.sound.size - v.index)
                        for (i in 0 until n) buffer[i] = (buffer[i] + v.sound[v.index + i] * g).coerceIn(-1f, 1f)
                        v.index += n
                        if (v.index >= v.sound.size) done += v
                    }
                    voices.removeAll(done)
                    voices.isNotEmpty() || done.isNotEmpty()
                }
                if (any) t.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING) else Thread.sleep(10)
            }
        }, "sampler").apply { start() }
    }

    fun release() {
        thread?.interrupt()
        thread = null
        track?.release()
        track = null
    }

    private companion object {
        const val CHUNK = 512
    }
}
