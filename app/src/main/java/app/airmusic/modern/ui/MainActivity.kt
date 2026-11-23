package app.airmusic.modern.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.airmusic.modern.model.AppUidFilterMode
import app.airmusic.modern.model.AudioEngineTelemetry
import app.airmusic.modern.model.GlobalSettingsState
import app.airmusic.modern.model.MediaMetadataState
import app.airmusic.modern.model.QuickConnectGroup
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.service.AudioCaptureForegroundService
import app.airmusic.modern.service.MediaSessionMetadataListener
import app.airmusic.modern.state.MultiRoomStateRepository
import app.airmusic.modern.ui.screens.MixerAndEqScreen
import app.airmusic.modern.ui.screens.ModernSettingsScreen
import app.airmusic.modern.ui.screens.ReceiversGridScreen
import kotlin.math.pow

data class InstalledMediaApp(val label: String, val packageName: String, val uid: Int)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val metadata by MediaSessionMetadataListener.metadataFlow.collectAsState()
            val telemetry by AudioCaptureForegroundService.telemetryFlow.collectAsState()
            val sessionConfig by AudioCaptureForegroundService.sessionConfigFlow.collectAsState()
            val receivers by MultiRoomStateRepository.receiversFlow.collectAsState()
            val groups by MultiRoomStateRepository.groupsFlow.collectAsState()
            val settings by MultiRoomStateRepository.settingsFlow.collectAsState()

            AirMusicModernTheme(paletteSeedArgb = metadata.paletteSeedArgb) {
                AdaptiveAirMusicAppShell(
                    metadata = metadata,
                    telemetry = telemetry,
                    receivers = receivers,
                    groups = groups,
                    settings = settings,
                    selectedUids = sessionConfig.targetAppUids,
                    filterMode = sessionConfig.uidFilterMode,
                    onUpdateUidFilter = { mode, uids ->
                        AudioCaptureForegroundService.updateUidFilter(mode, uids)
                    }
                )
            }
        }
    }
}

@Composable
fun AirMusicModernTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    paletteSeedArgb: Int? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val seedAccent = paletteSeedArgb?.let { Color(it) } ?: Color(0xFFFF5722)
    val animatedAccent by animateColorAsState(targetValue = seedAccent, label = "PaletteSeedAccent")

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = animatedAccent,
            secondary = Color(0xFF7C4DFF),
            surface = Color(0xFF1C1C1C),
            background = Color(0xFF121212)
        )
        else -> lightColorScheme(
            primary = animatedAccent,
            secondary = Color(0xFF7C4DFF),
            surface = Color(0xFFFFFFFF),
            background = Color(0xFFFAFAFA)
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveAirMusicAppShell(
    metadata: MediaMetadataState,
    telemetry: AudioEngineTelemetry,
    receivers: List<ReceiverDeviceState>,
    groups: List<QuickConnectGroup>,
    settings: GlobalSettingsState,
    selectedUids: Set<Int>,
    filterMode: AppUidFilterMode,
    onUpdateUidFilter: (AppUidFilterMode, Set<Int>) -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) }
    val navTabs = listOf("Receivers", "Mixer & 10-Band EQ", "App Isolation", "Settings")
    val connectedCount = receivers.count { it.isConnected }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useTabletNavigationRail = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("AirMusic Modern • $connectedCount Active Speakers")
                    }
                )
            },
            bottomBar = {
                Column {
                    NowCastingWaveformBar(metadata = metadata, telemetry = telemetry)
                    if (!useTabletNavigationRail) {
                        NavigationBar {
                            navTabs.forEachIndexed { index, label ->
                                NavigationBarItem(
                                    selected = activeTab == index,
                                    onClick = { activeTab = index },
                                    icon = {},
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useTabletNavigationRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        navTabs.forEachIndexed { index, label ->
                            NavigationRailItem(
                                selected = activeTab == index,
                                onClick = { activeTab = index },
                                icon = {},
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    when (activeTab) {
                        0 -> ReceiversGridScreen(receivers = receivers, groups = groups)
                        1 -> MixerAndEqScreen(receivers = receivers)
                        2 -> PerAppCaptureIsolationScreen(
                            telemetry = telemetry,
                            selectedUids = selectedUids,
                            filterMode = filterMode,
                            onUpdateUidFilter = onUpdateUidFilter
                        )
                        3 -> ModernSettingsScreen(settings = settings)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerAppCaptureIsolationScreen(
    telemetry: AudioEngineTelemetry,
    selectedUids: Set<Int>,
    filterMode: AppUidFilterMode,
    onUpdateUidFilter: (AppUidFilterMode, Set<Int>) -> Unit
) {
    val context = LocalContext.current

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            AudioCaptureForegroundService.startProjectionCapture(context, result.resultCode, data)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    val installedApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.uid != android.os.Process.myUid() }
            .take(16)
            .map { InstalledMediaApp(pm.getApplicationLabel(it).toString(), it.packageName, it.uid) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (telemetry.isCapturing) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (telemetry.isCapturing) "Live System Capture Active (44.1 kHz Stereo)" else "Capture Engine Idle",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "352-Sample Frames: ${telemetry.framesCaptured} • Peak Level: ${"%.1f".format(telemetry.latestRmsDb)} dBFS",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (telemetry.isCapturing) {
                                AudioCaptureForegroundService.stopCapture(context)
                            } else {
                                val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    needed.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                val allGranted = needed.all {
                                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                }
                                if (allGranted) {
                                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                                    mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
                                } else {
                                    permissionLauncher.launch(needed.toTypedArray())
                                }
                            }
                        }
                    ) {
                        Text(if (telemetry.isCapturing) "Stop Audio Capture" else "Start Internal Audio Capture")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Per-App Audio Isolation (addMatchingUid / excludeUid)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppUidFilterMode.entries.forEach { mode ->
                            FilterChip(
                                selected = filterMode == mode,
                                onClick = { onUpdateUidFilter(mode, selectedUids) },
                                label = { Text(mode.name.replace('_', ' ')) }
                            )
                        }
                    }
                    if (filterMode != AppUidFilterMode.CAPTURE_ALL_MEDIA) {
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            installedApps.forEach { app ->
                                val isSelected = app.uid in selectedUids
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val updated = if (isSelected) selectedUids - app.uid else selectedUids + app.uid
                                        onUpdateUidFilter(filterMode, updated)
                                    },
                                    label = { Text(app.label) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NowCastingWaveformBar(
    metadata: MediaMetadataState,
    telemetry: AudioEngineTelemetry
) {
    val barColor = metadata.paletteSeedArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = metadata.title, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(text = metadata.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Canvas(
                modifier = Modifier
                    .height(28.dp)
                    .fillMaxWidth(0.35f)
            ) {
                val normalized = 10.0f.pow(telemetry.latestRmsDb / 20.0f).coerceIn(0.05f, 1.0f)
                val barCount = 16
                val step = size.width / barCount
                for (i in 0 until barCount) {
                    val factor = if (telemetry.isCapturing) {
                        ((i % 5 + 1) / 5f) * normalized
                    } else {
                        0.1f
                    }
                    val barHeight = (size.height * factor).coerceAtLeast(4f)
                    val top = (size.height - barHeight) / 2f
                    drawLine(
                        color = barColor,
                        start = Offset(i * step + step / 2f, top),
                        end = Offset(i * step + step / 2f, top + barHeight),
                        strokeWidth = 6f
                    )
                }
            }
        }
    }
}
