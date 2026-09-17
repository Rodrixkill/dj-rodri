package com.rvillarroel.djrodri.settings

import android.content.Context
import com.rvillarroel.djrodri.data.party.PartySettings
import com.rvillarroel.djrodri.domain.dj.DjMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A handful of preferences; SharedPreferences is plenty and survives everything. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _party = MutableStateFlow(loadParty())
    val party: StateFlow<PartySettings> = _party

    private val _hyperOsGuideDone = MutableStateFlow(prefs.getBoolean(KEY_GUIDE, false))
    val hyperOsGuideDone: StateFlow<Boolean> = _hyperOsGuideDone

    val lastPlaylistId: String? get() = prefs.getString(KEY_LAST_PLAYLIST, null)

    fun updateParty(settings: PartySettings) {
        prefs.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putBoolean(KEY_DROP_PAUSE, settings.dropWithPause)
            .putLong(KEY_CUT_LEAD, settings.cutLeadMs)
            .apply()
        _party.value = settings
    }

    fun setHyperOsGuideDone(done: Boolean) {
        prefs.edit().putBoolean(KEY_GUIDE, done).apply()
        _hyperOsGuideDone.value = done
    }

    fun rememberPlaylist(id: String) = prefs.edit().putString(KEY_LAST_PLAYLIST, id).apply()

    private fun loadParty() = PartySettings(
        mode = runCatching { DjMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(DjMode.FIESTA),
        dropWithPause = prefs.getBoolean(KEY_DROP_PAUSE, false),
        cutLeadMs = prefs.getLong(KEY_CUT_LEAD, PartySettings().cutLeadMs),
    )

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_DROP_PAUSE = "drop_with_pause"
        const val KEY_CUT_LEAD = "cut_lead_ms"
        const val KEY_GUIDE = "hyperos_guide_done"
        const val KEY_LAST_PLAYLIST = "last_playlist"
    }
}
