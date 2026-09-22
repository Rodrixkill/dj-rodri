package com.rvillarroel.djrodri.settings

import android.content.Context
import com.rvillarroel.djrodri.data.party.PartySettings
import com.rvillarroel.djrodri.domain.dj.DjMode
import com.rvillarroel.djrodri.domain.dj.StyleFamily
import org.json.JSONArray
import org.json.JSONObject
import com.rvillarroel.djrodri.spotify.Profile
import com.rvillarroel.djrodri.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A handful of preferences; SharedPreferences is plenty and survives everything. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _party = MutableStateFlow(loadParty())
    val party: StateFlow<PartySettings> = _party

    private val _hyperOsGuideDone = MutableStateFlow(prefs.getBoolean(KEY_GUIDE, false))
    val hyperOsGuideDone: StateFlow<Boolean> = _hyperOsGuideDone

    /** Use the local model to read your requests. Off means the keyword rules, which are instant. */
    private val _useLocalAi = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_AI, true))
    val useLocalAi: StateFlow<Boolean> = _useLocalAi

    fun setUseLocalAi(use: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_AI, use).apply()
        _useLocalAi.value = use
    }

    val lastPlaylistId: String? get() = prefs.getString(KEY_LAST_PLAYLIST, null)

    /** Follows the phone by default; Ajustes can pin it dark or light. */
    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    /** Your Spotify name and picture, kept so the greeting shows instantly, even offline. */
    private val _profile = MutableStateFlow(Profile(prefs.getString(KEY_NAME, null), prefs.getString(KEY_AVATAR, null)))
    val profile: StateFlow<Profile> = _profile

    fun rememberProfile(profile: Profile) {
        prefs.edit().putString(KEY_NAME, profile.name).putString(KEY_AVATAR, profile.imageUrl).apply()
        _profile.value = profile
    }

    /**
     * Your songs grouped by style, as the last analysis left them: {"REGGAETON": ["id", …]}. Null until the first
     * grouping. Kept here so Home and Search read it instead of recomputing it.
     */
    val styleIndex: Map<StyleFamily, List<String>>?
        get() = prefs.getString(KEY_STYLE_INDEX, null)?.let { text ->
            runCatching {
                val json = JSONObject(text)
                json.keys().asSequence().mapNotNull { key ->
                    val family = runCatching { StyleFamily.valueOf(key) }.getOrNull() ?: return@mapNotNull null
                    val ids = json.getJSONArray(key)
                    family to List(ids.length()) { ids.getString(it) }
                }.toMap()
            }.getOrNull()
        }

    fun saveStyleIndex(index: Map<StyleFamily, List<String>>) {
        val json = JSONObject()
        index.forEach { (family, ids) -> json.put(family.name, JSONArray(ids)) }
        prefs.edit().putString(KEY_STYLE_INDEX, json.toString()).apply()
    }

    /** What you searched for, newest first. */
    val recentSearches: List<String>
        get() = prefs.getString(KEY_SEARCHES, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    fun rememberSearch(text: String) {
        val clean = text.trim().takeIf { it.length >= 2 } ?: return
        val list = (listOf(clean) + recentSearches.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX_SEARCHES)
        prefs.edit().putString(KEY_SEARCHES, list.joinToString("\n")).apply()
    }

    fun forgetSearch(text: String) {
        prefs.edit().putString(KEY_SEARCHES, recentSearches.filterNot { it == text }.joinToString("\n")).apply()
    }

    fun updateParty(settings: PartySettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putBoolean(KEY_DROP_PAUSE, settings.dropWithPause)
            .putLong(KEY_CUT_LEAD, settings.cutLeadMs)
            .putBoolean(KEY_SPOTIFY_FADE, settings.useSpotifyFade)
            .apply()
        _party.value = settings
    }

    fun setHyperOsGuideDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_GUIDE, done).apply()
        _hyperOsGuideDone.value = done
    }

    fun rememberPlaylist(id: String) = prefs.edit().putString(KEY_LAST_PLAYLIST, id).apply()

    /** The playlist made from your likes, so updating it doesn't make another one. */
    var likedCopyId: String?
        get() = prefs.getString(KEY_LIKED_COPY, null)
        set(value) = prefs.edit().putString(KEY_LIKED_COPY, value).apply()

    private fun loadParty() = PartySettings(
        mode = runCatching { DjMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(DjMode.FIESTA),
        dropWithPause = prefs.getBoolean(KEY_DROP_PAUSE, false),
        cutLeadMs = prefs.getLong(KEY_CUT_LEAD, PartySettings().cutLeadMs),
        useSpotifyFade = prefs.getBoolean(KEY_SPOTIFY_FADE, true),
    )

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_DROP_PAUSE = "drop_with_pause"
        const val KEY_CUT_LEAD = "cut_lead_ms"
        const val KEY_SPOTIFY_FADE = "use_spotify_fade"
        const val KEY_GUIDE = "hyperos_guide_done"
        const val KEY_LOCAL_AI = "use_local_ai"
        const val KEY_LAST_PLAYLIST = "last_playlist"
        const val KEY_LIKED_COPY = "liked_copy"
        const val KEY_THEME = "theme"
        const val KEY_NAME = "profile_name"
        const val KEY_AVATAR = "profile_avatar"
        const val KEY_SEARCHES = "recent_searches"
        const val MAX_SEARCHES = 8
        const val KEY_STYLE_INDEX = "style_index"
    }
}
