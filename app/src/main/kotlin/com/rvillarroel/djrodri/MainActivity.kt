package com.rvillarroel.djrodri

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.rvillarroel.djrodri.ui.DjRodriRoot
import com.spotify.sdk.android.auth.AuthorizationClient

class MainActivity : ComponentActivity() {
    private val loginError = mutableStateOf<String?>(null)

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        loginError.value = container.auth.onLoginResponse(AuthorizationClient.getResponse(result.resultCode, result.data))
    }

    // The party notification is how you control it with the phone locked; without this permission it is invisible.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            DjRodriRoot(container, loginError.value, onLogin = ::startLogin)
        }
    }

    private fun startLogin() {
        if (BuildConfig.SPOTIFY_CLIENT_ID.isBlank()) {
            loginError.value = "Falta SPOTIFY_CLIENT_ID en local.properties"
            return
        }
        loginError.value = null
        login.launch(AuthorizationClient.createLoginActivityIntent(this, container.auth.loginRequest()))
    }
}
