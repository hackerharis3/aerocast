package app.airmusic.modern.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.airmusic.modern.model.AudioCodecProfile
import app.airmusic.modern.model.QuickConnectGroup
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.state.MultiRoomStateRepository
import kotlin.math.roundToInt

/**
 * Phase 2 Adaptive Material 3 Receivers Grid replacing AirMusic's legacy SinksFragment (h2.c1),
 * fragment_sinks.xml, fragment_sinks_grid.xml, and view_sink.xml.
 */
@Composable
fun ReceiversGridScreen(
    receivers: List<ReceiverDeviceState>,
    groups: List<QuickConnectGroup>,
    modifier: Modifier = Modifier
) {
    var showAddManualAirPlayDialog by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 330.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            QuickConnectGroupsBanner(
                groups = groups,
                activeReceiverIds = receivers.filter { it.isConnected }.map { it.id }.toSet(),
                onSelectGroup = { MultiRoomStateRepository.activateQuickConnectGroup(it) },
                onDisconnectAll = { MultiRoomStateRepository.disconnectAllReceivers() },
                onAddManualAirPlay = { showAddManualAirPlayDialog = true }
            )
        }

        if (receivers.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No speakers found yet. AirMusic scans your Wi-Fi for AirPlay, Cast, " +
                        "Sonos, DLNA, HEOS, Roku and Snapcast devices. Make sure this phone is on " +
                        "the same network, or add an AirPlay speaker manually.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }

        items(items = receivers, key = { it.id }) { receiver ->
            ExpandableReceiverCard(receiver = receiver)
        }
    }

    if (showAddManualAirPlayDialog) {
        ManualAirPlayDeviceDialog(
            onDismiss = { showAddManualAirPlayDialog = false },
            onConfirm = { name, ip, port, authSetup ->
                MultiRoomStateRepository.addManualAirPlayReceiver(name, ip, port, authSetup)
                showAddManualAirPlayDialog = false
            }
        )
    }
}

@Composable
private fun QuickConnectGroupsBanner(
    groups: List<QuickConnectGroup>,
    activeReceiverIds: Set<String>,
    onSelectGroup: (QuickConnectGroup) -> Unit,
    onDisconnectAll: () -> Unit,
    onAddManualAirPlay: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Quick-Connect Speaker Groups",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onAddManualAirPlay) {
                    Text("+ Manual AirPlay", maxLines = 1)
                }
                if (activeReceiverIds.isNotEmpty()) {
                    TextButton(onClick = onDisconnectAll) {
                        Text("Disconnect All (${activeReceiverIds.size})", maxLines = 1)
                    }
                }
            }
            if (groups.isEmpty()) {
                Text(
                    text = "No speaker groups yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEach { group ->
                    val isGroupActive = group.receiverIds.isNotEmpty() &&
                        activeReceiverIds.containsAll(group.receiverIds)
                    ElevatedFilterChip(
                        selected = isGroupActive,
                        onClick = { onSelectGroup(group) },
                        label = { Text("${group.name} (${group.receiverIds.size})") }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpandableReceiverCard(receiver: ReceiverDeviceState) {
    val containerColor = if (receiver.isConnected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = receiver.protocol.badgeTitle,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "${receiver.activeCodec.displayName} • ${receiver.delayOffsetMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = receiver.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${receiver.ipAddress}:${receiver.port}" +
                            if (receiver.eqEnabled) " • 10-Band EQ (${receiver.eqPreset.displayName})" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Switch(
                        checked = receiver.isConnected,
                        onCheckedChange = { MultiRoomStateRepository.toggleReceiverConnection(receiver.id) }
                    )
                    TextButton(
                        onClick = { MultiRoomStateRepository.toggleReceiverExpanded(receiver.id) }
                    ) {
                        Text(if (receiver.isExpanded) "Hide Controls" else "Mixer & Sync")
                    }
                }
            }

            AnimatedVisibility(visible = receiver.isExpanded || receiver.isConnected) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    // Inline Volume Slider + Mute Toggle + Step Buttons (Replaces view_sink_volume.xml)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (receiver.isMuted) "Volume: Muted" else "Volume: ${receiver.volumePercent}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = { MultiRoomStateRepository.toggleReceiverMute(receiver.id) },
                                label = { Text(if (receiver.isMuted) "Unmute" else "Mute") }
                            )
                            AssistChip(
                                onClick = { MultiRoomStateRepository.setReceiverVolume(receiver.id, receiver.volumePercent - 5) },
                                label = { Text("-5%") }
                            )
                            AssistChip(
                                onClick = { MultiRoomStateRepository.setReceiverVolume(receiver.id, receiver.volumePercent + 5) },
                                label = { Text("+5%") }
                            )
                        }
                    }

                    Slider(
                        value = receiver.volumePercent.toFloat(),
                        onValueChange = { MultiRoomStateRepository.setReceiverVolume(receiver.id, it.roundToInt()) },
                        valueRange = 0f..100f
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Multi-Room Sync Delay Offset (-5000 ms to +5000 ms) + Mic Auto-Chirp Calibration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Multi-Room Sync Delay: ${receiver.delayOffsetMs} ms",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { MultiRoomStateRepository.setReceiverDelayOffset(receiver.id, 0) }) {
                                Text("Reset 0ms")
                            }
                            OutlinedButton(
                                onClick = { MultiRoomStateRepository.runAutoChirpCalibration(receiver.id) }
                            ) {
                                Text("Auto-Chirp Sync")
                            }
                        }
                    }

                    Slider(
                        value = receiver.delayOffsetMs.toFloat(),
                        onValueChange = { MultiRoomStateRepository.setReceiverDelayOffset(receiver.id, it.roundToInt()) },
                        valueRange = -2000f..2000f
                    )

                    // Per-Speaker Audio Codec Profile & Stereo-to-Mono Downmix
                    Text(
                        text = "Streaming Codec & Channel Mode",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        receiver.protocol.supportedCodecs.forEach { codec ->
                            FilterChip(
                                selected = receiver.activeCodec == codec,
                                onClick = { MultiRoomStateRepository.setReceiverCodec(receiver.id, codec) },
                                label = { Text(codec.displayName) }
                            )
                        }
                        FilterChip(
                            selected = receiver.stereoToMono,
                            onClick = { MultiRoomStateRepository.toggleStereoToMono(receiver.id) },
                            label = { Text(if (receiver.stereoToMono) "Mono Downmix: ON" else "Stereo 2.0") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualAirPlayDeviceDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, ip: String, port: Int, requiresAuthSetup: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("Bedroom AirPlay Speaker") }
    var ip by remember { mutableStateOf("192.168.1.120") }
    var portText by remember { mutableStateOf("7000") }
    var requiresAuthSetup by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Manual AirPlay Receiver") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Speaker Alias") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IPv4 / IPv6 Address") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("RTSP Port (Default 5000 or 7000)") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AirPlay 2 /auth-setup Handshake")
                    Switch(checked = requiresAuthSetup, onCheckedChange = { requiresAuthSetup = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val port = portText.toIntOrNull() ?: 7000
                onConfirm(name, ip, port, requiresAuthSetup)
            }) {
                Text("Add & Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
