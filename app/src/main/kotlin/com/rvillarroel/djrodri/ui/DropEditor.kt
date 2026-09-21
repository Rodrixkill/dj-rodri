package com.rvillarroel.djrodri.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rvillarroel.djrodri.domain.dj.CueEstimator
import com.rvillarroel.djrodri.domain.dj.TransitionPlanner
import com.rvillarroel.djrodri.ui.theme.DjColors
import kotlinx.coroutines.launch

/**
 * Where the analysis thinks the drop is, and your way to fix it: listen from a point, tap when the drop hits, or
 * type the minute and second. Your mark beats any estimate in every party from then on.
 */
@Composable
fun DropEditorDialog(row: TrackRow, vm: PlaylistDetailViewModel, onDismiss: () -> Unit) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val cues = CueEstimator.estimate(row.profile)
    val marked = row.profile.userDropMs
    var typed by remember(row.track.id, marked) { mutableStateOf(cues.drop?.positionMs?.let(::formatTime).orEmpty()) }
    var note by remember { mutableStateOf<String?>(null) }

    fun listenFrom(ms: Long) = scope.launch {
        val act = activity ?: return@launch
        // A few seconds before, so you hear the build-up and can tell whether the drop lands where it should.
        note = vm.preview(act, row, (ms - LEAD_IN_MS).coerceAtLeast(0)) ?: "Sonando desde ${formatTime((ms - LEAD_IN_MS).coerceAtLeast(0))}…"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.track.name, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(row.track.artist, color = DjColors.TextDim)
                val drop = cues.drop
                Text(
                    when {
                        marked != null -> "Drop marcado por ti en ${formatTime(marked)}"
                        drop == null -> "La app no encontró el drop de esta canción"
                        drop.confidence >= TransitionPlanner.MIN_DROP_CONFIDENCE ->
                            "Drop estimado en ${formatTime(drop.positionMs)} (por el coro)"
                        else -> "Drop adivinado en ${formatTime(drop.positionMs)} (por el tempo: revísalo)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (cues.drops.isNotEmpty()) {
                    Text("Otros momentos fuertes: toca uno para escucharlo", color = DjColors.TextDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cues.drops.forEach { ms ->
                            OutlinedButton(onClick = {
                                typed = formatTime(ms)
                                listenFrom(ms)
                            }) { Text(formatTime(ms)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                    label = { Text("Drop (min:seg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { parseTime(typed)?.let(::listenFrom) ?: run { note = "Escribe el tiempo como 1:05" } }) {
                        Text("▶ Escuchar ahí")
                    }
                    Button(onClick = {
                        val now = vm.positionOf(row)
                        if (now == null) {
                            note = "Primero dale ▶ Escuchar: marca mientras suena esta canción"
                        } else {
                            typed = formatTime(now)
                            vm.saveDrop(row.track.id, now)
                            note = "Drop guardado en ${formatTime(now)}"
                        }
                    }) { Text("¡Drop aquí!") }
                }
                note?.let { Text(it, color = DjColors.Neon) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ms = parseTime(typed)
                if (ms == null || ms >= row.track.durationMs) {
                    note = "Ese tiempo no está dentro de la canción"
                } else {
                    vm.saveDrop(row.track.id, ms)
                    onDismiss()
                }
            }) { Text("Guardar") }
        },
        dismissButton = {
            Column {
                if (marked != null) TextButton(onClick = { vm.clearDrop(row.track.id); onDismiss() }) { Text("Volver al automático") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
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
