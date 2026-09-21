package com.rvillarroel.djrodri.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.ai.IntentSource
import com.rvillarroel.djrodri.data.ai.InterpretedIntent
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.Entry
import com.rvillarroel.djrodri.domain.dj.Exit
import com.rvillarroel.djrodri.party.PartyService
import com.rvillarroel.djrodri.party.label
import com.rvillarroel.djrodri.voice.VoiceState
import com.rvillarroel.djrodri.ui.theme.DjColors
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

@Composable
fun PartyScreen(onBack: () -> Unit, onSwitchPlaylist: () -> Unit, vm: PartyViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val engine = vm.engine
    var dropMarkedAt by remember { mutableStateOf<Long?>(null) }

    // The screen stays on while the party screen is visible: nobody wants to unlock the phone to skip a song.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Fiesta", onBack = onBack, action = {
            if (state.active) TextButton(onClick = engine::stop) { Text("Terminar", color = DjColors.Warning) }
        })

        if (!state.active) {
            Panel {
                Text("La fiesta terminó", style = MaterialTheme.typography.titleLarge)
                SessionStatsRow(state.stats.fullPlays, state.stats.cuts, state.stats.dropEntries, state.stats.manualSkips)
            }
            return@Column
        }

        Panel {
            Text(state.current?.name ?: "…", style = MaterialTheme.typography.displaySmall, maxLines = 2)
            Text(state.current?.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, color = DjColors.TextDim)
            val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(state.positionMs), color = DjColors.TextDim)
                Text(formatTime(state.durationMs), color = DjColors.TextDim)
            }
            PlanLine(state)
        }

        state.externalTrackUri?.let {
            Panel {
                Text("Suena algo elegido desde Spotify. El DJ espera: toca ⏭ para devolverle el control.", color = DjColors.Warning)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("⏮", engine::previous, Modifier.weight(1f), color = DjColors.StageRaised)
            BigButton(if (state.isPaused) "▶" else "⏸", engine::togglePause, Modifier.weight(1f), color = DjColors.StageRaised)
            BigButton("⏭", engine::next, Modifier.weight(1f), color = DjColors.StageRaised)
        }
        // Your own DJ moves, on top of what the DJ does by itself.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(
                state.swapAtMs?.let { "💥 en ${formatTime(it)}" } ?: "💥 Drop swap",
                engine::dropSwap,
                Modifier.weight(1f),
                color = DjColors.Drop,
            )
            BigButton("🌊 Fundido ya", engine::fadeNow, Modifier.weight(1f), color = DjColors.Neon)
            BigButton("🔁 Otra vez", engine::pullUp, Modifier.weight(1f), color = DjColors.StageRaised)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Completa", engine::keepFull, Modifier.weight(1f), color = DjColors.Whole)
            BigButton("No va 👎", engine::dislike, Modifier.weight(1f), color = DjColors.Warning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("😌 Más tranqui", engine::lessEnergy, Modifier.weight(1f), color = DjColors.StageRaised)
            BigButton("🔥 Más energía", engine::moreEnergy, Modifier.weight(1f), color = DjColors.Drop)
        }

        RequestPanel(vm, state.intent.label)
        BigButton(
            dropMarkedAt?.let { "¡Drop guardado en ${formatTime(it)}!" } ?: "¡Drop! (toca cuando entra)",
            {
                engine.markDrop()
                dropMarkedAt = state.positionMs
            },
            Modifier.fillMaxWidth(),
            color = DjColors.Neon,
        )
        LaunchedEffect(dropMarkedAt) { if (dropMarkedAt != null) { delay(2_500); dropMarkedAt = null } }

        Panel {
            Text("Modo", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DjMode.entries.forEach { mode ->
                    FilterChip(selected = state.settings.mode == mode, onClick = { engine.changeMode(mode) }, label = { Text(mode.label()) })
                }
            }
            state.next?.let { Text("Después: ${it.name} — ${it.artist}", color = DjColors.TextDim) }
            TextButton(onClick = onSwitchPlaylist) { Text("Cambiar de playlist", color = DjColors.NeonSoft) }
        }

        Panel {
            Text("Esta fiesta", style = MaterialTheme.typography.titleMedium)
            SessionStatsRow(state.stats.fullPlays, state.stats.cuts, state.stats.dropEntries, state.stats.manualSkips)
        }
    }
}

/** "Pon algo para perrear": the only place the local model is used, and it never sees your songs. */
@Composable
private fun RequestPanel(vm: PartyViewModel, currentIntentLabel: String) {
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

    Panel {
        Text("Pídele algo al DJ", style = MaterialTheme.typography.titleMedium)
        // The genres actually present in this playlist: one tap instead of typing.
        if (vm.playlistGenres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.playlistGenres.forEach { genre ->
                    FilterChip(selected = false, onClick = { vm.request("pon $genre") }, label = { Text(genre) })
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("pon algo de house estilo Avicii, sin Bad Bunny") },
            singleLine = true,
            enabled = !thinking,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(
                if (thinking) "Pensando…" else "Pedir",
                { vm.request(text); text = "" },
                Modifier.weight(1f),
                color = DjColors.Neon,
                enabled = !thinking && text.isNotBlank(),
            )
            BigButton(
                if (voiceState is VoiceState.Listening) "🎙 Escuchando…" else "🎤 Hablar",
                { if (vm.voice.hasPermission) vm.listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                Modifier.weight(1f),
                color = DjColors.StageRaised,
                enabled = voiceState !is VoiceState.Listening,
            )
        }

        when (val v = voiceState) {
            is VoiceState.Heard -> if (!v.final) Text("“${v.text}”…", color = DjColors.NeonSoft)
            is VoiceState.Failed -> Text(v.reason, color = DjColors.Warning)
            VoiceState.Unavailable -> Text("Este celular no tiene reconocimiento de voz disponible.", color = DjColors.Warning)
            else -> Unit
        }

        pending?.let { proposal ->
            Text("Entendí: ${proposal.intent.label}", style = MaterialTheme.typography.titleMedium, color = DjColors.Drop)
            Text(sourceLabel(proposal), color = DjColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("✅ Dale", vm::applyPending, Modifier.weight(1f), color = DjColors.Drop)
                BigButton("Cancelar", vm::dismissPending, Modifier.weight(1f), color = DjColors.StageRaised)
            }
        }

        Text(
            "Ahora mismo: $currentIntentLabel" + (last?.let { " · ${sourceLabel(it)}" } ?: ""),
            color = DjColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!vm.voice.onDeviceAvailable) {
            Text(
                "El reconocimiento sin conexión no está instalado: instálalo en Ajustes de Android → Idiomas → " +
                    "Reconocimiento de voz, para que tu voz no salga del celular.",
                color = DjColors.TextDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun sourceLabel(result: InterpretedIntent) = when (result.source) {
    IntentSource.LLM -> "IA local (${result.tookMs / 100 / 10.0} s)"
    IntentSource.RULES -> "reglas"
    IntentSource.LLM_FALLBACK -> "reglas (la IA no respondió)"
}

@Composable
private fun PlanLine(state: com.rvillarroel.djrodri.data.party.PartyState) {
    val plan = state.plan ?: return
    val color = when {
        plan.entry is Entry.AtDrop -> DjColors.Drop
        plan.exit is Exit.CutAt -> DjColors.Neon
        else -> DjColors.Whole
    }
    Text(PartyService.describe(state), color = color, style = MaterialTheme.typography.titleMedium)
    state.planNote?.let { Text(it, color = DjColors.TextDim, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun SessionStatsRow(full: Int, cuts: Int, drops: Int, skips: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Stat("completas", "$full", color = DjColors.Whole)
        Stat("cortes", "$cuts", color = DjColors.Neon)
        Stat("al drop", "$drops", color = DjColors.Drop)
        Stat("saltos tuyos", "$skips", color = DjColors.Warning)
    }
}

@Composable
fun CenteredNote(text: String) = Text(text, textAlign = TextAlign.Center, color = DjColors.TextDim, modifier = Modifier.fillMaxWidth())
