package com.rvillarroel.djrodri.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rvillarroel.djrodri.ui.theme.Dj
import com.rvillarroel.djrodri.ui.theme.DjType
import com.rvillarroel.djrodri.ui.theme.Space

/** The first screen: the name, what the app does in one sentence, and the one thing to do. */
@Composable
fun LoginScreen(error: String?, onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Space.xxl, vertical = Space.huge),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.weight(1f))
        Column(Modifier.widthIn(max = 480.dp)) {
            Text("DJ", style = DjType.display, color = Dj.colors.textPrimary)
            Text("Rodri", style = DjType.display, color = Dj.colors.accent)
            Text(
                "Tus playlists, mezcladas como en una fiesta de verdad: cambios en el momento justo, entradas en el drop y las canciones que lo merecen, completas.",
                style = DjType.body,
                color = Dj.colors.textSecondary,
                modifier = Modifier.padding(top = Space.lg),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.widthIn(max = 480.dp)) {
            PrimaryButton("Conectar con Spotify", onLogin, Modifier.fillMaxWidth())
            error?.let { InlineMessage(it, Modifier.padding(top = Space.sm)) }
            Spacer(Modifier.height(Space.md))
            Text(
                "Necesitas Spotify Premium y la app de Spotify instalada. DJ Rodri nunca toca el audio: solo elige, salta y adelanta.",
                style = DjType.meta,
                color = Dj.colors.textTertiary,
            )
        }
    }
}
