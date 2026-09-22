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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
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

    /** The copy of your likes: null when idle, (done, total) while copying; total 0 while the likes are read. */
    val copying = MutableStateFlow<Pair<Int, Int>?>(null)
    val copyResult = MutableStateFlow<String?>(null)
    val hasCopy get() = container.settings.likedCopyId != null
    fun canCreatePlaylists() = container.auth.canCreatePlaylists()

    /** One of your playlists already called [name] (any capitals): it is filled instead of making another. */
    fun playlistNamed(name: String): PlaylistWithProgress? =
        playlists.value?.firstOrNull { it.id != LIKED_SONGS_ID && it.name.trim().equals(name.trim(), ignoreCase = true) }

    fun copyLiked(name: String) {
        if (copying.value != null) return
        copyResult.value = null
        val target = if (hasCopy) null else playlistNamed(name)?.id
        viewModelScope.launch {
            val failure = container.copyLikedToPlaylist(name.trim().ifBlank { DEFAULT_COPY_NAME }, target) { done, total -> copying.value = done to total }
            copying.value = null
            copyResult.value = failure ?: "Listo: «${name.trim().ifBlank { DEFAULT_COPY_NAME }}» está en tu Spotify. Ábrela allí y activa el Mix."
        }
    }

    companion object {
        const val DEFAULT_COPY_NAME = "Me Gusta Mix"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** Your collection: the shortcuts first, then every playlist as a cover you can recognize at a glance. */
@Composable
fun LibraryScreen(onOpenPlaylist: (String) -> Unit, onSettings: () -> Unit, onLogin: () -> Unit, vm: LibraryViewModel = viewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val dropMarks by vm.dropMarks.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val pulled by vm.pulled.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    var copySheet by remember { mutableStateOf(false) }

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
                LibraryLink(
                    DjIcons.Playlist,
                    if (vm.hasCopy) "Actualizar tu playlist de me gusta" else "Tus me gusta como playlist",
                    "Para usar el Mix de Spotify, que no funciona en «Tus me gusta»",
                ) { copySheet = true }
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

    if (copySheet) LikedCopySheet(vm, onLogin, onDismiss = { copySheet = false })
}

/** Creates (or updates) a private playlist with all your likes, after telling you exactly what will happen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LikedCopySheet(vm: LibraryViewModel, onLogin: () -> Unit, onDismiss: () -> Unit) {
    val copying by vm.copying.collectAsStateWithLifecycle()
    val result by vm.copyResult.collectAsStateWithLifecycle()
    // If you already made the playlist in Spotify, its name is offered, so it gets filled.
    var name by remember { mutableStateOf(vm.playlistNamed(LibraryViewModel.DEFAULT_COPY_NAME)?.name ?: LibraryViewModel.DEFAULT_COPY_NAME) }
    val allowed = vm.canCreatePlaylists()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Dj.colors.surface) {
        Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(if (vm.hasCopy) "Actualizar tu playlist de me gusta" else "Tus me gusta como playlist", style = DjType.section, color = Dj.colors.textPrimary)
            Text(
                if (vm.hasCopy) {
                    "La playlist que ya creaste se reemplaza con tus me gusta de hoy, en el mismo orden. No se crea otra."
                } else {
                    "Se crea en tu Spotify una playlist privada con todas tus canciones que te gustan, de la más nueva a la más antigua. Después, en Spotify, ábrela y activa el Mix."
                },
                style = DjType.meta,
                color = Dj.colors.textSecondary,
            )
            val progress = copying
            when {
                progress != null -> {
                    ProgressLine(if (progress.second > 0) progress.first.toFloat() / progress.second else 0f)
                    Text(
                        if (progress.second == 0) "Leyendo tus me gusta…" else "Copiando ${progress.first} de ${progress.second} canciones…",
                        style = DjType.meta,
                        color = Dj.colors.textSecondary,
                    )
                }
                !allowed -> {
                    Text(
                        "Primero Spotify te va a pedir permiso para que DJ Rodri pueda crear playlists privadas. Solo se usa para esto.",
                        style = DjType.meta,
                        color = Dj.colors.textSecondary,
                    )
                    PrimaryButton("Dar permiso en Spotify", onLogin, Modifier.fillMaxWidth())
                }
                else -> {
                    val match = if (vm.hasCopy) null else vm.playlistNamed(name)
                    if (!vm.hasCopy) {
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(Radius.md)).background(Dj.colors.surfaceSecondary).padding(horizontal = Space.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.foundation.text.BasicTextField(
                                name,
                                { name = it },
                                singleLine = true,
                                textStyle = DjType.body.copy(color = Dj.colors.textPrimary),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Dj.colors.accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    match?.let {
                        Text(
                            when (val count = it.trackCount ?: 0) {
                                0 -> "Ya tienes «${it.name}», vacía: se llena esa, sin crear otra."
                                else -> "Ya tienes «${it.name}» con $count canciones: se reemplazan por tus me gusta."
                            },
                            style = DjType.meta,
                            color = if ((it.trackCount ?: 0) > 0) Dj.colors.accentBright else Dj.colors.textSecondary,
                        )
                    }
                    PrimaryButton(
                        when {
                            vm.hasCopy -> "Actualizar"
                            match != null -> "Llenar «${match.name}»"
                            else -> "Crear playlist"
                        },
                        { vm.copyLiked(name) },
                        Modifier.fillMaxWidth(),
                        icon = DjIcons.Plus,
                    )
                }
            }
            result?.let { InlineMessage(it, isError = !it.startsWith("Listo")) }
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
