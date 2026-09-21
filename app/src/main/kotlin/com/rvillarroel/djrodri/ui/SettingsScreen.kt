package com.rvillarroel.djrodri.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.ai.IntentSource
import com.rvillarroel.djrodri.data.ai.LlmStatus
import com.rvillarroel.djrodri.data.party.PartySettings
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.party.description
import com.rvillarroel.djrodri.party.label
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.Space
import com.rvillarroel.djrodri.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val settings = container.settings.party
    val sessions = container.database.party().recentSessions()
    val dropMarks = container.database.analysis().dropMarkCount()
    val useLocalAi = container.settings.useLocalAi
    val llmStatus = container.llm.status
    val modelsDir: String get() = container.llm.modelsDir.absolutePath
    val aiTest = MutableStateFlow<String?>(null)

    fun refreshModel() = container.llm.refreshStatus()

    fun setUseLocalAi(use: Boolean) {
        container.settings.setUseLocalAi(use)
        if (!use) container.llm.unload()
    }

    /** Runs one real request through the model so you can see what it understood and how long it took. */
    fun testAi(request: String) = viewModelScope.launch {
        aiTest.value = "Pensando…"
        val result = container.interpret(request)
        val i = result.intent
        aiTest.value = buildString {
            append(
                when (result.source) {
                    IntentSource.LLM -> "IA local en ${result.tookMs} ms"
                    IntentSource.RULES -> "reglas"
                    IntentSource.LLM_FALLBACK -> "la IA no sirvió, respondieron las reglas (${result.tookMs} ms)"
                },
            )
            append(" → ").append(i.label)
            i.targetEnergy?.let { append(" · energía ").append("%.2f".format(it)) }
            append(" · ").append(i.curve.name.lowercase())
            i.minBpm?.let { min -> append(" · ").append(min.toInt()).append("-").append(i.maxBpm?.toInt() ?: 0).append(" BPM") }
            if (i.excludeArtists.isNotEmpty()) append(" · sin ").append(i.excludeArtists.joinToString())
        }
    }

    val theme = container.settings.theme

    fun setTheme(mode: ThemeMode) = container.settings.setTheme(mode)

    fun update(settings: PartySettings) {
        container.settings.updateParty(settings)
        if (container.party.state.value.active) container.party.updateSettings(settings)
    }

    fun logout() {
        container.party.stop()
        container.remote.disconnect()
        container.auth.logout()
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onLab: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle(emptyList())
    val dropMarks by vm.dropMarks.collectAsStateWithLifecycle(0)
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader("Ajustes", onBack = onBack)

        SettingsGroup("Apariencia") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                ThemeMode.entries.forEach { mode -> DjChip(mode.label, theme == mode, { vm.setTheme(mode) }) }
            }
            Hint("Sistema sigue el modo oscuro o claro del celular.")
        }

        SettingsGroup("Cómo mezcla el DJ") {
            DjMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DjChip(mode.label(), settings.mode == mode, { vm.update(settings.copy(mode = mode)) }, Modifier.width(112.dp))
                    Text(mode.description(), style = DjType.meta, color = Dj.colors.textSecondary, modifier = Modifier.padding(start = Space.sm))
                }
            }
        }

        SettingsGroup("Transiciones") {
            SwitchRow(
                "Fundido de Spotify",
                "Las canciones que terminan solas y los cambios de house, techno o disco usan el crossfade de Spotify. " +
                    "Necesita el crossfade activado en Spotify (Ajustes → Reproducción).",
                settings.useSpotifyFade,
            ) { vm.update(settings.copy(useSpotifyFade = it)) }
            SwitchRow(
                "Pausa corta antes del drop",
                "Evita que se oiga el inicio de la canción antes del salto. Actívala si la entrada directa se nota.",
                settings.dropWithPause,
            ) { vm.update(settings.copy(dropWithPause = it)) }
            Text("Adelanto del corte · ${settings.cutLeadMs} ms", style = DjType.cardTitle, color = Dj.colors.textPrimary)
            Hint("Compensa lo que tarda Spotify en cambiar de canción (medido en el celular: 80–110 ms).")
            Slider(
                value = settings.cutLeadMs.toFloat(),
                onValueChange = { vm.update(settings.copy(cutLeadMs = (it / 25).toLong() * 25)) },
                valueRange = 0f..800f,
                colors = SliderDefaults.colors(
                    thumbColor = Dj.colors.accent,
                    activeTrackColor = Dj.colors.accent,
                    inactiveTrackColor = Dj.colors.progressInactive,
                ),
            )
        }

        AiPanel(vm)

        SettingsGroup("Que HyperOS no corte la fiesta") {
            Hint("Xiaomi cierra apps en segundo plano. Haz estos pasos una vez y revísalos después de actualizar HyperOS.")
            TertiaryButton("1 · Activar inicio automático", { openAutostart(context) }, color = Dj.colors.accentBright)
            TertiaryButton(
                if (isIgnoringBattery(context)) "2 · Batería sin restricciones ✓" else "2 · Quitar restricción de batería",
                { openBatterySettings(context) },
                color = Dj.colors.accentBright,
            )
            Hint("3 · En Recientes, mantén presionada DJ Rodri y toca el candado.")
        }

        SettingsGroup("Tus fiestas") {
            Hint("$dropMarks drops marcados a mano")
            sessions.take(8).forEach { s ->
                val songs = s.fullPlays + s.cuts + s.manualSkips
                val skipRate = if (songs == 0) 0 else s.manualSkips * 100 / songs
                Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.startedAtMs))} · $songs canciones · " +
                        "${s.cuts} cambios · ${s.dropEntries} al drop · saltos $skipRate %",
                    style = DjType.meta,
                    color = if (skipRate > 30) Dj.colors.danger else Dj.colors.textPrimary,
                )
            }
            if (sessions.isEmpty()) Hint("Todavía no hay fiestas registradas.")
        }

        SettingsGroup("Cuenta y pruebas") {
            TertiaryButton("Laboratorio: pruebas de sincronía con Spotify", onLab)
            TertiaryButton("Cerrar sesión de Spotify", vm::logout, color = Dj.colors.danger)
        }
        Spacer(Modifier.height(Space.xxl))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title.uppercase(), style = DjType.technicalSmall, color = Dj.colors.textTertiary, modifier = Modifier.padding(start = Space.xxs, bottom = Space.xs))
        Panel { content() }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = DjType.meta, color = Dj.colors.textSecondary)

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(title, style = DjType.cardTitle, color = Dj.colors.textPrimary)
            Hint(hint)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Dj.colors.accent,
                checkedThumbColor = Dj.colors.onAccent,
                uncheckedTrackColor = Dj.colors.surfaceTertiary,
                uncheckedThumbColor = Dj.colors.textSecondary,
                uncheckedBorderColor = Dj.colors.surfaceTertiary,
            ),
        )
    }
}

/**
 * The local model: what state it is in, how to install it, and a place to try it.
 * The model file is not shipped with the app; you copy it to the phone yourself.
 */
@Composable
private fun AiPanel(vm: SettingsViewModel) {
    val useAi by vm.useLocalAi.collectAsStateWithLifecycle()
    val status by vm.llmStatus.collectAsStateWithLifecycle()
    val testResult by vm.aiTest.collectAsStateWithLifecycle()
    var request by remember { mutableStateOf("pon algo para perrear sin Bad Bunny") }

    LaunchedEffect(Unit) { vm.refreshModel() }

    SettingsGroup("Pedidos al DJ") {
        SwitchRow(
            "IA local",
            "Entiende pedidos como «algo para perrear pero sin Bad Bunny». Corre en tu celular y solo recibe tu frase: " +
                "nunca ve tus canciones. Apagada, el DJ usa palabras clave.",
            useAi,
            vm::setUseLocalAi,
        )
        when (val s = status) {
            is LlmStatus.Ready -> Text("Modelo ${s.modelName} listo en ${s.backend} (cargó en ${s.loadMs} ms)", style = DjType.meta, color = Dj.colors.success)
            LlmStatus.Loading -> Hint("Cargando el modelo…")
            is LlmStatus.Failed -> Text(s.reason, style = DjType.meta, color = Dj.colors.danger)
            LlmStatus.NotInstalled -> {
                Hint("No hay modelo instalado: por ahora responden las palabras clave. Copia un modelo .litertlm (por ejemplo Gemma de litert-community en Hugging Face) con:")
                Text("adb push modelo.litertlm ${vm.modelsDir}/", style = DjType.technicalSmall, color = Dj.colors.textPrimary)
            }
        }
        SearchField(request, { request = it }, placeholder = "Escribe un pedido de prueba", trailing = {
            TertiaryButton("Probar", { vm.testAi(request) }, color = Dj.colors.accentBright)
        })
        testResult?.let { Text(it, style = DjType.meta, color = Dj.colors.textPrimary) }
    }
}

private fun openAutostart(context: Context) {
    val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    runCatching { context.startActivity(miui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { openAppDetails(context) }
}

@SuppressLint("BatteryLife") // Personal app, never on Play: asking directly is the honest way to survive HyperOS.
private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { openAppDetails(context) }
}

private fun openAppDetails(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun isIgnoringBattery(context: Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
