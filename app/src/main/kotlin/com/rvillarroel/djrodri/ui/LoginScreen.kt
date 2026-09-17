package com.rvillarroel.djrodri.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rvillarroel.djrodri.ui.theme.DjColors

@Composable
fun LoginScreen(error: String?, onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DJ Rodri", style = MaterialTheme.typography.displaySmall, color = DjColors.Neon)
        Text(
            "Analiza tus playlists y maneja la música de la fiesta como un DJ: corta en buenos momentos, entra en el drop y deja completas las que lo merecen.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = DjColors.TextDim,
        )
        BigButton("Conectar con Spotify", onLogin, Modifier.fillMaxWidth())
        error?.let { Text(it, color = DjColors.Warning, textAlign = TextAlign.Center) }
        Text(
            "Necesitas Spotify Premium y la app de Spotify instalada. DJ Rodri nunca toca el audio: solo elige, salta y adelanta.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = DjColors.TextDim,
        )
    }
}
