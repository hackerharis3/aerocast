package app.airmusic.modern.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val label: String, val nightMode: Int) {
    SYSTEM("System default", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES)
}

/**
 * Stores the user's theme choice and applies it through AppCompat night mode, so the whole app
 * (Compose's isSystemInDarkTheme(), the window background and the status bar) follows it.
 */
object ThemePreferences {
    private const val PREFS = "ui_prefs"
    private const val KEY_THEME = "theme_mode"

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    /** Call from Application.onCreate so the saved theme applies before any screen is drawn. */
    fun applySaved(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)
        val mode = AppThemeMode.entries.firstOrNull { it.name == saved } ?: AppThemeMode.SYSTEM
        _themeMode.value = mode
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    fun set(context: Context, mode: AppThemeMode) {
        if (mode == _themeMode.value) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }
}
