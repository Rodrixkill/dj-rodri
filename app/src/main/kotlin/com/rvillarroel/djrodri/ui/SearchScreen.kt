package com.rvillarroel.djrodri.ui

import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.StyleMix
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.IconSize
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<TrackRow>?>(null)
    val recent = MutableStateFlow(container.settings.recentSearches)
    val mixes = MutableStateFlow<List<StyleMix>?>(null)

    /** A style picked from "Explorar por estilo": its songs are the results. */
    val style = MutableStateFlow<StyleMix?>(null)
    val message = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null
    val genresState = container.analysisScheduler.libraryGenresState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.rvillarroel.djrodri.analysis.AnalysisUiState.Idle)

    fun refreshGenres() = container.analysisScheduler.refreshLibraryGenres()

    /** You corrected a song's style: the groups are read again, and the open style shows its songs without it. */
    fun styleChanged() = viewModelScope.launch {
        val all = runCatching { container.styleMixes() }.getOrDefault(mixes.value.orEmpty())
        mixes.value = all
        val open = style.value ?: return@launch
        val updated = all.firstOrNull { it.family == open.family }
        if (updated == null) closeStyle() else openStyle(updated)
    }

    fun load() = viewModelScope.launch {
        // Only reads the style groups the last analysis saved: nothing is recomputed by opening this tab.
        mixes.value = runCatching { container.styleMixes() }.getOrDefault(mixes.value.orEmpty())
    }

    fun type(text: String) {
        query.value = text
        style.value = null
        searchJob?.cancel()
        if (text.isBlank()) {
            results.value = null
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val found = container.library.search(text)
            // A style you named ("reggaeton", "house") brings its songs too.
            val styled = mixes.value.orEmpty().filter { mix ->
                mix.family.label.contains(text.trim(), ignoreCase = true) ||
                    com.rvillarroel.djrodri.domain.dj.StyleFamily.genresOf(mix.family).any { it.contains(text.trim(), ignoreCase = true) }
            }.flatMap { it.tracks }
            results.value = container.trackActions.rows((found + styled).distinctBy { it.id }.take(MAX_RESULTS))
        }
    }

    fun submit() {
        container.settings.rememberSearch(query.value)
        recent.value = container.settings.recentSearches
    }

    fun forget(text: String) {
        container.settings.forgetSearch(text)
        recent.value = container.settings.recentSearches
    }

    fun openStyle(mix: StyleMix) = viewModelScope.launch {
        style.value = mix
        query.value = ""
        results.value = container.trackActions.rows(mix.tracks.take(MAX_RESULTS))
    }

    fun closeStyle() {
        style.value = null
        results.value = null
    }

    fun playStyle(activity: android.app.Activity, mix: StyleMix, onStarted: () -> Unit) = viewModelScope.launch {
        val failure = container.startMix(activity, mix)
        message.value = failure
        if (failure == null) onStarted()
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val MAX_RESULTS = 80
    }
}

/**
 * Search over your own library: songs, artists, albums and styles. Spotify's catalog search is left out on purpose:
 * this app works on the music you already put in your playlists.
 */
@Composable
fun SearchScreen(onParty: () -> Unit, vm: SearchViewModel = viewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val genresState by vm.genresState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var sheet by remember { mutableStateOf<TrackRow?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    // When a genre update finishes, the style groups it rebuilt are shown.
    LaunchedEffect(genresState) { if (genresState is com.rvillarroel.djrodri.analysis.AnalysisUiState.Done) vm.styleChanged() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.md)) {
        item { ScreenHeader(style?.let { "Mezcla ${it.family.label}" } ?: "Buscar", onBack = style?.let { { vm.closeStyle() } }) }
        if (style == null) {
            item {
                SearchField(
                    query,
                    vm::type,
                    Modifier.padding(top = Space.xs),
                    trailing = { if (query.isNotEmpty()) DjIconButton(DjIcons.Close, "Borrar", { vm.type("") }, size = IconSize.sm) },
                )
            }
        } else {
            item {
                Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    PrimaryButton("Poner esta mezcla", { activity?.let { vm.playStyle(it, style!!, onParty) } }, icon = DjIcons.Play)
                }
            }
            item {
                Text(
                    "¿Alguna no es ${style!!.family.label}? Tócala y corrige su género.",
                    style = DjType.meta,
                    color = Dj.colors.textSecondary,
                    modifier = Modifier.padding(top = Space.sm),
                )
            }
        }
        message?.let { item { InlineMessage(it, Modifier.padding(top = Space.sm)) } }

        val list = results
        when {
            list != null && list.isEmpty() -> item {
                EmptyState(DjIcons.Search, "Nada con «$query»", "Busca por canción, artista, álbum o estilo. Solo aparecen canciones de tus playlists.")
            }
            list != null -> {
                if (style == null) item { SectionHeader("Resultados") }
                items(list, key = { it.track.id }) { row ->
                    TrackRow(row.track, detail = row.facts(), onClick = {
                        vm.submit()
                        sheet = row
                    }, trailing = { Text(formatTime(row.track.durationMs), style = DjType.meta, color = Dj.colors.textTertiary) })
                }
            }
            else -> {
                if (recent.isNotEmpty()) {
                    item { SectionHeader("Búsquedas recientes") }
                    items(recent, key = { it }) { text -> RecentSearch(text, onClick = { vm.type(text) }, onForget = { vm.forget(text) }) }
                }
                item { SectionHeader("Explorar por estilo") }
                val styles = mixes
                if (styles == null) {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) { repeat(2) { Skeleton(Modifier.weight(1f).size(96.dp), Radius.md) } } }
                } else if (styles.isEmpty()) {
                    item { EmptyState(DjIcons.Music, "Todavía no hay estilos", "Analiza una playlist y aquí aparecerán tus canciones agrupadas por estilo.") }
                } else {
                    item { StyleGrid(styles, vm::openStyle) }
                    item { GenreRefresh(genresState, vm::refreshGenres) }
                }
            }
        }
    }

    sheet?.let { TrackSheet(it, onDismiss = { sheet = null }, onChanged = vm::styleChanged) }
}

/** Genres asked again for the whole library, with the sources that decide by votes. Only when you ask. */
@Composable
private fun GenreRefresh(state: com.rvillarroel.djrodri.analysis.AnalysisUiState, onRefresh: () -> Unit) {
    Column(Modifier.padding(top = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            "¿Canciones en el estilo equivocado? Los géneros ahora se deciden entre Deezer y MusicBrainz. Actualízalos para toda tu biblioteca (tarda cerca de un segundo por canción) o corrige una canción tocándola.",
            style = DjType.meta,
            color = Dj.colors.textSecondary,
        )
        when (state) {
            is com.rvillarroel.djrodri.analysis.AnalysisUiState.Running -> {
                ProgressLine(if (state.total > 0) state.done.toFloat() / state.total else 0f)
                Text(
                    if (state.total > 0) "Actualizando géneros: ${state.done} de ${state.total}" else "Preparando…",
                    style = DjType.meta,
                    color = Dj.colors.textSecondary,
                )
            }
            is com.rvillarroel.djrodri.analysis.AnalysisUiState.Failed -> InlineMessage(state.message, action = "Reintentar", onAction = onRefresh)
            else -> SecondaryButton("Actualizar géneros", onRefresh, icon = DjIcons.Refresh)
        }
    }
}

@Composable
private fun RecentSearch(text: String, onClick: () -> Unit, onForget: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).clickable(onClick = onClick).padding(start = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DjIcons.Clock, null, tint = Dj.colors.textTertiary, modifier = Modifier.size(IconSize.sm))
        Text(text, style = DjType.body, color = Dj.colors.textPrimary, modifier = Modifier.weight(1f).padding(horizontal = Space.sm))
        DjIconButton(DjIcons.Close, "Quitar", onForget, tint = Dj.colors.textTertiary, size = IconSize.sm)
    }
}

/** Two tiles per row on the phone, more when there is room. */
@Composable
private fun StyleGrid(styles: List<StyleMix>, onOpen: (StyleMix) -> Unit) {
    BoxWithConstraints {
        val columns = if (maxWidth >= 600.dp) 4 else 2
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            styles.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    row.forEach { mix ->
                        GenreCard(
                            mix.family.label.replaceFirstChar { it.uppercase() },
                            "${mix.tracks.size} canciones",
                            mix.covers.firstOrNull(),
                            Modifier.weight(1f),
                        ) { onOpen(mix) }
                    }
                    repeat(columns - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
