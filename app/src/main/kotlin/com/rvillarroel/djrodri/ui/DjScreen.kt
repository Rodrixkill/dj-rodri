package com.rvillarroel.djrodri.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.domain.mix.Pad
import com.rvillarroel.djrodri.library.db.LocalPlaylistWithCount
import com.rvillarroel.djrodri.localdj.AudioDecoder
import com.rvillarroel.djrodri.localdj.Deck
import com.rvillarroel.djrodri.localdj.DeckState
import com.rvillarroel.djrodri.localdj.LocalSong
import com.rvillarroel.djrodri.localdj.Side
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DjViewModel(app: Application) : AndroidViewModel(app) {
    val mixer = app.container.dj
    val state = mixer.state
    val songs = MutableStateFlow<List<LocalSong>?>(null)
    val playlists = mixer.playlists.playlists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "3 de 40": the analysis of the whole library, only when you ask for it. */
    val analyzing = MutableStateFlow<Pair<Int, Int>?>(null)

    fun refresh() = viewModelScope.launch { songs.value = runCatching { mixer.library.songs() }.getOrDefault(emptyList()) }

    fun bpmOf(song: LocalSong): Double? = mixer.analysis.cachedBpm(song)

    /** Decodes and analyzes every song not analyzed yet, one at a time so memory stays low. */
    fun analyzeAll() {
        if (analyzing.value != null) return
        val pending = songs.value.orEmpty().filter { mixer.analysis.cached(it) == null }
        if (pending.isEmpty()) {
            mixer.message("Todas tus canciones ya están analizadas")
            return
        }
        viewModelScope.launch {
            pending.forEachIndexed { index, song ->
                analyzing.value = index to pending.size
                runCatching { mixer.analysis.analyze(song, AudioDecoder.decode(getApplication(), song.uri)) }
            }
            analyzing.value = null
            songs.value = songs.value?.toList()
            mixer.message("Listo: ${pending.size} canciones analizadas")
        }
    }

    fun createPlaylist(name: String, first: LocalSong?) = viewModelScope.launch {
        val id = mixer.createPlaylist(name)
        first?.let { mixer.addToPlaylist(id, it) }
    }

    fun addToPlaylist(id: Long, song: LocalSong) = viewModelScope.launch {
        mixer.addToPlaylist(id, song)
        mixer.message("Agregada a la playlist")
    }

    fun removeFromPlaylist(id: Long, song: LocalSong) = viewModelScope.launch { mixer.removeFromPlaylist(id, song.id) }

    fun deletePlaylist(id: Long) = viewModelScope.launch { mixer.deletePlaylist(id) }

    suspend fun songsOf(id: Long): List<LocalSong> = mixer.songsOf(id, songs.value.orEmpty())

    fun queuePlaylist(id: Long) = viewModelScope.launch {
        val list = songsOf(id)
        mixer.enqueue(list)
        mixer.message("${list.size} canciones en la cola del automix")
    }
}

private enum class DjSection(val label: String) { DECKS("Decks"), MIXER("Mixer"), PADS("Pads"), SET("Set") }

/**
 * DJ with your own files: two decks you see scrolling, a crossfader always at hand, and the rest (deck controls,
 * the mixer, sound pads, the set) one tap away. Nothing here touches Spotify: the decks play the phone's music.
 */
@Composable
fun DjScreen(vm: DjViewModel = viewModel()) {
    val context = LocalContext.current
    val permission = Manifest.permission.READ_MEDIA_AUDIO
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) { if (granted && vm.songs.value == null) vm.refresh() }

    val mixer by vm.state.collectAsStateWithLifecycle()
    val a by vm.mixer.deckA.state.collectAsStateWithLifecycle()
    val b by vm.mixer.deckB.state.collectAsStateWithLifecycle()
    val posA = rememberPosition(vm.mixer.deckA, a)
    val posB = rememberPosition(vm.mixer.deckB, b)
    var section by rememberSaveable { mutableStateOf(DjSection.DECKS) }
    var library by remember { mutableStateOf<Side?>(null) }
    var browsing by remember { mutableStateOf(false) }

    LaunchedEffect(mixer.message) {
        if (mixer.message != null) {
            kotlinx.coroutines.delay(3_500)
            vm.mixer.message(null)
        }
    }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            ScreenHeader("DJ")
            EmptyState(
                DjIcons.Mixer,
                "Mezcla tu propia música",
                "Dos decks, ecualizador, loops, efectos y pads con las canciones guardadas en tu celular. Necesito permiso para leer tu música.",
                action = "Dar permiso",
                onAction = { ask.launch(permission) },
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = Space.gutter)) {
            ScreenHeader("DJ") {
                DjChip(if (mixer.automix) "Automix ON" else "Automix", mixer.automix, { vm.mixer.setAutomix(!mixer.automix) })
                DjIconButton(DjIcons.Library, "Tu música", { browsing = true })
            }
            DeckStrip(Side.A, a, posA, Dj.colors.accent) { library = Side.A }
            Spacer(Modifier.height(Space.xs))
            DeckStrip(Side.B, b, posB, Dj.colors.textSecondary) { library = Side.B }
            Crossfader(mixer.crossfader, vm.mixer::setCrossfader, Modifier.padding(top = Space.xs))
            (mixer.message ?: mixer.automixStatus)?.let {
                Text(it, style = DjType.meta, color = Dj.colors.accentBright, maxLines = 2, modifier = Modifier.padding(bottom = Space.xxs))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                DjSection.entries.forEach { DjChip(it.label, section == it, { section = it }) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter).padding(bottom = Space.xl)) {
                when (section) {
                    DjSection.DECKS -> BoxWithConstraints {
                        if (maxWidth >= 600.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                DeckControls(Side.A, a, posA, vm, Modifier.weight(1f))
                                DeckControls(Side.B, b, posB, vm, Modifier.weight(1f))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
                                DeckControls(Side.A, a, posA, vm)
                                DeckControls(Side.B, b, posB, vm)
                            }
                        }
                    }
                    DjSection.MIXER -> MixerSection(a, b, vm)
                    DjSection.PADS -> PadsSection(mixer.padVolume, vm)
                    DjSection.SET -> SetSection(mixer.queue, mixer.automix, vm) { browsing = true }
                }
            }
        }
    }

    library?.let { side -> LibrarySheet(vm, target = side, onDismiss = { library = null }) }
    if (browsing) LibrarySheet(vm, target = null, onDismiss = { browsing = false })
}

/** Position at the display's rate while playing; read once when stopped, cue or seek change it. */
@Composable
private fun rememberPosition(deck: Deck, state: DeckState): Long {
    var position by remember(deck) { mutableLongStateOf(0L) }
    LaunchedEffect(deck, state.playing, state.song, state.cueMs, state.loopBeats) {
        position = deck.positionMs()
        while (state.playing) {
            withFrameMillis { position = deck.positionMs() }
        }
    }
    // A seek while paused: poll gently so the waveform follows a tap on the overview.
    LaunchedEffect(deck, state.playing) {
        while (!state.playing) {
            position = deck.positionMs()
            kotlinx.coroutines.delay(120)
        }
    }
    return position
}

/** Always visible: which song, where it is, the scrolling waveform. Tap the title to load another song. */
@Composable
private fun DeckStrip(side: Side, deck: DeckState, positionMs: Long, color: Color, onLoad: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onLoad).padding(vertical = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(Radius.sm)).background(if (deck.playing) Dj.colors.accent else Dj.colors.surfaceSecondary),
                contentAlignment = Alignment.Center,
            ) { Text(side.name, style = DjType.technicalSmall, color = if (deck.playing) Dj.colors.onAccent else Dj.colors.textPrimary) }
            Column(Modifier.weight(1f).padding(horizontal = Space.xs)) {
                Text(
                    when {
                        deck.song == null -> "Toca para cargar una canción"
                        else -> deck.song.title
                    },
                    style = DjType.cardTitle,
                    color = if (deck.song == null) Dj.colors.textTertiary else Dj.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = when {
                    deck.loading -> "Analizando…"
                    deck.error != null -> deck.error
                    deck.song != null -> "${deck.song.artist} · ${formatTime(positionMs)} / ${formatTime(deck.durationMs)}"
                    else -> null
                }
                detail?.let { Text(it, style = DjType.meta, color = if (deck.error != null) Dj.colors.danger else Dj.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(deck.bpm?.let { "%.1f".format(it) } ?: "—", style = DjType.technical, color = if (deck.synced) Dj.colors.accentBright else Dj.colors.textPrimary)
                Text(if (deck.tempo != 1.0) "BPM ${Deck.tempoPercent(deck.tempo)}" else "BPM", style = DjType.technicalSmall, color = Dj.colors.textTertiary)
            }
        }
        val loop = deck.loopBeats?.let { beats ->
            deck.grid?.let { g -> val start = g.nearestBeat(positionMs - (g.beatMs * beats / 2).toLong()); start to (start + (g.beatMs * beats).toLong()) }
        }
        if (deck.loading) Skeleton(Modifier.fillMaxWidth().height(64.dp))
        else ZoomWaveform(deck.waveform, deck.durationMs, positionMs, deck.grid, color, height = 64.dp, cueMs = deck.cueMs.takeIf { deck.song != null }, loop = loop)
    }
}

@Composable
private fun DeckControls(side: Side, deck: DeckState, positionMs: Long, vm: DjViewModel, modifier: Modifier = Modifier) {
    val d = vm.mixer.deck(side)
    val enabled = deck.loaded
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        SectionHeader("Deck ${side.name}")
        OverviewWaveform(deck.waveform, deck.durationMs, positionMs, if (side == Side.A) Dj.colors.accent else Dj.colors.textSecondary, d::seekTo)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SecondaryButton("CUE", d::cue, Modifier.weight(1f), enabled = enabled)
            PlayButton(deck.playing, { if (enabled) vm.mixer.togglePlay(side) }, size = 56.dp)
            SecondaryButton(if (deck.synced) "SYNC ✓" else "SYNC", { vm.mixer.sync(side) }, Modifier.weight(1f), icon = DjIcons.Sync, enabled = enabled)
        }
        // "Otra vez": back 5 or 15 seconds on the beat, and beat jumps.
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            ActionTile("-15 s", DjIcons.JumpBack, { d.replay(15) }, Modifier.weight(1f), enabled = enabled)
            ActionTile("-5 s", DjIcons.JumpBack, { d.replay(5) }, Modifier.weight(1f), enabled = enabled)
            ActionTile("-4 beats", DjIcons.JumpBack, { d.beatJump(-4) }, Modifier.weight(1f), enabled = enabled)
            ActionTile("+4 beats", DjIcons.JumpForward, { d.beatJump(4) }, Modifier.weight(1f), enabled = enabled)
            ActionTile("+16", DjIcons.JumpForward, { d.beatJump(16) }, Modifier.weight(1f), enabled = enabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            var loopLength by rememberSaveable(side) { mutableStateOf(4) }
            LoopSelector(
                beats = deck.loopBeats ?: loopLength,
                active = deck.loopBeats != null,
                onBeats = { loopLength = it; if (deck.loopBeats != null) d.setLoop(it) },
                onToggle = { if (enabled) d.setLoop(if (deck.loopBeats == null) loopLength else null) },
                modifier = Modifier.weight(1.4f),
            )
            ActionTile("Eco", DjIcons.Echo, { d.setEcho(!deck.echo) }, Modifier.weight(1f), active = deck.echo, enabled = enabled)
            ActionTile("Freno", DjIcons.Stop, d::tapeStop, Modifier.weight(1f), active = deck.effect == "Parada de cinta", enabled = deck.playing)
            ActionTile("Backspin", DjIcons.Refresh, d::backspin, Modifier.weight(1f), active = deck.effect == "Backspin", enabled = deck.playing)
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tempo", style = DjType.label, color = Dj.colors.textSecondary, modifier = Modifier.weight(1f))
                Text(Deck.tempoPercent(deck.tempo), style = DjType.technicalSmall, color = Dj.colors.textPrimary)
                TertiaryButton("Reset", { d.setTempo(1.0) })
            }
            Slider(
                value = deck.tempo.toFloat(),
                onValueChange = { d.setTempo(it.toDouble()) },
                valueRange = Deck.MIN_TEMPO.toFloat()..Deck.MAX_TEMPO.toFloat(),
                enabled = enabled,
                colors = sliderColors(),
            )
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Dj.colors.textPrimary,
    activeTrackColor = Dj.colors.accent,
    inactiveTrackColor = Dj.colors.progressInactive,
)

/** A channel strip per deck: three EQ bands (double tap resets), the filter and the fader. */
@Composable
private fun MixerSection(a: DeckState, b: DeckState, vm: DjViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        listOf(Side.A to a, Side.B to b).forEach { (side, state) ->
            val d = vm.mixer.deck(side)
            Panel {
                Text("Canal ${side.name}" + (state.song?.let { " · ${it.title}" } ?: ""), style = DjType.cardTitle, color = Dj.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    VerticalFader("AGUDOS", state.eqHigh, com.rvillarroel.djrodri.domain.mix.ThreeBandEq.KILL_DB..com.rvillarroel.djrodri.domain.mix.ThreeBandEq.MAX_DB, { d.setEq(high = it) })
                    VerticalFader("MEDIOS", state.eqMid, com.rvillarroel.djrodri.domain.mix.ThreeBandEq.KILL_DB..com.rvillarroel.djrodri.domain.mix.ThreeBandEq.MAX_DB, { d.setEq(mid = it) })
                    VerticalFader("GRAVES", state.eqLow, com.rvillarroel.djrodri.domain.mix.ThreeBandEq.KILL_DB..com.rvillarroel.djrodri.domain.mix.ThreeBandEq.MAX_DB, { d.setEq(low = it) })
                    VerticalFader("FILTRO", state.filter, -1.0..1.0, d::setFilter, display = {
                        when {
                            it < -0.02 -> "LP"
                            it > 0.02 -> "HP"
                            else -> "—"
                        }
                    })
                    VerticalFader("VOL", state.volume, 0.0..1.0, d::setVolume, neutral = 1.0, display = { "${(it * 100).toInt()}" })
                }
                Text("Doble toque en un fader lo devuelve a su punto neutro. Graves abajo del todo: el truco para cambiar de bajo entre dos canciones.", style = DjType.meta, color = Dj.colors.textTertiary)
            }
        }
    }
}

@Composable
private fun PadsSection(volume: Double, vm: DjViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        SectionHeader("Sonidos")
        Pad.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                row.forEach { pad -> PadButton(pad.label, { vm.mixer.pad(pad) }, Modifier.weight(1f)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volumen de los pads", style = DjType.label, color = Dj.colors.textSecondary, modifier = Modifier.weight(1f))
            Text("${(volume * 100).toInt()}", style = DjType.technicalSmall, color = Dj.colors.textPrimary)
        }
        Slider(value = volume.toFloat(), onValueChange = { vm.mixer.setPadVolume(it.toDouble()) }, colors = sliderColors())
    }
}

/** What the automix plays next, and your saved sets. */
@Composable
private fun SetSection(queue: List<LocalSong>, automix: Boolean, vm: DjViewModel, onBrowse: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val analyzing by vm.analyzing.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        SectionHeader("Cola del automix", action = if (queue.isNotEmpty()) "Vaciar" else null, onAction = vm.mixer::clearQueue)
        Text(
            if (automix) "El automix carga cada canción en el deck libre, la sincroniza y la mezcla en un cambio de frase."
            else "Activa Automix arriba y la cola se mezcla sola. Tú puedes seguir usando el mixer y los pads.",
            style = DjType.meta,
            color = Dj.colors.textSecondary,
        )
        if (queue.isEmpty()) {
            SecondaryButton("Agregar canciones", onBrowse, icon = DjIcons.Plus)
        } else {
            queue.forEachIndexed { index, song ->
                SongLine(song, vm.bpmOf(song), detail = "${index + 1}") {
                    DjIconButton(DjIcons.Close, "Quitar de la cola", { vm.mixer.removeFromQueue(index) }, size = 18.dp)
                }
            }
        }

        SectionHeader("Tus sets", Modifier.padding(top = Space.md))
        if (playlists.isEmpty()) {
            Text("Guarda canciones en un set desde tu música (el botón + de cada canción).", style = DjType.meta, color = Dj.colors.textSecondary)
        }
        playlists.forEach { playlist -> SetLine(playlist, vm) }

        SectionHeader("Análisis", Modifier.padding(top = Space.md))
        Text(
            "Cada canción se analiza (BPM, pulso y forma de onda) la primera vez que la cargas. Puedes adelantarlo para todas de una vez.",
            style = DjType.meta,
            color = Dj.colors.textSecondary,
        )
        analyzing?.let { (done, total) ->
            ProgressLine(done.toFloat() / total)
            Text("Analizando ${done + 1} de $total…", style = DjType.meta, color = Dj.colors.textSecondary)
        } ?: SecondaryButton("Analizar todo", vm::analyzeAll, icon = DjIcons.Refresh)
    }
}

@Composable
private fun SetLine(playlist: LocalPlaylistWithCount, vm: DjViewModel) {
    var open by remember { mutableStateOf(false) }
    var songs by remember { mutableStateOf<List<LocalSong>>(emptyList()) }
    LaunchedEffect(open, playlist.songs) { if (open) songs = vm.songsOf(playlist.id) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radius.sm)).background(Dj.colors.surfaceSecondary), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(DjIcons.Playlist, null, tint = Dj.colors.accent)
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.sm)) {
                Text(playlist.name, style = DjType.cardTitle, color = Dj.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (playlist.songs == 1) "1 canción" else "${playlist.songs} canciones", style = DjType.meta, color = Dj.colors.textSecondary)
            }
            TertiaryButton("A la cola", { vm.queuePlaylist(playlist.id) }, color = Dj.colors.accentBright)
        }
        if (open) {
            songs.forEach { song ->
                SongLine(song, vm.bpmOf(song), Modifier.padding(start = Space.md)) {
                    DjIconButton(DjIcons.Close, "Quitar del set", { vm.removeFromPlaylist(playlist.id, song); songs = songs - song }, size = 18.dp)
                }
            }
            TertiaryButton("Borrar set", { vm.deletePlaylist(playlist.id) }, color = Dj.colors.danger)
        }
    }
}

@Composable
private fun SongLine(song: LocalSong, bpm: Double?, modifier: Modifier = Modifier, detail: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (detail != null) Text(detail, style = DjType.technicalSmall, color = Dj.colors.textTertiary, modifier = Modifier.width(24.dp))
        Artwork(song.artUri?.toString(), Modifier.size(44.dp), radius = Radius.sm)
        Column(Modifier.weight(1f).padding(horizontal = Space.sm)) {
            Text(song.title, style = DjType.cardTitle, color = Dj.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(song.artist, formatTime(song.durationMs), bpm?.let { "%.0f BPM".format(it) }).joinToString(" · "),
                style = DjType.meta,
                color = Dj.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/**
 * Your music. With a [target] deck, tapping a song loads it there; without, each song offers A, B, the queue and
 * your sets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySheet(vm: DjViewModel, target: Side?, onDismiss: () -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<LocalSong?>(null) }
    val shown = remember(songs, query) {
        val q = query.trim().lowercase()
        songs.orEmpty().filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Dj.colors.surface) {
        Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.xl)) {
            Text(target?.let { "Cargar en el deck ${it.name}" } ?: "Tu música", style = DjType.section, color = Dj.colors.textPrimary)
            Text(
                songs?.let { "${it.size} canciones guardadas en el celular" } ?: "Buscando tu música…",
                style = DjType.meta,
                color = Dj.colors.textSecondary,
                modifier = Modifier.padding(top = Space.xxs, bottom = Space.sm),
            )
            SearchField(query, { query = it }, placeholder = "Buscar en tu música…")
            if (songs?.isEmpty() == true) {
                EmptyState(DjIcons.Music, "No hay música en el celular", "Copia archivos MP3, M4A o FLAC de más de un minuto a la carpeta Música.", action = "Buscar de nuevo", onAction = { vm.refresh() })
            }
            LazyColumn(Modifier.fillMaxWidth().height(480.dp).padding(top = Space.xs)) {
                items(shown, key = { it.id }) { song ->
                    SongLine(
                        song,
                        vm.bpmOf(song),
                        onClick = target?.let { side -> { vm.mixer.load(side, song); onDismiss() } },
                    ) {
                        if (target == null) {
                            TertiaryButton("A", { vm.mixer.load(Side.A, song) }, color = Dj.colors.accentBright)
                            TertiaryButton("B", { vm.mixer.load(Side.B, song) }, color = Dj.colors.accentBright)
                        }
                        DjIconButton(DjIcons.Plus, "Agregar", { adding = song }, size = 18.dp)
                    }
                }
            }
        }
    }
    adding?.let { song -> AddSheet(song, playlists, vm, onDismiss = { adding = null }) }
}

/** Where a song goes: the automix queue, a saved set, or a new set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(song: LocalSong, playlists: List<LocalPlaylistWithCount>, vm: DjViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Dj.colors.surface) {
        Column(Modifier.padding(horizontal = Space.gutter).padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(song.title, style = DjType.section, color = Dj.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PrimaryButton("A la cola del automix", {
                vm.mixer.enqueue(listOf(song))
                vm.mixer.message("En la cola: ${song.title}")
                onDismiss()
            }, Modifier.fillMaxWidth(), icon = DjIcons.Plus)
            if (playlists.isNotEmpty()) Text("A un set", style = DjType.label, color = Dj.colors.textSecondary, modifier = Modifier.padding(top = Space.sm))
            playlists.forEach { p ->
                Text(
                    p.name,
                    style = DjType.body,
                    color = Dj.colors.textPrimary,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm)).clickable {
                        vm.addToPlaylist(p.id, song)
                        onDismiss()
                    }.padding(vertical = Space.sm),
                )
            }
            Text("Set nuevo", style = DjType.label, color = Dj.colors.textSecondary, modifier = Modifier.padding(top = Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Box(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(Radius.md)).background(Dj.colors.surfaceSecondary).padding(horizontal = Space.md),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("Nombre del set", style = DjType.body, color = Dj.colors.textTertiary)
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = DjType.body.copy(color = Dj.colors.textPrimary), cursorBrush = SolidColor(Dj.colors.accent), modifier = Modifier.fillMaxWidth())
                }
                SecondaryButton("Crear", {
                    vm.createPlaylist(name, song)
                    vm.mixer.message("Set creado")
                    onDismiss()
                })
            }
        }
    }
}
