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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.party.PartySettings
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.party.description
import com.rvillarroel.djrodri.party.label
import com.rvillarroel.djrodri.ui.theme.DjColors
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val settings = container.settings.party
    val sessions = container.database.party().recentSessions()
    val dropMarks = container.database.analysis().dropMarkCount()

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
    val sessions by vm.sessions.collectAsStateWithLifecycle(emptyList())
    val dropMarks by vm.dropMarks.collectAsStateWithLifecycle(0)
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Ajustes", onBack = onBack)

        Panel {
            Text("Modo por defecto", style = MaterialTheme.typography.titleMedium)
            DjMode.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = settings.mode == mode, onClick = { vm.update(settings.copy(mode = mode)) }, label = { Text(mode.label()) })
                    Text(mode.description(), color = DjColors.TextDim, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pausa corta antes del drop", style = MaterialTheme.typography.titleMedium)
                    Text("Evita que se escuche el inicio de la canción antes del salto. Actívala si la entrada directa se nota.", color = DjColors.TextDim)
                }
                Switch(checked = settings.dropWithPause, onCheckedChange = { vm.update(settings.copy(dropWithPause = it)) })
            }
            Text("Adelanto del corte: ${settings.cutLeadMs} ms", style = MaterialTheme.typography.titleMedium)
            Text("Compensa lo que tarda Spotify en cambiar de canción. Usa la latencia que midió la prueba de la fase 0.", color = DjColors.TextDim)
            Slider(
                value = settings.cutLeadMs.toFloat(),
                onValueChange = { vm.update(settings.copy(cutLeadMs = (it / 25).toLong() * 25)) },
                valueRange = 0f..800f,
            )
        }

        Panel {
            Text("Que HyperOS no mate la fiesta", style = MaterialTheme.typography.titleMedium)
            Text("Xiaomi cierra apps en segundo plano. Haz estos tres pasos una vez (y revísalos después de actualizar HyperOS).", color = DjColors.TextDim)
            TextButton(onClick = { openAutostart(context) }) { Text("1. Activar inicio automático") }
            TextButton(onClick = { openBatterySettings(context) }) {
                Text(if (isIgnoringBattery(context)) "2. Batería sin restricciones ✓" else "2. Quitar restricción de batería")
            }
            Text("3. En Recientes, mantén presionada DJ Rodri y toca el candado.", color = DjColors.TextDim)
        }

        Panel {
            Text("Tus datos", style = MaterialTheme.typography.titleMedium)
            Text("$dropMarks drops marcados a mano", color = DjColors.TextDim)
            sessions.take(8).forEach { s ->
                val songs = s.fullPlays + s.cuts + s.manualSkips
                val skipRate = if (songs == 0) 0 else s.manualSkips * 100 / songs
                Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.startedAtMs))} · $songs canciones · " +
                        "${s.cuts} cortes · ${s.dropEntries} drops · saltos $skipRate%",
                    color = if (skipRate > 30) DjColors.Warning else DjColors.Text,
                )
            }
            if (sessions.isEmpty()) Text("Todavía no hay fiestas registradas.", color = DjColors.TextDim)
        }

        Panel {
            TextButton(onClick = onLab) { Text("Laboratorio: pruebas de la fase 0") }
            TextButton(onClick = vm::logout) { Text("Cerrar sesión de Spotify", color = DjColors.Warning) }
        }
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
