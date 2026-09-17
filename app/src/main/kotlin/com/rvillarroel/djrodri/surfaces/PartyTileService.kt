package com.rvillarroel.djrodri.surfaces

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rvillarroel.djrodri.MainActivity
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.party.PartyService

/**
 * Quick Settings tile: during a party it skips to the next song; otherwise it opens the app on the party screen.
 * Starting a party needs the Spotify connection, which needs an Activity, so the tile never starts one blind.
 */
class PartyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val state = container.party.state.value
        qsTile?.apply {
            this.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (state.active) "DJ: siguiente" else "DJ Rodri"
            subtitle = state.current?.name ?: "Toca para empezar"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (container.party.state.value.active) {
            startService(PartyService.actionIntent(this, PartyService.ACTION_NEXT))
        } else {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        }
    }
}
