package com.rvillarroel.djrodri.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rvillarroel.djrodri.domain.mix.BeatGrid
import com.rvillarroel.djrodri.domain.mix.Waveform
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.Motion
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space
import kotlin.math.floor

/**
 * The deck's main waveform: [windowMs] of music around the playhead, scrolling as it plays, from the real audio.
 * Bass is the solid body, mids and highs sit on top, lighter; what has played is in the deck's colour, what is
 * coming is grey. Beat ticks come from the beat grid, bars are taller, the loop is shaded and the cue is marked.
 */
@Composable
fun ZoomWaveform(
    waveform: Waveform?,
    durationMs: Long,
    positionMs: Long,
    grid: BeatGrid?,
    played: Color,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
    windowMs: Long = 10_000,
    cueMs: Long? = null,
    loop: Pair<Long, Long>? = null,
) {
    val upcoming = Dj.colors.textTertiary
    val playhead = Dj.colors.textPrimary
    val loopShade = Dj.colors.accent.copy(alpha = 0.18f)
    val beatTick = Dj.colors.textSecondary
    val bg = Dj.colors.surfaceSecondary
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Radius.sm))) {
        drawRect(bg)
        val w = size.width
        val h = size.height
        val mid = h / 2
        val start = positionMs - windowMs / 2
        fun xOf(ms: Long) = ((ms - start).toFloat() / windowMs) * w

        loop?.let { (from, to) -> drawRect(loopShade, Offset(xOf(from), 0f), Size(xOf(to) - xOf(from), h)) }

        if (waveform != null && durationMs > 0) {
            val step = 3f
            var x = 0f
            while (x < w) {
                val ms = start + (x / w * windowMs).toLong()
                if (ms in 0 until durationMs) {
                    val i = (ms * waveform.size / durationMs).toInt().coerceIn(0, waveform.size - 1)
                    val color = if (ms <= positionMs) played else upcoming
                    val low = waveform.low[i] * mid * 0.95f
                    val midBand = waveform.mid[i] * mid * 0.7f
                    val high = waveform.high[i] * mid * 0.45f
                    drawLine(color, Offset(x, mid - low), Offset(x, mid + low), strokeWidth = step - 0.8f)
                    drawLine(color.copy(alpha = 0.55f), Offset(x, mid - midBand - low * 0.15f), Offset(x, mid + midBand + low * 0.15f), strokeWidth = step - 1.4f)
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(x, mid - high), Offset(x, mid + high), strokeWidth = 1f)
                }
                x += step
            }
        }

        // The beat grid: a tick per beat, a longer one per bar, so phrases can be read at a glance.
        grid?.takeIf { it.confidence >= BeatGrid.MIN_MIXABLE_CONFIDENCE }?.let { g ->
            var beat = floor((start - g.firstBeatMs) / g.beatMs).toInt()
            while (true) {
                val ms = g.beatAt(beat)
                if (ms > start + windowMs) break
                if (ms >= 0) {
                    val bar = Math.floorMod(beat, BeatGrid.BEATS_PER_BAR) == 0
                    val tick = if (bar) 12.dp.toPx() else 5.dp.toPx()
                    val x = xOf(ms)
                    drawLine(beatTick.copy(alpha = if (bar) 0.9f else 0.5f), Offset(x, 0f), Offset(x, tick), strokeWidth = if (bar) 2f else 1f)
                    drawLine(beatTick.copy(alpha = if (bar) 0.9f else 0.5f), Offset(x, h - tick), Offset(x, h), strokeWidth = if (bar) 2f else 1f)
                }
                beat++
            }
        }

        cueMs?.let {
            val x = xOf(it)
            if (x in 0f..w) {
                val path = Path().apply { moveTo(x - 6.dp.toPx(), 0f); lineTo(x + 6.dp.toPx(), 0f); lineTo(x, 8.dp.toPx()); close() }
                drawPath(path, playhead)
            }
        }

        drawLine(playhead, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 2.dp.toPx())
    }
}

/** The whole song in one strip: where you are, what has played. Tap to jump anywhere. */
@Composable
fun OverviewWaveform(waveform: Waveform?, durationMs: Long, positionMs: Long, played: Color, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val upcoming = Dj.colors.progressInactive
    val playhead = Dj.colors.textPrimary
    var width by remember { mutableStateOf(1f) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset -> if (durationMs > 0) onSeek((offset.x / size.width * durationMs).toLong()) }
            },
    ) {
        width = size.width
        val w = size.width
        val h = size.height
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        if (waveform != null) {
            val step = 2f
            var x = 0f
            while (x < w) {
                val i = (x / w * waveform.size).toInt().coerceIn(0, waveform.size - 1)
                val amp = (waveform.low[i] * 0.7f + waveform.mid[i] * 0.3f) * h / 2
                drawLine(if (x / w <= progress) played else upcoming, Offset(x, h / 2 - amp), Offset(x, h / 2 + amp), strokeWidth = step - 0.5f)
                x += step
            }
        } else {
            drawRoundRect(upcoming, cornerRadius = CornerRadius(4f))
        }
        drawLine(playhead, Offset(w * progress, 0f), Offset(w * progress, h), strokeWidth = 2.dp.toPx())
    }
}

/**
 * A mixer fader, vertical: drag to set, double tap to return to the middle (EQ) or the top (volume). The part
 * above neutral is red, so a boost or a cut is seen at a glance.
 */
@Composable
fun VerticalFader(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    neutral: Double = 0.0,
    height: Dp = 132.dp,
    display: (Double) -> String = { "%+.0f".format(it) },
) {
    val haptics = LocalHapticFeedback.current
    val track = Dj.colors.progressInactive
    val active = Dj.colors.accent
    val knob = Dj.colors.textPrimary
    val span = range.endInclusive - range.start
    fun fraction(v: Double) = ((v - range.start) / span).toFloat().coerceIn(0f, 1f)
    Column(modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(display(value), style = DjType.technicalSmall, color = if (value != neutral) Dj.colors.accentBright else Dj.colors.textTertiary)
        Canvas(
            Modifier
                .padding(vertical = Space.xxs)
                .width(44.dp)
                .height(height)
                .pointerInput(range) {
                    detectTapGestures(onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onChange(neutral)
                    })
                }
                .pointerInput(range) {
                    detectDragGestures { change, _ ->
                        val f = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        onChange(range.start + span * f)
                    }
                },
        ) {
            val x = size.width / 2
            val top = 6.dp.toPx()
            val bottom = size.height - 6.dp.toPx()
            fun yOf(f: Float) = bottom - (bottom - top) * f
            drawLine(track, Offset(x, top), Offset(x, bottom), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            val y = yOf(fraction(value))
            val yNeutral = yOf(fraction(neutral))
            drawLine(active, Offset(x, yNeutral), Offset(x, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(track, Offset(x - 8.dp.toPx(), yNeutral), Offset(x + 8.dp.toPx(), yNeutral), strokeWidth = 1.dp.toPx())
            drawRoundRect(knob, Offset(x - 14.dp.toPx(), y - 5.dp.toPx()), Size(28.dp.toPx(), 10.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
        Text(label, style = DjType.technicalSmall, color = Dj.colors.textSecondary)
    }
}

/**
 * A ──●── B: the crossfader. Drag the red handle; the side that dominates is labelled brighter. Double tap to
 * centre it.
 */
@Composable
fun Crossfader(position: Double, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val track = Dj.colors.progressInactive
    val handle = Dj.colors.accent
    val aColor by animateColorAsState(if (position < 0.5) Dj.colors.textPrimary else Dj.colors.textTertiary, tween(Motion.FAST), label = "a")
    val bColor by animateColorAsState(if (position > 0.5) Dj.colors.textPrimary else Dj.colors.textTertiary, tween(Motion.FAST), label = "b")
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("A", style = DjType.section, color = aColor, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        Canvas(
            Modifier
                .weight(1f)
                .height(44.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onChange(0.5)
                    }, onTap = { offset -> onChange((offset.x / size.width).toDouble().coerceIn(0.0, 1.0)) })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> onChange((change.position.x / size.width).toDouble().coerceIn(0.0, 1.0)) }
                },
        ) {
            val y = size.height / 2
            val pad = 14.dp.toPx()
            drawLine(track, Offset(pad, y), Offset(size.width - pad, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            val cx = size.width / 2
            drawLine(track, Offset(cx, y - 8.dp.toPx()), Offset(cx, y + 8.dp.toPx()), strokeWidth = 1.dp.toPx())
            val x = pad + (size.width - 2 * pad) * position.toFloat()
            drawRoundRect(handle, Offset(x - 12.dp.toPx(), y - 16.dp.toPx()), Size(24.dp.toPx(), 32.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawLine(Color.White, Offset(x, y - 10.dp.toPx()), Offset(x, y + 10.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
        Text("B", style = DjType.section, color = bColor, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
    }
}

/** ‹ 4 BEAT ›: choose the loop length; tapping the number turns the loop on and off. Red only when looping. */
@Composable
fun LoopSelector(beats: Int, active: Boolean, onBeats: (Int) -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val choices = com.rvillarroel.djrodri.domain.mix.LoopRegion.BEAT_CHOICES
    val index = choices.indexOf(beats).coerceAtLeast(0)
    val bg by animateColorAsState(if (active) Dj.colors.accent else Dj.colors.surfaceSecondary, tween(Motion.FAST), label = "loop")
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(Radius.md)).background(bg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val fg = if (active) Dj.colors.onAccent else Dj.colors.textPrimary
        Text("‹", style = DjType.section, color = fg, modifier = Modifier.clickable { onBeats(choices[(index - 1).coerceAtLeast(0)]) }.padding(horizontal = Space.sm))
        Column(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$beats", style = DjType.technical, color = fg)
            Text(if (beats == 1) "BEAT" else "BEATS", style = DjType.technicalSmall, color = fg.copy(alpha = 0.8f))
        }
        Text("›", style = DjType.section, color = fg, modifier = Modifier.clickable { onBeats(choices[(index + 1).coerceAtMost(choices.lastIndex)]) }.padding(horizontal = Space.sm))
    }
}

/** A sound pad: flashes red for a moment when struck. */
@Composable
fun PadButton(label: String, onHit: () -> Unit, modifier: Modifier = Modifier) {
    var lit by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (lit) Dj.colors.accent else Dj.colors.surfaceSecondary, tween(if (lit) 30 else Motion.SLOW), label = "pad")
    androidx.compose.runtime.LaunchedEffect(lit) { if (lit) { kotlinx.coroutines.delay(120); lit = false } }
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    lit = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHit()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DjType.label, color = if (lit) Dj.colors.onAccent else Dj.colors.textPrimary)
    }
}
