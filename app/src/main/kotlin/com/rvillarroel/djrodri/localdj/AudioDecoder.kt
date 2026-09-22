package com.rvillarroel.djrodri.localdj

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** A whole song in memory, stereo, interleaved L R L R, -1..1, at its own sample rate. */
class DecodedAudio(val samples: FloatArray, val sampleRate: Int) {
    val frames: Int get() = samples.size / 2
    val durationMs: Long get() = frames * 1000L / sampleRate

    /** Left and right folded to one channel, for analysis. */
    fun mono(): FloatArray = FloatArray(frames) { (samples[it * 2] + samples[it * 2 + 1]) * 0.5f }
}

/**
 * Turns an MP3, AAC, FLAC, OGG or Opus file into samples with Android's own decoders (MediaCodec): no library.
 * The whole song is decoded up front, so loops, cues and scratching-back are instant jumps in memory.
 */
object AudioDecoder {
    suspend fun decode(context: Context, uri: Uri): DecodedAudio = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("El archivo no tiene audio")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        // Float output when the decoder offers it: no precision lost before the EQ.
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        codec.configure(format, null, null, 0)
        codec.start()

        var out = FloatArray(((durationUs / 1_000_000.0 + 1) * sampleRate * 2).toInt().coerceAtLeast(sampleRate * 2))
        var written = 0
        // Without a PCM encoding key the codec gives 16-bit samples, whatever was asked for.
        var floatOutput = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatOutput = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val count = if (floatOutput) info.size / 4 else info.size / 2
                        val frames = count / channels
                        if (written + frames * 2 > out.size) out = out.copyOf((out.size * 1.5).toInt() + frames * 2)
                        if (floatOutput) {
                            val fb = buffer.asFloatBuffer()
                            for (i in 0 until frames) {
                                val l = fb.get(i * channels).finiteOrZero()
                                val r = if (channels > 1) fb.get(i * channels + 1).finiteOrZero() else l
                                out[written++] = l
                                out[written++] = r
                            }
                        } else {
                            val sb = buffer.asShortBuffer()
                            for (i in 0 until frames) {
                                val l = sb.get(i * channels) / 32_768f
                                val r = if (channels > 1) sb.get(i * channels + 1) / 32_768f else l
                                out[written++] = l
                                out[written++] = r
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        DecodedAudio(out.copyOf(written), sampleRate)
    }

    private const val TIMEOUT_US = 10_000L
}

private fun Float.finiteOrZero(): Float = if (isFinite()) coerceIn(-1f, 1f) else 0f
