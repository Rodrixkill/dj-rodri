package com.rvillarroel.djrodri.ui

import android.app.Activity
import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.analysis.AnalysisUiState
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.analysis.Coverage
import com.rvillarroel.djrodri.library.db.PlaylistEntity
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val coverage = MutableStateFlow<Coverage?>(null)
    val rows = MutableStateFlow<List<TrackRow>?>(null)
    val message = MutableStateFlow<String?>(null)
    val starting = MutableStateFlow(false)
    val party = container.party.state
    val covers = MutableStateFlow<List<String>>(emptyList())

    fun analysis(playlistId: String) = container.analysisScheduler.state(playlistId)
    fun playlist(playlistId: String) = container.library.playlist(playlistId)

    private var loadedId: String? = null

    fun load(playlistId: String) = viewModelScope.launch {
        // The view model outlives the screen: without this, one playlist's error shows up on the next one.
        message.value = null
        if (playlistId != loadedId) {
            rows.value = null
            coverage.value = null
            loadedId = playlistId
        }
        runCatching {
            val started = android.os.SystemClock.elapsedRealtime()
            val tracks = container.library.tracks(playlistId)
            val read = android.os.SystemClock.elapsedRealtime()
            rows.value = container.trackActions.rows(tracks)
            android.util.Log.d("DJRodri", "Playlist ${tracks.size} canciones: leídas en ${read - started} ms, perfiles en ${android.os.SystemClock.elapsedRealtime() - read} ms")
            coverage.value = container.analyzer.coverage(tracks)
            covers.value = container.library.coversOf(playlistId)
        }.onFailure {
            message.value = it.message
            if (rows.value == null) rows.value = emptyList()
        }
    }

    fun reload() = loadedId?.let { load(it) }

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

/**
 * A playlist: big cover, name, what it holds, one red play. The analysis is a single quiet line, the songs follow.
 * Tapping a song opens what you can do with it, drop included.
 */
@Composable
fun PlaylistDetailScreen(playlistId: String, onBack: () -> Unit, onPartyStarted: () -> Unit, vm: PlaylistDetailViewModel = viewModel()) {
    val playlist by vm.playlist(playlistId).collectAsStateWithLifecycle(null)
    val analysis by vm.analysis(playlistId).collectAsStateWithLifecycle(AnalysisUiState.Idle)
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val party by vm.party.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<TrackRow?>(null) }

    LaunchedEffect(playlistId, analysis is AnalysisUiState.Done) { vm.load(playlistId) }

    val play: () -> Unit = {
        when {
            party.active && party.playlistId == playlistId -> onPartyStarted()
            party.active -> vm.switchTo(playlistId, onPartyStarted)
            else -> activity?.let { a -> scope.launch { if (vm.startParty(a, playlistId)) onPartyStarted() } }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.xs)) {
        item { ScreenHeader("", onBack = onBack) }
        item { Header(playlist, covers, rows?.let { list -> list.size to list.sumOf { it.track.durationMs } }) }
        item {
            Row(Modifier.padding(top = Space.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PlayButton(playing = false, onClick = { if (!starting && rows.orEmpty().isNotEmpty()) play() }, size = 56.dp)
                Column(Modifier.weight(1f).padding(start = Space.xs)) {
                    Text(
                        when {
                            starting -> "Conectando con Spotify…"
                            party.active && party.playlistId == playlistId -> "Suena en la fiesta"
                            party.active -> "Cambiar la fiesta a esta playlist"
                            else -> "A la fiesta"
                        },
                        style = DjType.cardTitle,
                        color = Dj.colors.textPrimary,
                    )
                    Text("El DJ elige el orden y dónde cambiar", style = DjType.meta, color = Dj.colors.textSecondary)
                }
                AnalysisAction(analysis, coverage, onAnalyze = { vm.analyze(playlistId, refresh = it) }, onCancel = { vm.cancelAnalysis(playlistId) })
            }
        }
        item { AnalysisLine(analysis, coverage, Modifier.padding(top = Space.md)) }
        message?.let { item { InlineMessage(it, Modifier.padding(top = Space.sm)) } }

        val list = rows
        when {
            list == null -> items(8) { TrackRowSkeleton() }
            list.isEmpty() -> item { EmptyState(DjIcons.Music, "Sin canciones", "Spotify no devolvió canciones de esta playlist.") }
            else -> {
                item { SectionHeader("Canciones") }
                items(list, key = { it.track.id }) { row ->
                    TrackRow(
                        row.track,
                        detail = row.facts(),
                        active = party.current?.id == row.track.id,
                        onClick = { sheet = row },
                        trailing = { Text(formatTime(row.track.durationMs), style = DjType.meta, color = Dj.colors.textTertiary) },
                    )
                }
            }
        }
    }

    sheet?.let { row ->
        // The row is refreshed after a drop is saved, so the sheet always shows the drop as it is now.
        val fresh = rows?.firstOrNull { it.track.id == row.track.id } ?: row
        TrackSheet(fresh, onDismiss = { sheet = null }, onChanged = { vm.reload() })
    }
}

@Composable
private fun Header(playlist: PlaylistEntity?, covers: List<String>, summary: Pair<Int, Long>?) {
    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        val art = if (wide) 220.dp else 188.dp
        val cover: @Composable (Modifier) -> Unit = { m ->
            if (playlist?.imageUrl != null) Artwork(playlist.imageUrl, m, radius = Radius.lg) else Mosaic(covers, m, radius = Radius.lg)
        }
        val text: @Composable () -> Unit = {
            Column {
                Text("PLAYLIST", style = DjType.technicalSmall, color = Dj.colors.textTertiary)
                Text(playlist?.name ?: "", style = DjType.pageTitle, color = Dj.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
                summary?.let { (count, duration) ->
                    Text("$count canciones · ${formatDuration(duration)}", style = DjType.meta, color = Dj.colors.textSecondary, modifier = Modifier.padding(top = Space.xxs))
                }
            }
        }
        if (wide) {
            Row(verticalAlignment = Alignment.Bottom) {
                cover(Modifier.size(art))
                Column(Modifier.padding(start = Space.xl)) { text() }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                cover(Modifier.size(art))
                Column(Modifier.fillMaxWidth().padding(top = Space.lg)) { text() }
            }
        }
    }
}

/** Analyze, analyze again, or stop: a small secondary action beside the play button. */
@Composable
private fun AnalysisAction(state: AnalysisUiState, coverage: Coverage?, onAnalyze: (refresh: Boolean) -> Unit, onCancel: () -> Unit) {
    when (state) {
        is AnalysisUiState.Running -> DjIconButton(DjIcons.Close, "Cancelar análisis", onCancel, tint = Dj.colors.textSecondary)
        else -> {
            val analyzed = coverage?.let { it.total > 0 && it.withSyncedLyrics + it.withTempo > 0 } == true
            DjIconButton(DjIcons.Refresh, if (analyzed) "Analizar canciones nuevas" else "Analizar playlist", { onAnalyze(analyzed) }, tint = Dj.colors.textSecondary)
        }
    }
}

/** Where the analysis stands, in one line, with a thin bar while it runs. */
@Composable
private fun AnalysisLine(state: AnalysisUiState, coverage: Coverage?, modifier: Modifier = Modifier) {
    Column(modifier) {
        when (state) {
            is AnalysisUiState.Running -> {
                Text(
                    "Analizando ${state.done} de ${state.total}" + (state.title?.let { " · $it" } ?: ""),
                    style = DjType.meta,
                    color = Dj.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressLine(if (state.total == 0) 0f else state.done.toFloat() / state.total, Modifier.padding(top = Space.xs))
                Text("Sigue en segundo plano; lo analizado ya quedó guardado.", style = DjType.label, color = Dj.colors.textTertiary, modifier = Modifier.padding(top = Space.xs))
            }
            is AnalysisUiState.Failed -> InlineMessage("El análisis se detuvo: ${state.message}")
            else -> coverage?.let { c ->
                val text = if (c.total > 0 && c.withSyncedLyrics + c.withTempo == 0) {
                    "Sin analizar: toca ↻ para que el DJ aprenda dónde están los coros y los drops"
                } else {
                    "${c.withChorus} con coro · ${c.withDrop} con drop · ${c.withTempo} con BPM · ${c.alwaysWhole} siempre completas"
                }
                Text(text, style = DjType.meta, color = Dj.colors.textSecondary)
                if (c.failed > 0) Text("${c.failed} no se pudieron analizar; se reintentan la próxima vez", style = DjType.label, color = Dj.colors.textTertiary)
            }
        }
    }
}
