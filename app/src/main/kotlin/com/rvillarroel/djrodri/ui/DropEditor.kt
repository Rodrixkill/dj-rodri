package com.rvillarroel.djrodri.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.domain.dj.CueEstimator
import com.rvillarroel.djrodri.domain.dj.StyleFamily
import com.rvillarroel.djrodri.domain.dj.TransitionPlanner
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.IconSize
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space
import kotlinx.coroutines.launch

/** One line of what the analysis knows about a song: "reggaetón · 91 BPM · 3B · drop 1:13". */
fun TrackRow.facts(): String {
    val p = profile
    val cues = CueEstimator.estimate(p)
    return buildList {
        StyleFamily.of(p).takeIf { it != StyleFamily.UNKNOWN }?.let { add(it.label) }
        p.bpm?.let { add("${it.toInt()} BPM") }
        p.key?.let { add("$it") }
        cues.drop?.let { add((if (p.userDropMs != null) "drop tuyo " else "drop ") + formatTime(it.positionMs)) }
    }.ifEmpty { listOf("sin analizar todavía") }.joinToString(" · ")
}

/**
 * Everything you can do with a song, from a sheet that slides up: hear it, send it next in the party, fix its drop.
 * [onChanged] runs after a drop is saved, so the list behind can refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSheet(row: TrackRow, onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    val actions = LocalContext.current.container.trackActions
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    if (editing) {
        DropEditorDialog(row, onDismiss = { editing = false; onDismiss() }, onChanged = onChanged)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Dj.colors.surface) {
        Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.xxl)) {
            TrackRow(row.track, detail = row.facts())
            Column(Modifier.padding(top = Space.md)) {
                SheetAction(DjIcons.Play, "Escuchar") {
                    scope.launch {
                        note = activity?.let { actions.preview(it, row.track, 0) }
                        if (note == null) onDismiss()
                    }
                }
                if (actions.canPlayNext(row.track)) {
                    SheetAction(DjIcons.Playlist, "Que suene después") {
                        actions.playNext(row.track)
                        onDismiss()
                    }
                }
                SheetAction(DjIcons.Flag, "Revisar el drop") { editing = true }
            }
            note?.let { InlineMessage(it, Modifier.padding(top = Space.sm)) }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).clickable(onClick = onClick).padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Dj.colors.textPrimary, modifier = Modifier.size(IconSize.md))
        Text(label, style = DjType.body, color = Dj.colors.textPrimary, modifier = Modifier.padding(start = Space.md))
    }
}

/**
 * Where the analysis thinks the drop is, and your way to fix it: listen from a point, tap when the drop hits, or
 * type the minute and second. Your mark beats any estimate in every party from then on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropEditorDialog(row: TrackRow, onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    val actions = LocalContext.current.container.trackActions
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val cues = CueEstimator.estimate(row.profile)
    var marked by remember(row.track.id) { mutableStateOf(row.profile.userDropMs) }
    var typed by remember(row.track.id) { mutableStateOf((marked ?: cues.drop?.positionMs)?.let(::formatTime).orEmpty()) }
    var note by remember { mutableStateOf<String?>(null) }

    fun close() {
        actions.stopPreview()
        onDismiss()
    }

    fun listenFrom(ms: Long) = scope.launch {
        val act = activity ?: return@launch
        // A few seconds before, so you hear the build-up and can tell whether the drop lands where it should.
        val from = (ms - LEAD_IN_MS).coerceAtLeast(0)
        note = actions.preview(act, row.track, from) ?: "Sonando desde ${formatTime(from)}. Toca «¡Drop aquí!» cuando entre."
    }

    fun save(ms: Long) = scope.launch {
        actions.saveDrop(row.track.id, ms)
        marked = ms
        typed = formatTime(ms)
        note = "Drop guardado en ${formatTime(ms)}"
        onChanged()
    }

    ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Dj.colors.surface) {
        Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            TrackRow(row.track)
            val drop = cues.drop
            Text(
                when {
                    marked != null -> "Drop marcado por ti en ${formatTime(marked!!)}"
                    drop == null -> "La app no encontró el drop de esta canción"
                    drop.confidence >= TransitionPlanner.MIN_DROP_CONFIDENCE -> "Drop estimado en ${formatTime(drop.positionMs)}, por el coro"
                    else -> "Drop adivinado en ${formatTime(drop.positionMs)}, por el tempo: revísalo"
                },
                style = DjType.cardTitle,
                color = Dj.colors.textPrimary,
            )
            if (cues.drops.isNotEmpty()) {
                Text("MOMENTOS FUERTES", style = DjType.technicalSmall, color = Dj.colors.textTertiary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    cues.drops.forEach { ms ->
                        DjChip(formatTime(ms), selected = typed == formatTime(ms), onClick = {
                            typed = formatTime(ms)
                            listenFrom(ms)
                        })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                    singleLine = true,
                    textStyle = DjType.technical.copy(color = Dj.colors.textPrimary),
                    cursorBrush = SolidColor(Dj.colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(Dj.colors.surfaceSecondary)
                        .padding(horizontal = Space.md, vertical = Space.sm + 2.dp),
                )
                SecondaryButton("Escuchar", { parseTime(typed)?.let(::listenFrom) ?: run { note = "Escribe el tiempo como 1:05" } }, icon = DjIcons.Play)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PrimaryButton("¡Drop aquí!", {
                    val now = actions.positionOf(row.track)
                    if (now == null) note = "Primero dale Escuchar: se marca mientras suena esta canción" else save(now)
                }, Modifier.weight(1f), icon = DjIcons.Flag)
                SecondaryButton("Guardar", {
                    val ms = parseTime(typed)
                    if (ms == null || ms >= row.track.durationMs) note = "Ese tiempo no está dentro de la canción" else save(ms)
                }, Modifier.weight(1f))
            }
            note?.let { InlineMessage(it, isError = false) }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                if (marked != null) {
                    TertiaryButton("Volver al automático", {
                        scope.launch {
                            actions.clearDrop(row.track.id)
                            marked = null
                            note = "Se usará el drop que estima la app"
                            onChanged()
                        }
                    })
                }
                TertiaryButton("Listo", ::close)
            }
        }
    }
}

/** "1:05" → 65000. Null when it is not a time. */
internal fun parseTime(text: String): Long? {
    val parts = text.trim().split(":")
    val seconds = when (parts.size) {
        1 -> parts[0].toLongOrNull()
        2 -> {
            val min = parts[0].toLongOrNull() ?: return null
            val sec = parts[1].toLongOrNull()?.takeIf { it < 60 } ?: return null
            min * 60 + sec
        }
        else -> null
    } ?: return null
    return seconds * 1_000
}

private const val LEAD_IN_MS = 8_000L
