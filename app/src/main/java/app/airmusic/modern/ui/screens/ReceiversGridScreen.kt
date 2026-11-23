package app.airmusic.modern.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Edit
import app.airmusic.modern.AirMusicRuntimeCoordinator
import app.airmusic.modern.service.AudioCaptureForegroundService
import app.airmusic.modern.ui.components.QrStreamShareSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
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
    var showListenSheet by remember { mutableStateOf(false) }
    // null = closed; GROUP_NEW = creating; otherwise the group being edited
    var editingGroup by remember { mutableStateOf<QuickConnectGroup?>(null) }
    val telemetry by AudioCaptureForegroundService.telemetryFlow.collectAsState()

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
                onAddManualAirPlay = { showAddManualAirPlayDialog = true },
                onListenInBrowser = { showListenSheet = true },
                onNewGroup = { editingGroup = GROUP_NEW },
                onEditGroup = { editingGroup = it }
            )
        }

        if (receivers.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No speakers found yet. Aerocast scans your Wi-Fi for AirPlay, Cast, " +
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

    editingGroup?.let { group ->
        GroupEditorDialog(
            group = group.takeIf { it !== GROUP_NEW },
            receivers = receivers,
            onDismiss = { editingGroup = null },
            onSave = { name, ids ->
                MultiRoomStateRepository.saveGroup(group.takeIf { it !== GROUP_NEW }?.id, name, ids)
                editingGroup = null
            },
            onDelete = {
                MultiRoomStateRepository.deleteGroup(group.id)
                editingGroup = null
            }
        )
    }

    if (showListenSheet) {
        QrStreamShareSheet(
            listenUrl = AirMusicRuntimeCoordinator.ktorServer.buildQrWebListenUrl(),
            isCapturing = telemetry.isCapturing,
            onDismiss = { showListenSheet = false }
        )
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
    onAddManualAirPlay: () -> Unit,
    onListenInBrowser: () -> Unit,
    onNewGroup: () -> Unit,
    onEditGroup: (QuickConnectGroup) -> Unit
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
                TextButton(onClick = onNewGroup) {
                    Text("+ New group", maxLines = 1)
                }
                TextButton(onClick = onAddManualAirPlay) {
                    Text("+ Manual AirPlay", maxLines = 1)
                }
                TextButton(onClick = onListenInBrowser) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Listen in browser", maxLines = 1)
                }
                if (activeReceiverIds.isNotEmpty()) {
                    TextButton(onClick = onDisconnectAll) {
                        Text("Disconnect All (${activeReceiverIds.size})", maxLines = 1)
                    }
                }
            }
            if (groups.isEmpty()) {
                Text(
                    text = "No speaker groups yet. Tap + New group to play several speakers with one tap.",
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ElevatedFilterChip(
                            selected = isGroupActive,
                            onClick = { onSelectGroup(group) },
                            label = { Text("${group.name} (${group.receiverIds.size})") }
                        )
                        IconButton(onClick = { onEditGroup(group) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit ${group.name}", modifier = Modifier.size(18.dp))
                        }
                    }
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
                                text = receiver.activeCodec.displayName,
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
                        Text(if (receiver.isExpanded) "Hide controls" else "Controls")
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

                    // Multi-room sync: hold this speaker back so it lines up with slower ones.
                    // ("Auto-Chirp" mic calibration stays hidden until it really measures.)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sync delay: ${receiver.delayOffsetMs} ms",
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (receiver.delayOffsetMs != 0) {
                            TextButton(onClick = { MultiRoomStateRepository.setReceiverDelayOffset(receiver.id, 0) }) {
                                Text("Reset")
                            }
                        }
                    }
                    Slider(
                        value = receiver.delayOffsetMs.toFloat(),
                        onValueChange = { MultiRoomStateRepository.setReceiverDelayOffset(receiver.id, it.roundToInt()) },
                        valueRange = 0f..MultiRoomStateRepository.MAX_SYNC_DELAY_MS.toFloat()
                    )
                    Text(
                        text = "Playing on several speakers and one is ahead? Add delay to that one until they line up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Per-Speaker Audio Codec Profile & Stereo-to-Mono Downmix
                    Text(
                        text = "Audio format",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Only show a format choice when there is more than one real option.
                        if (receiver.protocol.supportedCodecs.size > 1) receiver.protocol.supportedCodecs.forEach { codec ->
                            FilterChip(
                                selected = receiver.activeCodec == codec,
                                onClick = { MultiRoomStateRepository.setReceiverCodec(receiver.id, codec) },
                                label = { Text(codec.displayName) }
                            )
                        }
                        FilterChip(
                            selected = receiver.stereoToMono,
                            onClick = { MultiRoomStateRepository.toggleStereoToMono(receiver.id) },
                            label = { Text(if (receiver.stereoToMono) "Mono" else "Stereo") }
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
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("7000") }
    val ipLooksValid = ip.trim().matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add AirPlay speaker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("e.g. Bedroom speaker") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP address") },
                    placeholder = { Text("e.g. 192.168.1.50") },
                    isError = ip.isNotBlank() && !ipLooksValid,
                    singleLine = true
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Port (usually 7000 or 5000)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                enabled = ipLooksValid,
                onClick = {
                    val port = portText.toIntOrNull() ?: 7000
                    // AirPlay 2 /auth-setup isn't implemented, so it is never requested.
                    onConfirm(name.trim(), ip.trim(), port, false)
                }
            ) {
                Text("Add & connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Marker for "create a new group" in the editor state. */
private val GROUP_NEW = QuickConnectGroup(id = "", name = "", receiverIds = emptySet())

@Composable
private fun GroupEditorDialog(
    group: QuickConnectGroup?,
    receivers: List<ReceiverDeviceState>,
    onDismiss: () -> Unit,
    onSave: (name: String, receiverIds: Set<String>) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(group?.name ?: "") }
    // Keeps members that aren't currently discovered, so editing never drops them silently.
    var selected by remember { mutableStateOf(group?.receiverIds ?: emptySet()) }
    val missing = selected.count { id -> receivers.none { it.id == id } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (group == null) "New speaker group" else "Edit group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group name") },
                    placeholder = { Text("e.g. Living room + kitchen") },
                    singleLine = true
                )
                if (receivers.isEmpty()) {
                    Text(
                        "No speakers found yet. Groups can include any speaker Aerocast has found.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    receivers.forEach { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = if (r.id in selected) selected - r.id else selected + r.id },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = r.id in selected, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(r.displayTitle, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    r.protocol.badgeTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (missing > 0) {
                    Text(
                        "$missing speaker(s) in this group are not on the network right now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = selected.isNotEmpty(), onClick = { onSave(name, selected) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (group != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
