package com.rvillarroel.djrodri.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.IconSize
import com.rvillarroel.djrodri.ui.theme.Motion
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space

// ---- Artwork ---------------------------------------------------------------------------------------------------------

/**
 * An album cover exactly as Spotify serves it (never cropped: covers are square). Without one, a quiet neutral tile
 * with a note, so a missing cover never looks like an error.
 */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, radius: Dp = Radius.md, description: String? = null) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.aspectRatio(1f).clip(shape).background(Dj.colors.surfaceSecondary), contentAlignment = Alignment.Center) {
        if (url == null) {
            Icon(DjIcons.Music, null, tint = Dj.colors.textTertiary, modifier = Modifier.fillMaxSize(0.36f))
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(Motion.BASE).build(),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A playlist without its own picture shows its first four covers. */
@Composable
fun Mosaic(urls: List<String>, modifier: Modifier = Modifier, radius: Dp = Radius.md) {
    if (urls.size < 4) return Artwork(urls.firstOrNull(), modifier, radius)
    Column(modifier.aspectRatio(1f).clip(RoundedCornerShape(radius))) {
        urls.take(4).chunked(2).forEach { row ->
            Row(Modifier.weight(1f)) {
                row.forEach { Artwork(it, Modifier.weight(1f), radius = 0.dp) }
            }
        }
    }
}

// ---- Buttons ---------------------------------------------------------------------------------------------------------

@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.94f else 1f, tween(Motion.FAST), label = "press").value
}

/** The one main action of a screen: red, white text. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    DjButton(text, onClick, modifier, icon, enabled, container = Dj.colors.accent, content = Dj.colors.onAccent)
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    DjButton(text, onClick, modifier, icon, enabled, container = Dj.colors.surfaceSecondary, content = Dj.colors.textPrimary)
}

@Composable
fun TertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Dj.colors.textSecondary) {
    Text(
        text,
        style = DjType.button,
        color = color,
        modifier = modifier.clip(RoundedCornerShape(Radius.sm)).clickable(onClick = onClick).padding(horizontal = Space.xs, vertical = Space.xs),
    )
}

@Composable
private fun DjButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    container: Color,
    content: Color,
) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .scale(pressScale(source))
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(if (enabled) container else Dj.colors.surfaceSecondary)
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .padding(horizontal = Space.lg, vertical = Space.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) content else Dj.colors.textTertiary
        icon?.let {
            Icon(it, null, tint = tint, modifier = Modifier.size(IconSize.sm))
            Spacer(Modifier.width(Space.xs))
        }
        Text(text, style = DjType.button, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A minimal round touch target: 48 dp even when the icon is smaller. */
@Composable
fun DjIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dj.colors.textPrimary,
    size: Dp = IconSize.md,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .scale(pressScale(source))
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) tint else Dj.colors.textTertiary, modifier = Modifier.size(size))
    }
}

/** Play/pause: the primary action of the player, a red circle with a small press. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .scale(pressScale(source))
            .clip(CircleShape)
            .background(Dj.colors.accent)
            .clickable(interactionSource = source, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) DjIcons.Pause else DjIcons.Play,
            if (playing) "Pausa" else "Reproducir",
            tint = Dj.colors.onAccent,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

/**
 * A compact control: icon over a short label, so three or four fit in a row without cutting words. Neutral until
 * [active], then red. For DJ moves and deck controls.
 */
@Composable
fun ActionTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (active) Dj.colors.accent else Dj.colors.surfaceSecondary, tween(Motion.FAST), label = "tile")
    val fg = when {
        !enabled -> Dj.colors.textTertiary
        active -> Dj.colors.onAccent
        else -> Dj.colors.textPrimary
    }
    Column(
        modifier
            .scale(pressScale(source))
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(bg)
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .padding(horizontal = Space.xxs, vertical = Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(IconSize.md))
        Text(label, style = DjType.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
    }
}

/** A filter or tag. Red only when selected. */
@Composable
fun DjChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) Dj.colors.accent else Dj.colors.surfaceSecondary, tween(Motion.FAST), label = "chip")
    Text(
        label,
        style = DjType.label,
        color = if (selected) Dj.colors.onAccent else Dj.colors.textPrimary,
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.xs + 2.dp),
    )
}

// ---- Headers ---------------------------------------------------------------------------------------------------------

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = DjType.section, color = Dj.colors.textPrimary, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TertiaryButton(action, onAction)
    }
}

/** Title of a screen. Pushed screens get a back arrow; tab roots show a large title. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) DjIconButton(DjIcons.Back, "Volver", onBack)
        Text(
            title,
            style = if (onBack == null) DjType.pageTitle else DjType.section,
            color = Dj.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = if (onBack == null) 0.dp else Space.xxs),
        )
        action?.invoke()
    }
}

// ---- Search ----------------------------------------------------------------------------------------------------------

/** The search field. With [onClick] it is a doorway to the Search tab instead of a field you type in. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Buscar canciones, artistas, géneros…",
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(Dj.colors.surfaceSecondary)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = Space.md, end = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DjIcons.Search, null, tint = Dj.colors.textSecondary, modifier = Modifier.size(IconSize.sm))
        Spacer(Modifier.width(Space.sm))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = DjType.body, color = Dj.colors.textTertiary, maxLines = 1)
            if (onClick == null) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = DjType.body.copy(color = Dj.colors.textPrimary),
                    cursorBrush = SolidColor(Dj.colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        trailing?.invoke()
    }
}

// ---- Music items -----------------------------------------------------------------------------------------------------

/**
 * A song in a list: cover, title, artist (or [subtitle]), and something at the end. Rows breathe on spacing, not on
 * borders. [active] marks the song playing now.
 */
@Composable
fun TrackRow(
    track: SourceTrack,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    detail: String? = null,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.imageUrl, Modifier.size(52.dp), radius = Radius.sm)
        Column(Modifier.weight(1f).padding(horizontal = Space.sm)) {
            Text(
                track.name,
                style = DjType.cardTitle,
                color = if (active) Dj.colors.accentBright else Dj.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle ?: track.artist, style = DjType.meta, color = Dj.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = DjType.label, color = Dj.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

/** Cover on top, two lines under it: for horizontal rails. */
@Composable
fun MusicCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    width: Dp = 140.dp,
    onClick: () -> Unit,
    cover: @Composable (Modifier) -> Unit,
) {
    Column(modifier.width(width).clip(RoundedCornerShape(Radius.sm)).clickable(onClick = onClick)) {
        cover(Modifier.fillMaxWidth())
        Text(title, style = DjType.cardTitle, color = Dj.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
        Text(subtitle, style = DjType.meta, color = Dj.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A genre tile: its own music's cover behind the name, darkened at the bottom so the name always reads. */
@Composable
fun GenreCard(title: String, subtitle: String, cover: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.aspectRatio(1.6f).clip(RoundedCornerShape(Radius.md)).clickable(onClick = onClick)) {
        CoverFill(cover)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(Space.sm)) {
            Text(title, style = DjType.cardTitle, color = Color.White, maxLines = 1)
            Text(subtitle, style = DjType.label, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
        }
    }
}

/** A cover filling a non-square box (a banner or a tile), for backgrounds only. */
@Composable
fun CoverFill(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Dj.colors.surfaceSecondary)) {
        url?.let {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(it).crossfade(Motion.BASE).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---- Music data ------------------------------------------------------------------------------------------------------

/** BPM · TONO · ENERGÍA, the three numbers a DJ reads first, compact and technical. */
@Composable
fun MusicFacts(bpm: Double?, key: String?, energy: Double?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .border(BorderStroke(1.dp, Dj.colors.border), RoundedCornerShape(Radius.md))
            .padding(vertical = Space.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Fact(bpm?.let { "%.0f".format(it) } ?: "—", "BPM")
        Fact(key ?: "—", "TONO")
        Fact(energyLabel(energy), "ENERGÍA", highlight = (energy ?: 0.0) >= HIGH_ENERGY)
    }
}

@Composable
private fun Fact(value: String, label: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = DjType.technical, color = if (highlight) Dj.colors.accentBright else Dj.colors.textPrimary)
        Text(label, style = DjType.technicalSmall, color = Dj.colors.textTertiary)
    }
}

fun energyLabel(energy: Double?): String = when {
    energy == null -> "—"
    energy >= HIGH_ENERGY -> "Alta"
    energy >= 0.45 -> "Media"
    else -> "Baja"
}

private const val HIGH_ENERGY = 0.7

/** A thin progress line: red for what has played, neutral for what is left. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Dj.colors.progressInactive)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(Dj.colors.progressActive))
    }
}

/**
 * The song's timeline you can grab, like Spotify's: tap or drag anywhere, the time follows your finger, and the
 * song moves when you let go. The thumb grows while you hold it. [marks] are points worth seeing (a planned change).
 */
@Composable
fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, marks: List<Long> = emptyList()) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(1) }
    val haptics = LocalHapticFeedback.current
    val progress = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val thumb by animateFloatAsState(if (dragging != null) 16f else 10f, tween(Motion.FAST), label = "thumb")
    val active = Dj.colors.progressActive
    val inactive = Dj.colors.progressInactive
    val markColor = Dj.colors.textSecondary

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onSeek(((offset.x / width).coerceIn(0f, 1f) * durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = (it.x / width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ -> dragging = (change.position.x / width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragging?.let { onSeek((it * durationMs).toLong()) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val y = size.height / 2
                val stroke = 4.dp.toPx()
                drawLine(inactive, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(active, Offset(0f, y), Offset(size.width * progress, y), strokeWidth = stroke, cap = StrokeCap.Round)
                if (durationMs > 0) marks.forEach { m ->
                    val x = size.width * (m.toFloat() / durationMs).coerceIn(0f, 1f)
                    drawLine(markColor, Offset(x, y - 6.dp.toPx()), Offset(x, y + 6.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
                drawCircle(active, radius = thumb.dp.toPx() / 2 + 2.dp.toPx(), center = Offset(size.width * progress, y))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(dragging?.let { (it * durationMs).toLong() } ?: positionMs), style = DjType.technicalSmall, color = if (dragging != null) Dj.colors.accentBright else Dj.colors.textTertiary)
            Text(formatTime(durationMs), style = DjType.technicalSmall, color = Dj.colors.textTertiary)
        }
    }
}

// ---- States ----------------------------------------------------------------------------------------------------------

/** A placeholder shaped like what is loading, breathing gently. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, radius: Dp = Radius.sm) {
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeleton",
    )
    Box(modifier.graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(radius)).background(Dj.colors.skeleton))
}

@Composable
fun TrackRowSkeleton() {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Skeleton(Modifier.size(52.dp))
        Column(Modifier.padding(start = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Skeleton(Modifier.width(160.dp).height(12.dp))
            Skeleton(Modifier.width(100.dp).height(10.dp))
        }
    }
}

/** Nothing here yet: a large quiet icon, one sentence, and the way forward. */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.xxxl, horizontal = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Dj.colors.surfaceSecondary), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Dj.colors.textSecondary, modifier = Modifier.size(IconSize.lg))
        }
        Text(title, style = DjType.section, color = Dj.colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.md))
        Text(message, style = DjType.body, color = Dj.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.xs))
        if (action != null && onAction != null) PrimaryButton(action, onAction, Modifier.padding(top = Space.lg))
    }
}

/** What happened and what to do, in words; never a stack trace. */
@Composable
fun InlineMessage(text: String, modifier: Modifier = Modifier, isError: Boolean = true, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(Dj.colors.surfaceSecondary)
            .padding(start = Space.md, end = Space.xs, top = Space.sm, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (isError) Dj.colors.danger else Dj.colors.success))
        Text(text, style = DjType.meta, color = Dj.colors.textPrimary, modifier = Modifier.weight(1f).padding(horizontal = Space.sm))
        if (action != null && onAction != null) TertiaryButton(action, onAction, color = Dj.colors.accentBright)
    }
}

/** A quiet grouped surface, for settings and forms. Not for music: music sits on the background. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(Dj.colors.surface).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) { content() }
}

fun formatTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}
