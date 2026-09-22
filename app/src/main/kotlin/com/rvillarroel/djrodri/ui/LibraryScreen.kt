package com.rvillarroel.djrodri.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.library.db.PlaylistWithProgress
import com.rvillarroel.djrodri.spotify.LIKED_SONGS_ID
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.IconSize
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val playlists = container.library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recent = container.library.recentlyPlayed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dropMarks = container.database.analysis().dropMarkCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val refreshing = MutableStateFlow(false)

    /** Only a pull shows the spinner; the quiet check on opening the tab doesn't get in your way. */
    val pulled = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val covers = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    init {
        // A playlist just made in Spotify shows up on its own: the list is read again each time the app opens it.
        refresh()
    }

    fun refresh(byPull: Boolean = false) = viewModelScope.launch {
        if (refreshing.value) {
            pulled.value = pulled.value || byPull
            return@launch
        }
        pulled.value = byPull
        refreshing.value = true
        error.value = runCatching { container.library.refreshPlaylists() }.exceptionOrNull()?.let {
            "No se pudieron leer tus playlists de Spotify. Revisa la conexión y vuelve a intentar."
        }
        refreshing.value = false
        pulled.value = false
    }

    fun loadCovers(ids: List<String>) = viewModelScope.launch {
        covers.value = ids.associateWith { container.library.coversOf(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** Your collection: the shortcuts first, then every playlist as a cover you can recognize at a glance. */
@Composable
fun LibraryScreen(onOpenPlaylist: (String) -> Unit, onSettings: () -> Unit, vm: LibraryViewModel = viewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val dropMarks by vm.dropMarks.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val pulled by vm.pulled.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()

    // First visit: the local copy is empty, so read it from Spotify once.
    LaunchedEffect(playlists) {
        val list = playlists ?: return@LaunchedEffect
        vm.loadCovers(list.filter { it.imageUrl == null }.map { it.id })
    }

    // Pull the list down to read your playlists from Spotify again.
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = { vm.refresh(byPull = true) },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = pulled,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = Dj.colors.surface,
                color = Dj.colors.accent,
            )
        },
    ) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.md)) {
        item {
            ScreenHeader("Biblioteca", action = {
                DjIconButton(DjIcons.Refresh, "Actualizar playlists", { vm.refresh() }, enabled = !refreshing, tint = Dj.colors.textSecondary)
                DjIconButton(DjIcons.Settings, "Ajustes", onSettings, tint = Dj.colors.textSecondary)
            })
        }
        error?.let { item { InlineMessage(it, Modifier.padding(vertical = Space.xs), action = "Reintentar", onAction = { vm.refresh() }) } }

        item {
            Column(Modifier.padding(top = Space.xs)) {
                LibraryLink(DjIcons.Heart, "Tus me gusta", "Tus canciones guardadas en Spotify") { onOpenPlaylist(LIKED_SONGS_ID) }
                LibraryLink(DjIcons.Clock, "Escuchado en tus fiestas", if (recent.isEmpty()) "Todavía nada" else "${recent.size} canciones recientes", onClick = null)
                LibraryLink(DjIcons.Flag, "Drops marcados", if (dropMarks == 0) "Márcalos en la fiesta o desde una canción" else "$dropMarks canciones con tu drop", onClick = null)
            }
        }

        item { SectionHeader("Playlists") }
        val list = playlists?.filter { it.id != LIKED_SONGS_ID }
        when {
            list == null || (list.isEmpty() && refreshing) -> item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) { repeat(2) { Column(Modifier.weight(1f)) { Skeleton(Modifier.fillMaxWidth().size(150.dp), Radius.md) } } }
            }
            list.isEmpty() -> item {
                EmptyState(
                    DjIcons.Playlist,
                    "No hay playlists",
                    "Crea una playlist en Spotify (o copia una de otra persona a una tuya) y desliza hacia abajo para actualizar.",
                    action = "Actualizar",
                    onAction = { vm.refresh() },
                )
            }
            else -> item { PlaylistGrid(list, covers, onOpenPlaylist) }
        }
        item {
            Text(
                "Solo se pueden leer las canciones de playlists tuyas o colaborativas: es una regla de Spotify.",
                style = DjType.meta,
                color = Dj.colors.textTertiary,
                modifier = Modifier.padding(top = Space.xl),
            )
        }
    }
    }
}

@Composable
private fun LibraryLink(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Dj.colors.textPrimary, modifier = Modifier.size(IconSize.md))
        Column(Modifier.weight(1f).padding(horizontal = Space.md)) {
            Text(title, style = DjType.cardTitle, color = Dj.colors.textPrimary)
            Text(subtitle, style = DjType.meta, color = Dj.colors.textSecondary)
        }
        if (onClick != null) Icon(DjIcons.ChevronRight, null, tint = Dj.colors.textTertiary, modifier = Modifier.size(IconSize.sm))
    }
}

/** Covers in a grid: two columns on the phone, up to five on a tablet. */
@Composable
private fun PlaylistGrid(playlists: List<PlaylistWithProgress>, covers: Map<String, List<String>>, onOpen: (String) -> Unit) {
    BoxWithConstraints {
        val columns = (maxWidth / 170.dp).toInt().coerceIn(2, 5)
        Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            playlists.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    row.forEach { playlist ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(Radius.sm)).clickable { onOpen(playlist.id) }) {
                            if (playlist.imageUrl != null) Artwork(playlist.imageUrl, Modifier.fillMaxWidth())
                            else Mosaic(covers[playlist.id].orEmpty(), Modifier.fillMaxWidth())
                            Text(playlist.name, style = DjType.cardTitle, color = Dj.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
                            Text(playlist.status(), style = DjType.meta, color = if (playlist.isReady()) Dj.colors.textSecondary else Dj.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun PlaylistWithProgress.isReady() = cachedTracks > 0 && analyzedTracks >= cachedTracks
