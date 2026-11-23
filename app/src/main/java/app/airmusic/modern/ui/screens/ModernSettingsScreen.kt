package app.airmusic.modern.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import app.airmusic.modern.ui.AppThemeMode
import app.airmusic.modern.ui.ThemePreferences
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import app.airmusic.modern.BuildConfig
import app.airmusic.modern.model.GlobalSettingsState
import app.airmusic.modern.state.MultiRoomStateRepository

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModernSettingsScreen(
    settings: GlobalSettingsState,
    modifier: Modifier = Modifier
) {

    val context = LocalContext.current
    val themeMode by ThemePreferences.themeMode.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AppThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { ThemePreferences.set(context, mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = AppThemeMode.entries.size),
                                icon = {
                                    SegmentedButtonDefaults.Icon(active = themeMode == mode) {
                                        Icon(
                                            imageVector = when (mode) {
                                                AppThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                                                AppThemeMode.LIGHT -> Icons.Outlined.LightMode
                                                AppThemeMode.DARK -> Icons.Outlined.DarkMode
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                        )
                                    }
                                },
                                label = { Text(if (mode == AppThemeMode.SYSTEM) "System" else mode.label, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        }

        // Language and "streaming resilience" options were removed: the app has no translations yet,
        // adaptive bitrate could only fall back to an MP3 encoder that isn't bundled, and the UPnP
        // MediaServer toggle wasn't connected to anything.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Aerocast ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Streams your phone's sound to AirPlay, DLNA and other Wi-Fi speakers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
