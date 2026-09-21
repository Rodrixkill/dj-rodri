package com.rvillarroel.djrodri.party

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rvillarroel.djrodri.MainActivity
import com.rvillarroel.djrodri.R
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.data.party.PartyState
import com.rvillarroel.djrodri.domain.dj.Entry
import com.rvillarroel.djrodri.domain.dj.Exit
import com.rvillarroel.djrodri.surfaces.PartyWidget
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Keeps the party alive while the screen is off or another app is open. HyperOS kills background apps without
 * mercy; a foreground service with a visible notification is the one thing it respects.
 */
class PartyService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(PartyState()), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val engine = container.party
        lifecycleScope.launch {
            engine.state
                // Position changes ten times a second; the notification only cares about songs and decisions.
                .distinctUntilChangedBy { listOf(it.active, it.current?.id, it.next?.id, it.plan, it.isPaused, it.externalTrackUri, it.settings.mode) }
                .collect { state ->
                    if (!state.active) {
                        PartyWidget.refresh(this@PartyService)
                        stopSelf()
                        return@collect
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                    PartyWidget.refresh(this@PartyService)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val engine = container.party
        when (intent?.action) {
            ACTION_NEXT -> engine.next()
            ACTION_PREVIOUS -> engine.previous()
            ACTION_PAUSE -> engine.togglePause()
            ACTION_KEEP_FULL -> engine.keepFull()
            ACTION_DISLIKE -> engine.dislike()
            ACTION_STOP -> engine.stop()
        }
        return START_NOT_STICKY
    }

    private fun notification(state: PartyState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val song = state.current
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dj)
            .setContentTitle(song?.let { "${it.name} — ${it.artist}" } ?: "DJ Rodri")
            .setContentText(describe(state))
            .setSubText(state.settings.mode.label())
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "⏭ Siguiente", action(ACTION_NEXT))
            .addAction(0, "Completa", action(ACTION_KEEP_FULL))
            .addAction(0, "No va", action(ACTION_DISLIKE))
            .build()
    }

    private fun action(name: String) =
        PendingIntent.getService(this, name.hashCode(), Intent(this, PartyService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Fiesta en curso", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL = "party"
        private const val NOTIFICATION_ID = 1
        const val ACTION_NEXT = "com.rvillarroel.djrodri.NEXT"
        const val ACTION_PREVIOUS = "com.rvillarroel.djrodri.PREVIOUS"
        const val ACTION_PAUSE = "com.rvillarroel.djrodri.PAUSE"
        const val ACTION_KEEP_FULL = "com.rvillarroel.djrodri.KEEP_FULL"
        const val ACTION_DISLIKE = "com.rvillarroel.djrodri.DISLIKE"
        const val ACTION_STOP = "com.rvillarroel.djrodri.STOP"

        fun startIntent(context: Context) = Intent(context, PartyService::class.java)

        fun actionIntent(context: Context, action: String) = Intent(context, PartyService::class.java).setAction(action)

        /** One line a person reads at a glance: what the DJ will do with this song. */
        fun describe(state: PartyState): String {
            if (state.externalTrackUri != null) return "Suena algo elegido en Spotify · toca ⏭ para devolverle el control al DJ"
            val plan = state.plan ?: return "Preparando…"
            val next = state.next?.name
            fun time(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
            state.swapAtMs?.let { return "💥 Tu drop swap en ${time(it)}" + (next?.let { " → drop de $it" } ?: "") }
            return when (val exit = plan.exit) {
                Exit.Full -> if (next != null) "Completa · después: $next" else "Completa · última canción"
                is Exit.CutAt -> when {
                    exit.swap -> "💥 Drop swap en ${time(exit.positionMs)}" + (next?.let { " → drop de $it" } ?: "")
                    else -> {
                        val entry = if (plan.entry is Entry.AtDrop) " directo al drop" else ""
                        "Cambio en ${time(exit.positionMs)}$entry" + (next?.let { " → $it" } ?: "")
                    }
                }
            }
        }
    }
}
