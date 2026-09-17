package com.rvillarroel.djrodri.spotify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rvillarroel.djrodri.BuildConfig
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SpotifyTokens(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long)

/**
 * Tokens encrypted with a key that never leaves the Android Keystore. Nothing about the Spotify session is
 * readable from a backup or another app.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("spotify_session", Context.MODE_PRIVATE)

    fun load(): SpotifyTokens? {
        val sealed = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            val json = JSONObject(String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8))
            SpotifyTokens(
                accessToken = json.getString("access"),
                refreshToken = json.optString("refresh").takeIf { it.isNotEmpty() },
                expiresAtMs = json.getLong("expiresAt"),
            )
        }.getOrNull()
    }

    fun save(tokens: SpotifyTokens) {
        val json = JSONObject()
            .put("access", tokens.accessToken)
            .put("refresh", tokens.refreshToken.orEmpty())
            .put("expiresAt", tokens.expiresAtMs)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY = "tokens"
        const val ALIAS = "djrodri_spotify_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/**
 * Login with PKCE (no client secret in the app) and silent refresh. Auth 5.0.0 does the code exchange itself when
 * asked for a TOKEN with PKCE, and hands back a refresh token we use to stay logged in for good.
 */
class SpotifyAuth(private val store: TokenStore, private val http: OkHttpClient) {
    private val refreshLock = Mutex()
    private val _loggedIn = MutableStateFlow(store.load() != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    fun loginRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder(BuildConfig.SPOTIFY_CLIENT_ID, AuthorizationResponse.Type.TOKEN, BuildConfig.SPOTIFY_REDIRECT_URI)
            .setScopes(SCOPES)
            .build()

    /** @return an error message in Spanish, or null when the login worked. */
    fun onLoginResponse(response: AuthorizationResponse): String? {
        if (response.type != AuthorizationResponse.Type.TOKEN || response.accessToken == null) {
            return when (response.type) {
                AuthorizationResponse.Type.EMPTY -> "Inicio de sesión cancelado"
                else -> "Spotify no aceptó el inicio de sesión: ${response.error ?: response.type}"
            }
        }
        store.save(
            SpotifyTokens(
                accessToken = response.accessToken!!,
                refreshToken = response.refreshToken,
                expiresAtMs = System.currentTimeMillis() + response.expiresIn * 1_000L,
            ),
        )
        _loggedIn.value = true
        return null
    }

    /** A valid access token, refreshed if it is about to expire; null when you need to log in again. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = refreshLock.withLock {
        val tokens = store.load() ?: return@withLock null.also { _loggedIn.value = false }
        if (!forceRefresh && tokens.expiresAtMs - EXPIRY_MARGIN_MS > System.currentTimeMillis()) return@withLock tokens.accessToken
        val refreshToken = tokens.refreshToken ?: return@withLock null.also { logout() }
        refresh(refreshToken)?.accessToken
    }

    fun logout() {
        store.clear()
        _loggedIn.value = false
    }

    private suspend fun refresh(refreshToken: String): SpotifyTokens? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .build()
        val request = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        runCatching {
            http.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (!response.isSuccessful) {
                    // invalid_grant means the refresh token was revoked: only then is a new login needed.
                    if (json.optString("error") == "invalid_grant") logout()
                    return@use null
                }
                SpotifyTokens(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() } ?: refreshToken,
                    expiresAtMs = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1_000L,
                ).also(store::save)
            }
        }.getOrNull()
    }

    companion object {
        private const val EXPIRY_MARGIN_MS = 60_000L
        val SCOPES = arrayOf(
            "playlist-read-private", "playlist-read-collaborative", "user-library-read",
            "user-read-playback-state", "user-modify-playback-state", "app-remote-control",
        )
    }
}
