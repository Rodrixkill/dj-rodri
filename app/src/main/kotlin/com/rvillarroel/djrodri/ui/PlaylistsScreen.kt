package com.rvillarroel.djrodri.ui

import android.app.Activity
import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.party.PartySnapshot
import com.rvillarroel.djrodri.library.db.PlaylistWithProgress
import com.rvillarroel.djrodri.ui.theme.DjColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val library = container.library
    val playlists = library.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val refreshing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val party = container.party.state

    /** A party HyperOS killed, ready to carry on. */
    val resumable = MutableStateFlow<PartySnapshot?>(null)
    val resuming = MutableStateFlow(false)

    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        error.value = runCatching { library.refreshPlaylists() }.exceptionOrNull()?.let { it.message ?: "No se pudieron leer tus playlists" }
        refreshing.value = false
    }

    fun checkResumable() = viewModelScope.launch { resumable.value = container.restorableParty() }

    fun resume(activity: Activity, onResumed: () -> Unit) = viewModelScope.launch {
        resuming.value = true
        val failure = container.resumeParty(activity)
        resuming.value = false
        error.value = failure
        if (failure == null) onResumed() else resumable.value = container.restorableParty()
    }

    fun forget() = viewModelScope.launch {
        container.forgetParty()
        resumable.value = null
    }
}

@Composable
fun PlaylistsScreen(onOpen: (String) -> Unit, onParty: () -> Unit, onSettings: () -> Unit, vm: PlaylistsViewModel = viewModel()) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val party by vm.party.collectAsStateWithLifecycle()
    val resumable by vm.resumable.collectAsStateWithLifecycle()
    val resuming by vm.resuming.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    // First visit: the local copy is empty, fetch it once. A killed party is looked for every time you come back.
    LaunchedEffect(Unit) {
        if (vm.playlists.value.isEmpty()) vm.refresh()
        vm.checkResumable()
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ScreenHeader("Tus playlists", action = { TextButton(onClick = onSettings) { Text("Ajustes", color = DjColors.NeonSoft) } })
        }
        if (party.active) {
            item {
                Panel(Modifier.clickable(onClick = onParty)) {
                    Text("🎧 Fiesta en curso", style = MaterialTheme.typography.titleMedium, color = DjColors.Drop)
                    Text(party.current?.let { "${it.name} — ${it.artist}" } ?: "", color = DjColors.TextDim)
                }
            }
        } else {
            resumable?.let { snapshot ->
                item {
                    Panel {
                        Text("Fiesta interrumpida", style = MaterialTheme.typography.titleMedium, color = DjColors.Warning)
                        Text(
                            "Quedó a medias hace ${minutesAgo(snapshot.savedAtMs)} min, con ${snapshot.upcomingIds.size} canciones por delante.",
                            color = DjColors.TextDim,
                        )
                        BigButton(
                            if (resuming) "Retomando…" else "▶ Retomar donde quedó",
                            { activity?.let { a -> vm.resume(a, onParty) } },
                            Modifier.fillMaxWidth(),
                            color = DjColors.Drop,
                            enabled = !resuming,
                        )
                        TextButton(onClick = vm::forget) { Text("Descartarla", color = DjColors.TextDim) }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Elige una playlist para analizarla y ponerla en la fiesta. Solo se leen las canciones de playlists tuyas o colaborativas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DjColors.TextDim,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.refresh() }, enabled = !refreshing) { Text(if (refreshing) "…" else "Actualizar") }
            }
            error?.let { Text(it, color = DjColors.Warning) }
        }
        items(playlists, key = { it.id }) { playlist -> PlaylistRow(playlist) { onOpen(playlist.id) } }
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistWithProgress, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick)) {
        Text(playlist.name, style = MaterialTheme.typography.titleMedium)
        val total = playlist.cachedTracks.takeIf { it > 0 } ?: playlist.trackCount
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                playlist.cachedTracks == 0 -> Text("${total ?: "?"} canciones · sin analizar", color = DjColors.TextDim)
                playlist.analyzedTracks >= playlist.cachedTracks -> Text("${playlist.cachedTracks} canciones · analizada ✓", color = DjColors.Whole)
                else -> {
                    Text("${playlist.analyzedTracks} de ${playlist.cachedTracks} analizadas", color = DjColors.TextDim)
                    LinearProgressIndicator(
                        progress = { playlist.analyzedTracks.toFloat() / playlist.cachedTracks },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun minutesAgo(atMs: Long): Long = ((System.currentTimeMillis() - atMs) / 60_000).coerceAtLeast(1)
