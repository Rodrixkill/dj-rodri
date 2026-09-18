package com.rvillarroel.djrodri.ui

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.analysis.AnalysisUiState
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.analysis.Coverage
import com.rvillarroel.djrodri.domain.dj.CueEstimator
import com.rvillarroel.djrodri.domain.dj.TrackProfile
import com.rvillarroel.djrodri.domain.dj.TransitionPlanner
import com.rvillarroel.djrodri.data.analysis.SourceTrack
import com.rvillarroel.djrodri.ui.theme.DjColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class TrackRow(val track: SourceTrack, val profile: TrackProfile)

class PlaylistDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val coverage = MutableStateFlow<Coverage?>(null)
    val rows = MutableStateFlow<List<TrackRow>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val starting = MutableStateFlow(false)
    val party = container.party.state

    fun analysis(playlistId: String) = container.analysisScheduler.state(playlistId)
    fun playlist(playlistId: String) = container.library.playlist(playlistId)

    fun load(playlistId: String) = viewModelScope.launch {
        runCatching {
            val tracks = container.library.tracks(playlistId)
            val profiles = container.analyzer.profiles(tracks).associateBy { it.id }
            rows.value = tracks.distinctBy { it.id }.map { TrackRow(it, profiles.getValue(it.id)) }
            coverage.value = container.analyzer.coverage(tracks)
        }.onFailure { message.value = it.message }
    }

    fun analyze(playlistId: String, refresh: Boolean) = container.analysisScheduler.analyze(playlistId, refresh)

    /** Stops the analysis. Songs already analyzed stay analyzed; continuing picks up from there. */
    fun cancelAnalysis(playlistId: String) {
        container.analysisScheduler.cancel(playlistId)
        load(playlistId)
    }

    suspend fun startParty(activity: Activity, playlistId: String): Boolean {
        starting.value = true
        val error = container.startParty(activity, playlistId)
        starting.value = false
        message.value = error
        return error == null
    }

    fun switchTo(playlistId: String, onDone: () -> Unit) = viewModelScope.launch {
        message.value = container.switchParty(playlistId)
        if (message.value == null) onDone()
    }
}

@Composable
fun PlaylistDetailScreen(playlistId: String, onBack: () -> Unit, onPartyStarted: () -> Unit, vm: PlaylistDetailViewModel = viewModel()) {
    val playlist by vm.playlist(playlistId).collectAsStateWithLifecycle(null)
    val analysis by vm.analysis(playlistId).collectAsStateWithLifecycle(AnalysisUiState.Idle)
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val party by vm.party.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(playlistId, analysis is AnalysisUiState.Done) { vm.load(playlistId) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader(playlist?.name ?: "Playlist", onBack = onBack) }

        item {
            Panel {
                when (val a = analysis) {
                    is AnalysisUiState.Running -> {
                        Text("Analizando… ${a.done} de ${a.total}", style = MaterialTheme.typography.titleMedium)
                        a.title?.let { Text(it, color = DjColors.TextDim) }
                        LinearProgressIndicator(progress = { if (a.total == 0) 0f else a.done.toFloat() / a.total }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "Puedes salir de la app: sigue en segundo plano. Lo analizado hasta aquí ya quedó guardado.",
                            color = DjColors.TextDim,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedAction("Cancelar análisis", { vm.cancelAnalysis(playlistId) })
                    }
                    is AnalysisUiState.Failed -> {
                        Text("El análisis falló: ${a.message}", color = DjColors.Warning)
                        BigButton("Reintentar", { vm.analyze(playlistId, refresh = false) }, Modifier.fillMaxWidth())
                    }
                    else -> {
                        coverage?.let { CoverageSummary(it) }
                        val analyzed = coverage?.let { it.total > 0 && it.withSyncedLyrics + it.withTempo > 0 } == true
                        BigButton(
                            if (analyzed) "Analizar de nuevo (nuevas canciones)" else "Analizar playlist",
                            { vm.analyze(playlistId, refresh = true) },
                            Modifier.fillMaxWidth(),
                            color = if (analyzed) DjColors.StageRaised else DjColors.Neon,
                        )
                    }
                }
            }
        }

        item {
            if (party.active && party.playlistId != playlistId) {
                BigButton("Cambiar la fiesta a esta playlist", { vm.switchTo(playlistId, onPartyStarted) }, Modifier.fillMaxWidth(), color = DjColors.Drop)
            } else if (!party.active) {
                BigButton(
                    if (starting) "Conectando con Spotify…" else "🎉 ¡A la fiesta!",
                    { activity?.let { a -> scope.launch { if (vm.startParty(a, playlistId)) onPartyStarted() } } },
                    Modifier.fillMaxWidth(),
                    color = DjColors.Drop,
                    enabled = !starting && rows.isNotEmpty(),
                )
            } else {
                BigButton("Ver la fiesta", onPartyStarted, Modifier.fillMaxWidth(), color = DjColors.Drop)
            }
            message?.let { Text(it, color = DjColors.Warning, modifier = Modifier.padding(top = 6.dp)) }
        }

        items(rows, key = { it.track.id }) { row -> TrackLine(row) }
    }
}

@Composable
private fun CoverageSummary(c: Coverage) {
    Text("${c.total} canciones", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("con coro", "${c.withChorus}", color = DjColors.Neon)
        Stat("con drop", "${c.withDrop}", color = DjColors.Drop)
        Stat("con BPM", "${c.withTempo}")
        Stat("con género", "${c.withGenres}")
        Stat("siempre completas", "${c.alwaysWhole}", color = DjColors.Whole)
    }
    if (c.failed > 0) Text("${c.failed} no se pudieron analizar (se reintentan la próxima vez)", color = DjColors.Warning)
}

@Composable
private fun TrackLine(row: TrackRow) {
    val cues = CueEstimator.estimate(row.profile)
    val p = row.profile
    Panel {
        Text("${row.track.name} — ${row.track.artist}", style = MaterialTheme.typography.titleMedium)
        val facts = buildList {
            if (p.genres.isNotEmpty()) add(p.genres.take(3).joinToString("/"))
            p.bpm?.let { add("${it.toInt()} BPM") }
            p.key?.let { add("tono $it") }
            p.chorus?.let { add("coro x${it.occurrences.size}") }
            cues.drop?.let { add((if (p.userDropMs != null) "drop marcado " else "drop ") + formatTime(it.positionMs)) }
            if (p.durationMs < TransitionPlanner.SHORT_SONG_MS || cues.cuts.isEmpty()) add("suena completa")
        }
        Text(facts.ifEmpty { listOf("sin datos todavía") }.joinToString(" · "), color = DjColors.TextDim)
    }
}

@Composable
fun OutlinedAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
}
