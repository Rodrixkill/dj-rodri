package com.rvillarroel.djrodri.surfaces

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.rvillarroel.djrodri.MainActivity
import com.rvillarroel.djrodri.container
import com.rvillarroel.djrodri.party.PartyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home screen widget: what is playing and the three buttons you want at a party. */
class PartyWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = context.container.party.state.value
        provideContent {
            val white = ColorProvider(Color.White)
            val dim = ColorProvider(Color(0xFFB9A8FF))
            Column(
                modifier = GlanceModifier.fillMaxSize().background(Color(0xFF1A1033)).padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.current?.name ?: "DJ Rodri",
                    style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    if (state.active) PartyService.describe(state) else "Toca para empezar la fiesta",
                    style = TextStyle(color = dim, fontSize = 12.sp),
                    maxLines = 2,
                )
                if (state.active) {
                    Row(modifier = GlanceModifier.padding(top = 8.dp)) {
                        WidgetButton("⏮", PartyService.ACTION_PREVIOUS)
                        Spacer(GlanceModifier.width(16.dp))
                        WidgetButton("⏭", PartyService.ACTION_NEXT)
                        Spacer(GlanceModifier.width(16.dp))
                        WidgetButton("Completa", PartyService.ACTION_KEEP_FULL)
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun WidgetButton(label: String, action: String) {
        val context = androidx.glance.LocalContext.current
        Text(
            label,
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 20.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.clickable(actionStartService(PartyService.actionIntent(context, action), isForegroundService = true)),
        )
    }

    companion object {
        fun refresh(context: Context) {
            CoroutineScope(Dispatchers.Default).launch {
                if (GlanceAppWidgetManager(context).getGlanceIds(PartyWidget::class.java).isNotEmpty()) {
                    PartyWidget().updateAll(context)
                }
            }
        }
    }
}

class PartyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PartyWidget()
}
