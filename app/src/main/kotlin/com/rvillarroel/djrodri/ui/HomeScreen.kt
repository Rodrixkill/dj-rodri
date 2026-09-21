package com.rvillarroel.djrodri.ui

import android.app.Activity
import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.StyleMix
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.party.PartySnapshot
import com.rvillarroel.djrodri.library.db.PlaylistWithProgress
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val profile = container.settings.profile
    val party = container.party.state
    val playlists = container.library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val recent = container.library.recentlyPlayed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mixes = MutableStateFlow<List<StyleMix>?>(null)
    val resumable = MutableStateFlow<PartySnapshot?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    /** Covers of each playlist without a picture of its own, for its mosaic. */
    val covers = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Cover of the song an interrupted party stopped on. */
    val resumableCover = MutableStateFlow<String?>(null)

    fun load() = viewModelScope.launch {
        resumable.value = container.restorableParty()
        resumableCover.value = resumable.value?.let { container.library.trackByUri("spotify:track:${it.currentTrackId}")?.imageUrl }
        container.refreshProfile()
        if (container.library.allTracks().isEmpty()) runCatching { container.library.refreshPlaylists() }
        mixes.value = runCatching { container.styleMixes() }.getOrDefault(emptyList())
        // Songs saved before covers existed get theirs now; the mixes are rebuilt to show them.
        if (container.library.backfillCovers()) {
            mixes.value = runCatching { container.styleMixes() }.getOrDefault(mixes.value.orEmpty())
            playlists.value?.let { list -> loadCovers(list.filter { it.imageUrl == null }.map { it.id }) }
            resumableCover.value = resumable.value?.let { container.library.trackByUri("spotify:track:${it.currentTrackId}")?.imageUrl }
        }
    }

    fun loadCovers(ids: List<String>) = viewModelScope.launch {
        covers.value = ids.associateWith { container.library.coversOf(it) }
    }

    /** The playlist the featured card plays: the one you used last, or the most analyzed one. */
    fun featured(list: List<PlaylistWithProgress>): PlaylistWithProgress? =
        list.firstOrNull { it.id == container.settings.lastPlaylistId } ?: list.maxByOrNull { it.analyzedTracks }?.takeIf { it.analyzedTracks > 0 }

    fun play(activity: Activity, playlistId: String, onStarted: () -> Unit) = attempt { container.startParty(activity, playlistId) }.then(onStarted)

    fun playMix(activity: Activity, mix: StyleMix, onStarted: () -> Unit) = attempt { container.startMix(activity, mix) }.then(onStarted)

    fun resume(activity: Activity, onStarted: () -> Unit) = attempt { container.resumeParty(activity) }.then(onStarted)

    fun forget() = viewModelScope.launch {
        container.forgetParty()
        resumable.value = null
    }

    private fun attempt(action: suspend () -> String?) = Pending(action)

    inner class Pending(private val action: suspend () -> String?) {
        fun then(onDone: () -> Unit) = viewModelScope.launch {
            busy.value = true
            val failure = action()
            busy.value = false
            message.value = failure
            if (failure == null) onDone()
        }
    }
}

private enum class HomeFilter(val label: String) { FOR_YOU("Para ti"), PARTIES("Fiestas"), PLAYLISTS("Playlists"), STYLES("Estilos") }

/**
 * Home: music first. A greeting, the way into search, a featured party, what you heard lately, mixes made from your
 * own library and your playlists. No statistics, no dashboard.
 */
@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onParty: () -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val party by vm.party.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val mixes by vm.mixes.collectAsStateWithLifecycle()
    val resumable by vm.resumable.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()
    val resumableCover by vm.resumableCover.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var filter by remember { mutableStateOf(HomeFilter.FOR_YOU) }
    var sheet by remember { mutableStateOf<TrackRow?>(null) }
    val actions = rememberTrackRows()

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(playlists) { playlists?.let { list -> vm.loadCovers(list.filter { it.imageUrl == null }.map { it.id }) } }

    fun show(f: HomeFilter) = filter == HomeFilter.FOR_YOU || filter == f

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.md)) {
        item { Greeting(profile.name, profile.imageUrl, onSettings) }
        item { SearchField("", {}, Modifier.padding(top = Space.lg), onClick = onSearch) }
        item {
            Row(Modifier.padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                HomeFilter.entries.forEach { f -> DjChip(f.label, filter == f, { filter = f }) }
            }
        }
        message?.let { item { InlineMessage(it, Modifier.padding(top = Space.md)) } }

        if (show(HomeFilter.PARTIES)) {
            item {
                val list = playlists.orEmpty()
                val featured = vm.featured(list)
                Box(Modifier.padding(top = Space.lg)) {
                    when {
                        party.active -> Featured(
                            eyebrow = "SUENA AHORA",
                            title = party.current?.name ?: "Fiesta en curso",
                            subtitle = party.current?.artist.orEmpty(),
                            cover = party.current?.imageUrl,
                            action = "Abrir",
                            onAction = onParty,
                        )
                        resumable != null -> Featured(
                            eyebrow = "FIESTA INTERRUMPIDA",
                            title = "Sigue donde quedó",
                            subtitle = "Hace ${minutesAgo(resumable!!.savedAtMs)} min · ${resumable!!.upcomingIds.size} canciones por delante",
                            cover = resumableCover,
                            action = if (busy) "Retomando…" else "Retomar",
                            onAction = { activity?.let { vm.resume(it, onParty) } },
                            secondary = "Descartar" to { vm.forget() },
                        )
                        featured != null -> Featured(
                            eyebrow = "PARA LA FIESTA",
                            title = "Mantén la energía arriba",
                            subtitle = "${featured.name} · el DJ elige el orden y los cambios",
                            cover = featured.imageUrl ?: covers[featured.id]?.firstOrNull(),
                            action = if (busy) "Preparando…" else "A la fiesta",
                            onAction = { activity?.let { vm.play(it, featured.id, onParty) } },
                        )
                        else -> Featured(
                            eyebrow = "EMPIEZA AQUÍ",
                            title = "Analiza una playlist",
                            subtitle = "El DJ aprende dónde están los coros y los drops para cambiar en el momento justo",
                            cover = null,
                            action = "Ver playlists",
                            onAction = onLibrary,
                        )
                    }
                }
            }
        }

        if (show(HomeFilter.PARTIES) && recent.isNotEmpty()) {
            item { SectionHeader("Escuchado hace poco") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    items(recent, key = { it.id }) { track ->
                        MusicCard(track.name, track.artist, width = 128.dp, onClick = { actions.rowFor(track) { sheet = it } }) {
                            Artwork(track.imageUrl, it)
                        }
                    }
                }
            }
        }

        if (show(HomeFilter.STYLES)) {
            val list = mixes
            if (list == null) {
                item { SectionHeader("Hecho para ti") }
                item { Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) { repeat(3) { Skeleton(Modifier.size(140.dp), Radius.md) } } }
            } else if (list.isNotEmpty()) {
                item { SectionHeader("Hecho para ti") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        items(list, key = { it.family.name }) { mix ->
                            MusicCard("Mezcla ${mix.family.label}", "${mix.tracks.size} canciones tuyas", onClick = { activity?.let { vm.playMix(it, mix, onParty) } }) {
                                Mosaic(mix.covers, it)
                            }
                        }
                    }
                }
            }
        }

        if (show(HomeFilter.PLAYLISTS)) {
            item { SectionHeader("Tus playlists", action = "Ver todas", onAction = onLibrary) }
            val list = playlists
            when {
                list == null -> items(4) { TrackRowSkeleton() }
                list.isEmpty() -> item { EmptyState(DjIcons.Playlist, "Sin playlists todavía", "Crea una en Spotify o toca Actualizar en la Biblioteca.", action = "Ir a la biblioteca", onAction = onLibrary) }
                else -> items(list.take(if (filter == HomeFilter.PLAYLISTS) list.size else 6), key = { it.id }) { playlist ->
                    PlaylistLine(playlist, covers[playlist.id].orEmpty()) { onOpenPlaylist(playlist.id) }
                }
            }
        }
    }

    sheet?.let { TrackSheet(it, onDismiss = { sheet = null }) }
}

@Composable
private fun Greeting(name: String?, avatar: String?, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(greeting(), style = DjType.pageTitle, color = Dj.colors.textPrimary)
            Text(name?.substringBefore(' ') ?: "DJ", style = DjType.display, color = Dj.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(Dj.colors.surfaceSecondary).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
            if (avatar != null) Artwork(avatar, Modifier.size(44.dp), radius = 22.dp)
            else Text(name?.take(1)?.uppercase() ?: "R", style = DjType.cardTitle, color = Dj.colors.textPrimary)
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Buenos días,"
    in 12..18 -> "Buenas tardes,"
    else -> "Buenas noches,"
}

/**
 * The featured card: a cover as the backdrop, darkened toward the text, one clear action. Artwork brings the
 * color; the app only adds its red to the button.
 */
@Composable
private fun Featured(
    eyebrow: String,
    title: String,
    subtitle: String,
    cover: String?,
    action: String,
    onAction: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Box(Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(Radius.lg)).background(Dj.colors.surfaceSecondary)) {
        CoverFill(cover)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.86f), 0.75f to Color.Black.copy(alpha = 0.25f))))
        Column(Modifier.fillMaxSize().padding(Space.lg), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(eyebrow, style = DjType.technicalSmall, color = Color.White.copy(alpha = 0.7f))
                Text(title, style = DjType.pageTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
                Text(subtitle, style = DjType.meta, color = Color.White.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PrimaryButton(action, onAction, icon = DjIcons.Play)
                secondary?.let { (label, onClick) -> TertiaryButton(label, onClick, color = Color.White.copy(alpha = 0.8f)) }
            }
        }
    }
}

/** A playlist in a list: its picture (or a mosaic of its covers), its name and where its analysis is. */
@Composable
fun PlaylistLine(playlist: PlaylistWithProgress, covers: List<String>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).clickable(onClick = onClick).padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playlist.imageUrl != null) Artwork(playlist.imageUrl, Modifier.size(56.dp), radius = Radius.sm)
        else Mosaic(covers, Modifier.size(56.dp), radius = Radius.sm)
        Column(Modifier.weight(1f).padding(horizontal = Space.sm)) {
            Text(playlist.name, style = DjType.cardTitle, color = Dj.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playlist.status(), style = DjType.meta, color = Dj.colors.textSecondary, maxLines = 1)
        }
        androidx.compose.material3.Icon(DjIcons.ChevronRight, null, tint = Dj.colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

fun PlaylistWithProgress.status(): String {
    val total = cachedTracks.takeIf { it > 0 } ?: trackCount
    return when {
        cachedTracks == 0 -> "${total ?: "?"} canciones · sin analizar"
        analyzedTracks >= cachedTracks -> "$cachedTracks canciones · lista para la fiesta"
        analyzedTracks == 0 -> "$cachedTracks canciones · sin analizar"
        else -> "$analyzedTracks de $cachedTracks analizadas"
    }
}

fun minutesAgo(atMs: Long): Long = ((System.currentTimeMillis() - atMs) / 60_000).coerceAtLeast(1)
