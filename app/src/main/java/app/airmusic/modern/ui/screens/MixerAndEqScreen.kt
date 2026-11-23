package app.airmusic.modern.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.airmusic.modern.model.EqPreset
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.state.MultiRoomStateRepository

/**
 * Phase 2 Multi-Room Master Mixer & Per-Speaker 10-Band Parametric/Graphic Equalizer Cards
 * powered by Android's DynamicsProcessing API (Android 11 / API 30+).
 */
@Composable
fun MixerAndEqScreen(
    receivers: List<ReceiverDeviceState>,
    modifier: Modifier = Modifier
) {
    val connectedReceivers = receivers.filter { it.isConnected }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            MasterGroupBalanceCard(connectedReceivers = connectedReceivers)
        }

        items(items = receivers, key = { "eq_${it.id}" }) { receiver ->
            SpeakerTenBandEqCard(receiver = receiver)
        }
    }
}

@Composable
private fun MasterGroupBalanceCard(connectedReceivers: List<ReceiverDeviceState>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "All speakers (${connectedReceivers.size} connected)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Change the volume of every connected speaker at once.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { MultiRoomStateRepository.setMasterVolumeDelta(-10) },
                    label = { Text("All −10%", maxLines = 1) }
                )
                AssistChip(
                    onClick = { MultiRoomStateRepository.setMasterVolumeDelta(+10) },
                    label = { Text("All +10%", maxLines = 1) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakerTenBandEqCard(receiver: ReceiverDeviceState) {
    val curveColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (receiver.eqEnabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${receiver.displayTitle} • 10-Band EQ & Limiter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${receiver.protocol.badgeTitle} • Preset: ${receiver.eqPreset.displayName} • Limiter: ${if (receiver.limiterEnabled) "Active" else "Bypassed"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = receiver.eqEnabled,
                    onCheckedChange = { MultiRoomStateRepository.toggleReceiverEq(receiver.id, it) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Preset Chips + DynamicsProcessing Hardware Limiter Toggle
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EqPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = receiver.eqPreset == preset && receiver.eqEnabled,
                        onClick = { MultiRoomStateRepository.applyEqPreset(receiver.id, preset) },
                        label = { Text(preset.displayName) }
                    )
                }
                FilterChip(
                    selected = receiver.limiterEnabled,
                    onClick = { MultiRoomStateRepository.toggleReceiverLimiter(receiver.id, !receiver.limiterEnabled) },
                    label = { Text(if (receiver.limiterEnabled) "HW Limiter: ON" else "HW Limiter: OFF") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live Frequency Response Curve Canvas (-12 dB to +12 dB across 31 Hz .. 16 kHz)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .padding(horizontal = 8.dp)
            ) {
                val midY = size.height / 2f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, midY),
                    end = Offset(size.width, midY),
                    strokeWidth = 2f
                )

                val bands = receiver.eqBands
                if (bands.isNotEmpty()) {
                    val stepX = size.width / (bands.size - 1).coerceAtLeast(1)
                    val path = Path()
                    bands.forEachIndexed { idx, band ->
                        val x = idx * stepX
                        val normGain = (band.gainDb / 12.0f).coerceIn(-1f, 1f)
                        val y = midY - (normGain * (size.height * 0.42f))
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        drawCircle(color = curveColor, radius = 5.5f, center = Offset(x, y))
                    }
                    drawPath(path = path, color = curveColor, style = Stroke(width = 4f))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // 10 ISO Octave Band Sliders (31 Hz, 62 Hz, 125 Hz, 250 Hz, 500 Hz, 1k, 2k, 4k, 8k, 16k)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                receiver.eqBands.forEach { band ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${band.label} Hz",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(0.18f),
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = band.gainDb,
                            onValueChange = { MultiRoomStateRepository.setEqBandGain(receiver.id, band.index, it) },
                            valueRange = -12f..12f,
                            modifier = Modifier.weight(0.64f)
                        )
                        Text(
                            text = "${"%+.1f".format(band.gainDb)} dB",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(0.18f)
                        )
                    }
                }
            }
        }
    }
}
