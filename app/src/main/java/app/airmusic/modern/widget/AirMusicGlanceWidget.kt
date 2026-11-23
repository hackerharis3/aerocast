package app.airmusic.modern.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.airmusic.modern.service.MediaSessionMetadataListener
import app.airmusic.modern.state.MultiRoomStateRepository

/**
 * Jetpack Glance Material 3 Home Screen Widget (Android 11 / API 30+ compatible)
 * providing 1-tap Quick-Connect speaker group toggling and master volume controls.
 */
class AirMusicGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }

    @Composable
    private fun WidgetContent() {
        val metadata = MediaSessionMetadataListener.metadataFlow.value
        val receivers = MultiRoomStateRepository.receiversFlow.value
        val activeReceivers = receivers.filter { it.isConnected }
        val defaultGroup = MultiRoomStateRepository.groupsFlow.value.firstOrNull()

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surfaceVariant)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Aerocast • ${if (activeReceivers.isNotEmpty()) "${activeReceivers.size} Speakers Active" else "Idle"}",
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = "${metadata.title} — ${metadata.artist}",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(10.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    text = if (activeReceivers.isNotEmpty()) "Disconnect All" else "Quick-Connect (${defaultGroup?.name ?: "Group"})",
                    onClick = actionRunCallback<ToggleQuickConnectGroupAction>()
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Button(
                    text = "Vol -10%",
                    onClick = actionRunCallback<VolumeDownWidgetAction>()
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Button(
                    text = "Vol +10%",
                    onClick = actionRunCallback<VolumeUpWidgetAction>()
                )
            }
        }
    }
}

class ToggleQuickConnectGroupAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val active = MultiRoomStateRepository.receiversFlow.value.any { it.isConnected }
        if (active) {
            MultiRoomStateRepository.disconnectAllReceivers()
        } else {
            MultiRoomStateRepository.groupsFlow.value.firstOrNull()?.let { group ->
                MultiRoomStateRepository.activateQuickConnectGroup(group)
            }
        }
        AirMusicGlanceWidget().update(context, glanceId)
    }
}

class VolumeDownWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MultiRoomStateRepository.setMasterVolumeDelta(-10)
        AirMusicGlanceWidget().update(context, glanceId)
    }
}

class VolumeUpWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MultiRoomStateRepository.setMasterVolumeDelta(+10)
        AirMusicGlanceWidget().update(context, glanceId)
    }
}
