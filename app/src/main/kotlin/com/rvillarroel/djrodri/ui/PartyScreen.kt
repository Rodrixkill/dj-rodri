package com.rvillarroel.djrodri.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.ai.IntentSource
import com.rvillarroel.djrodri.data.ai.InterpretedIntent
import com.rvillarroel.djrodri.data.party.PartyState
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.Entry
import com.rvillarroel.djrodri.domain.dj.Exit
import com.rvillarroel.djrodri.party.PartyService
import com.rvillarroel.djrodri.party.label
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjIcons
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.IconSize
import com.rvillarroel.djrodri.ui.theme.Motion
import com.rvillarroel.djrodri.ui.theme.Radius
import com.rvillarroel.djrodri.ui.theme.Space
import com.rvillarroel.djrodri.voice.VoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PartyViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val engine = container.party
    val state = engine.state
    val thinking = MutableStateFlow(false)
    val lastRequest = MutableStateFlow<InterpretedIntent?>(null)

    /** What the DJ understood by voice, waiting for your tap. Nothing reaches Spotify until you confirm. */
    val pending = MutableStateFlow<InterpretedIntent?>(null)

    val voice = container.voice
    val voiceState = voice.state

    /** The most common genres in what is playing tonight, as one-tap chips. */
    val playlistGenres: List<String>
        get() = engine.profiles.flatMap { it.genres }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(6)
            .map { it.key }

    /** Reads your request (local model or rules) and hands the resulting numbers to the DJ. */
    fun request(text: String) = interpret(text) { engine.applyIntent(it.intent) }

    /** By voice the plan is only prepared: applying it is a tap of yours, never the microphone's doing. */
    fun voiceRequest(text: String) = interpret(text) { pending.value = it }

    fun applyPending() {
        pending.value?.let { engine.applyIntent(it.intent) }
        pending.value = null
        voice.reset()
    }

    fun dismissPending() {
        pending.value = null
        voice.reset()
    }

    fun listen() {
        pending.value = null
        voice.start()
    }

    private fun interpret(text: String, onReady: (InterpretedIntent) -> Unit) {
        if (text.isBlank()) return
        thinking.value = true
        viewModelScope.launch {
            val interpreted = container.interpret(text)
            lastRequest.value = interpreted
            onReady(interpreted)
            thinking.value = false
        }
    }

    override fun onCleared() = voice.reset()
}

/**
 * Now playing, the party's home. On the phone one column: cover, song, controls, the DJ's plan and your moves, then
 * what comes next and your requests. With room, the second half moves to its own column.
 */
@Composable
fun PartyScreen(onLibrary: () -> Unit, vm: PartyViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    // The screen stays on while the party is in front of you: nobody wants to unlock the phone to skip a song.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    if (!state.active) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter), verticalArrangement = Arrangement.Center) {
            EmptyState(
                DjIcons.Party,
                "No hay fiesta sonando",
                if (state.stats.fullPlays + state.stats.cuts > 0) {
                    "La última: ${state.stats.fullPlays} completas, ${state.stats.cuts} cortes y ${state.stats.dropEntries} entradas al drop."
                } else {
                    "Elige una playlist y dale play: el DJ decide el orden y cuándo cambiar."
                },
                action = "Elegir playlist",
                onAction = onLibrary,
            )
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = Space.xl), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = Space.md)) { NowPlaying(state, vm) }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = Space.md)) { PartyExtras(state, vm, onLibrary) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = Space.xs)) {
                NowPlaying(state, vm)
                PartyExtras(state, vm, onLibrary)
            }
        }
    }
}

@Composable
private fun NowPlaying(state: PartyState, vm: PartyViewModel) {
    val engine = vm.engine
    var dropMarkedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(dropMarkedAt) { if (dropMarkedAt != null) { delay(2_500); dropMarkedAt = null } }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("FIESTA · ${state.settings.mode.label().uppercase()}", style = DjType.technicalSmall, color = Dj.colors.textTertiary)
            Text(state.intent.label, style = DjType.meta, color = Dj.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TertiaryButton("Terminar", engine::stop)
    }

    // The cover changes with a short crossfade and a slight grow, never a jump.
    AnimatedContent(
        targetState = state.current?.imageUrl,
        transitionSpec = { (fadeIn(tween(Motion.SLOW)) + scaleIn(tween(Motion.SLOW), initialScale = 0.96f)) togetherWith fadeOut(tween(Motion.BASE)) },
        label = "cover",
        modifier = Modifier.fillMaxWidth().padding(top = Space.md),
        contentAlignment = Alignment.Center,
    ) { url ->
        Artwork(url, Modifier.widthIn(max = 420.dp).fillMaxWidth(), radius = Radius.lg, description = state.current?.name)
    }

    Row(Modifier.fillMaxWidth().padding(top = Space.xl), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.current?.name ?: "…", style = DjType.pageTitle, color = Dj.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.current?.artist.orEmpty(), style = DjType.body, color = Dj.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // "¡Drop!": tap when the drop hits, and every future party knows where it is.
        DjIconButton(
            DjIcons.Flag,
            "Marcar el drop aquí",
            {
                engine.markDrop()
                dropMarkedAt = state.positionMs
            },
            tint = if (dropMarkedAt != null) Dj.colors.accent else Dj.colors.textSecondary,
            size = IconSize.lg,
        )
    }
    dropMarkedAt?.let { Text("Drop guardado en ${formatTime(it)}", style = DjType.label, color = Dj.colors.accentBright) }

    val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    ProgressLine(progress, Modifier.padding(top = Space.md))
    Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(state.positionMs), style = DjType.technicalSmall, color = Dj.colors.textTertiary)
        Text(formatTime(state.durationMs), style = DjType.technicalSmall, color = Dj.colors.textTertiary)
    }

    Row(Modifier.fillMaxWidth().padding(top = Space.sm), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        DjIconButton(DjIcons.Dislike, "No va: saltar y no volver a ponerla", engine::dislike, tint = Dj.colors.textSecondary)
        DjIconButton(DjIcons.Previous, "Anterior", engine::previous, size = IconSize.lg)
        PlayButton(playing = !state.isPaused, onClick = engine::togglePause, size = 72.dp)
        DjIconButton(DjIcons.Next, "Siguiente", engine::next, size = IconSize.lg)
        DjIconButton(DjIcons.KeepFull, "Completa: esta suena entera", engine::keepFull, tint = if (state.plan?.exit is Exit.Full) Dj.colors.textPrimary else Dj.colors.textSecondary)
    }

    val p = state.currentProfile
    MusicFacts(p?.bpm, p?.key?.toString(), p?.energy, Modifier.padding(top = Space.lg))

    Column(Modifier.padding(top = Space.md)) { PlanLine(state) }

    state.externalTrackUri?.let {
        InlineMessage("Suena algo elegido en Spotify. El DJ espera: toca siguiente para devolverle el control.", Modifier.padding(top = Space.sm))
    }

    // Your own moves, on top of what the DJ does by itself.
    Row(Modifier.fillMaxWidth().padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        MoveButton(state.swapAtMs?.let { "Swap ${formatTime(it)}" } ?: "Drop swap", DjIcons.DropSwap, active = state.swapAtMs != null, onClick = engine::dropSwap, modifier = Modifier.weight(1f))
        MoveButton("Fundido", DjIcons.Fade, onClick = engine::fadeNow, modifier = Modifier.weight(1f))
        MoveButton("Otra vez", DjIcons.PullUp, onClick = engine::pullUp, modifier = Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        MoveButton("Más tranqui", DjIcons.Calm, onClick = engine::lessEnergy, modifier = Modifier.weight(1f))
        MoveButton("Más energía", DjIcons.Flame, onClick = engine::moreEnergy, modifier = Modifier.weight(1f))
    }
}

/** A DJ move: neutral until it is armed, then red. */
@Composable
private fun MoveButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    if (active) PrimaryButton(label, onClick, modifier, icon = icon) else SecondaryButton(label, onClick, modifier, icon = icon)
}

@Composable
private fun PartyExtras(state: PartyState, vm: PartyViewModel, onLibrary: () -> Unit) {
    if (state.upcoming.isNotEmpty()) {
        SectionHeader("A continuación")
        state.upcoming.take(5).forEachIndexed { index, track ->
            TrackRow(track, subtitle = if (index == 0) "Sigue · ${track.artist}" else track.artist, trailing = {
                Text(formatTime(track.durationMs), style = DjType.meta, color = Dj.colors.textTertiary)
            })
        }
    }

    SectionHeader("Pídele algo al DJ")
    RequestPanel(vm, state.intent.label, state.playlistId)

    SectionHeader("Cómo mezcla")
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        DjMode.entries.forEach { mode -> DjChip(mode.label(), state.settings.mode == mode, { vm.engine.changeMode(mode) }) }
    }
    Text(
        "${state.stats.fullPlays} completas · ${state.stats.cuts} cambios · ${state.stats.dropEntries} al drop · ${state.stats.manualSkips} saltos tuyos",
        style = DjType.meta,
        color = Dj.colors.textTertiary,
        modifier = Modifier.padding(top = Space.sm),
    )
    TertiaryButton("Cambiar de playlist", onLibrary, Modifier.padding(top = Space.xs, bottom = Space.xl), color = Dj.colors.accentBright)
}

/** "Pon algo para perrear": the only place the local model is used, and it never sees your songs. */
@Composable
private fun RequestPanel(vm: PartyViewModel, currentIntentLabel: String, playlistId: String?) {
    // Counting genres over the whole playlist is not free: once per playlist, not on every tick of the player.
    val genres = remember(playlistId) { vm.playlistGenres }
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    val last by vm.lastRequest.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val voiceState by vm.voiceState.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.listen()
    }

    // A finished sentence goes straight to be interpreted; you still have to tap to apply it.
    LaunchedEffect(voiceState) {
        (voiceState as? VoiceState.Heard)?.takeIf { it.final }?.let { vm.voiceRequest(it.text) }
    }

    // The genres actually present in this playlist: one tap instead of typing.
    if (genres.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            genres.forEach { genre -> DjChip(genre, false, { vm.request("pon $genre") }) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(Radius.md)).background(Dj.colors.surfaceSecondary).padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text("house estilo Avicii, sin Bad Bunny…", style = DjType.body, color = Dj.colors.textTertiary, maxLines = 1)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    enabled = !thinking,
                    textStyle = DjType.body.copy(color = Dj.colors.textPrimary),
                    cursorBrush = SolidColor(Dj.colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        DjIconButton(
            DjIcons.Mic,
            "Pedir con la voz",
            { if (vm.voice.hasPermission) vm.listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
            tint = if (voiceState is VoiceState.Listening) Dj.colors.accent else Dj.colors.textPrimary,
            enabled = voiceState !is VoiceState.Listening,
        )
    }
    if (text.isNotBlank()) {
        PrimaryButton(if (thinking) "Pensando…" else "Pedir", { vm.request(text); text = "" }, Modifier.padding(top = Space.sm), enabled = !thinking)
    }

    when (val v = voiceState) {
        VoiceState.Listening -> Text("Escuchando…", style = DjType.meta, color = Dj.colors.accentBright, modifier = Modifier.padding(top = Space.xs))
        is VoiceState.Heard -> if (!v.final) Text("«${v.text}»…", style = DjType.meta, color = Dj.colors.textSecondary, modifier = Modifier.padding(top = Space.xs))
        is VoiceState.Failed -> InlineMessage(v.reason, Modifier.padding(top = Space.xs))
        VoiceState.Unavailable -> InlineMessage("Este celular no tiene reconocimiento de voz disponible.", Modifier.padding(top = Space.xs))
        else -> Unit
    }

    pending?.let { proposal ->
        Panel(Modifier.padding(top = Space.sm)) {
            Text("Entendí: ${proposal.intent.label}", style = DjType.cardTitle, color = Dj.colors.textPrimary)
            Text(sourceLabel(proposal), style = DjType.meta, color = Dj.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                PrimaryButton("Dale", vm::applyPending, Modifier.weight(1f))
                SecondaryButton("Cancelar", vm::dismissPending, Modifier.weight(1f))
            }
        }
    }

    Text(
        "Ahora: $currentIntentLabel" + (last?.let { " · ${sourceLabel(it)}" } ?: ""),
        style = DjType.label,
        color = Dj.colors.textTertiary,
        modifier = Modifier.padding(top = Space.sm),
    )
    if (!vm.voice.onDeviceAvailable) {
        Text(
            "Para que tu voz no salga del celular, instala el reconocimiento sin conexión en Ajustes de Android → Idiomas → Reconocimiento de voz.",
            style = DjType.label,
            color = Dj.colors.textTertiary,
            modifier = Modifier.padding(top = Space.xxs),
        )
    }
}

private fun sourceLabel(result: InterpretedIntent) = when (result.source) {
    IntentSource.LLM -> "IA local (${result.tookMs / 100 / 10.0} s)"
    IntentSource.RULES -> "reglas"
    IntentSource.LLM_FALLBACK -> "reglas (la IA no respondió)"
}

/** What the DJ will do with this song and why. Red only when a real move is coming. */
@Composable
private fun PlanLine(state: PartyState) {
    val plan = state.plan ?: return
    val move = plan.entry is Entry.AtDrop || (plan.exit as? Exit.CutAt)?.swap == true || state.swapAtMs != null
    Text(PartyService.describe(state), style = DjType.cardTitle, color = if (move) Dj.colors.accentBright else Dj.colors.textPrimary)
    state.planNote?.let { Text(it, style = DjType.meta, color = Dj.colors.textSecondary, modifier = Modifier.padding(top = Space.xxs)) }
}
