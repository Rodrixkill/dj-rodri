package com.rvillarroel.djrodri.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.ui.ScreenHeader
import kotlinx.coroutines.delay

/** Laboratorio: the phase 0 probes, kept in the app to re-measure after Spotify or HyperOS updates. */
@Composable
fun LabScreen(onBack: () -> Unit, vm: SpikeViewModel = viewModel()) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Laboratorio", onBack = onBack)
        SpikeScreen(vm, onConnectRemote = { vm.connectRemote(context) }, onReport = { vm.writeReport(context) })
    }
}

@Composable
private fun SpikeScreen(vm: SpikeViewModel, onConnectRemote: () -> Unit, onReport: () -> Unit) {
    val user by vm.userName.collectAsStateWithLifecycle()
    val connected by vm.remote.connected.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val selected by vm.selectedPlaylist.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val nowPlaying by vm.remote.nowPlaying.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val logLines by vm.log.lines.collectAsStateWithLifecycle()
    val reportPath by vm.reportPath.collectAsStateWithLifecycle()

    var cutSeconds by remember { mutableStateOf("10") }
    var dropSeconds by remember { mutableStateOf("60") }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(200) } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("DJ Rodri · Fase 0", style = MaterialTheme.typography.headlineMedium) }

        item {
            Section("1. Conexión") {
                Text(if (user != null) "Web API: $user" else "Web API: sin sesión")
                Text("App Remote: ${if (connected) "conectado" else "desconectado"}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                    Button(onClick = onConnectRemote) { Text("Conectar App Remote") }
                    OutlinedButton(onClick = { vm.readCrossfade() }) { Text("Leer crossfade") }
                }
            }
        }

        item {
            Section("2. Sonando") {
                val np = nowPlaying
                if (np == null) Text("Nada (o Spotify no envía la canción)")
                else {
                    val pos = np.snapshot.positionAt(now)
                    Text("${np.track.name} — ${np.track.artist?.name}")
                    Text("%d:%02d / %d:%02d%s".format(pos / 60000, pos / 1000 % 60, np.track.duration / 60000, np.track.duration / 1000 % 60, if (np.snapshot.isPaused) " (pausa)" else ""))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.remote.skipPrevious() }) { Text("⏮") }
                    Button(onClick = { if (nowPlaying?.snapshot?.isPaused == false) vm.remote.pause() else vm.remote.resume() }) { Text("⏯") }
                    Button(onClick = { vm.remote.skipNext() }) { Text("⏭") }
                }
            }
        }

        item {
            Section("3. Pruebas de tiempo") {
                if (busy != null) Text("⏳ $busy", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { vm.probeDrift() }) { Text("Medir deriva de posición (20 s)") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(cutSeconds, "seg", { cutSeconds = it })
                    Button(onClick = { vm.probeCut(cutSeconds.toIntOrNull() ?: 10) }) { Text("Corte natural en X s") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(dropSeconds, "drop s", { dropSeconds = it })
                    Column {
                        Button(onClick = { vm.probeDropEntry(dropSeconds.toIntOrNull() ?: 60, withPause = false) }) { Text("Siguiente directo al drop") }
                        OutlinedButton(onClick = { vm.probeDropEntry(dropSeconds.toIntOrNull() ?: 60, withPause = true) }) { Text("…con pausa corta") }
                    }
                }
                Rating("Entrada en drop", vm)
                OutlinedButton(onClick = { vm.probeEndSeek() }) { Text("Saltar al final (¿crossfade?)") }
                Rating("Final + crossfade", vm)
                Button(onClick = { vm.probeFadeIntoDrop(dropSeconds.toIntOrNull() ?: 60) }) { Text("Fundido a la cola + drop") }
                OutlinedButton(onClick = { vm.probeFadeIntoDrop(0) }) { Text("Fundido a la cola, desde el inicio") }
                Rating("Fundido + drop", vm)
                OutlinedButton(onClick = { vm.probeWebApiSkip() }) { Text("Siguiente vía Web API (respaldo)") }
            }
        }

        item {
            Section("4. Playlists y cobertura") {
                Text(selected?.let { "Elegida: ${it.name} (${tracks.size} canciones)" } ?: "Elige una playlist")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.playPlaylist() }, enabled = selected != null) { Text("Reproducir") }
                    Button(onClick = { vm.runCoverage() }, enabled = tracks.isNotEmpty()) { Text("Analizar cobertura (30)") }
                }
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            OutlinedButton(onClick = { vm.selectPlaylist(playlist) }, modifier = Modifier.fillMaxWidth()) {
                Text("${playlist.name}${playlist.total?.let { " · $it" } ?: ""}")
            }
        }
        // No keys: a playlist can hold the same song twice.
        items(tracks.take(15)) { track ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${track.name} — ${track.artist}", Modifier.weight(1f), fontSize = 13.sp)
                OutlinedButton(onClick = { vm.remote.play(track.uri) }) { Text("▶") }
                OutlinedButton(onClick = { vm.remote.queue(track.uri) }) { Text("+cola") }
            }
        }

        item {
            Section("5. Informe") {
                Button(onClick = onReport) { Text("Guardar informe") }
                reportPath?.let {
                    Text("Sácalo al PC con:", fontSize = 13.sp)
                    Text("adb pull \"$it\" spike-reports/", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        item { HorizontalDivider() }
        items(logLines.takeLast(80).reversed()) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun NumberField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.padding(end = 4.dp).fillMaxWidth(0.3f),
    )
}

@Composable
private fun Rating(what: String, vm: SpikeViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.rate(what, "sonó como DJ") }) { Text("👍 Sonó bien") }
        OutlinedButton(onClick = { vm.rate(what, "se notó el corte") }) { Text("👎 Se notó") }
    }
}
