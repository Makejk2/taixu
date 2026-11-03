package top.wkbin.taixu.core.datastore

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first

/** Narrow, atomic allowlisted settings facade; never exposes approval/permission keys. */
class AgentConfigPreferences(private val store: SettingsDataStore) {
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorEnabledKey = booleanPreferencesKey("dynamic_color_enabled")
    private val appFontScaleKey = floatPreferencesKey("app_font_scale")
    private val terminalFontSizeKey = intPreferencesKey("terminal_font_size")
    private val terminalHapticsEnabledKey = booleanPreferencesKey("terminal_haptics_enabled")
    /** Agent configuration allowlist; never maps permission or approval preferences. */
    private fun agentConfigValue(prefs: androidx.datastore.preferences.core.Preferences, key: String): String = when (key) {
        "appearance.theme" -> prefs[themeModeKey] ?: "system"
        "appearance.dynamic_color" -> (prefs[dynamicColorEnabledKey] ?: true).toString()
        "appearance.font_scale" -> (prefs[appFontScaleKey] ?: 1.0f).coerceIn(0.8f, 1.3f).toString()
        "terminal.font_size" -> (prefs[terminalFontSizeKey] ?: 13).toString()
        "terminal.haptics" -> (prefs[terminalHapticsEnabledKey] ?: true).toString()
        else -> error("Unsupported agent config key")
    }

    suspend fun readAgentConfig(key: String): String = agentConfigValue(store.appearanceDataStore.data.first(), key)

    /** Compare and write atomically against manual UI writes and other sessions. */
    suspend fun compareAndSetAgentConfig(key: String, expected: String, value: String): Boolean {
        var changed = false
        store.appearanceDataStore.edit { prefs ->
            if (agentConfigValue(prefs, key) == expected) {
                when (key) {
                    "appearance.theme" -> { require(value in listOf("system", "light", "dark")); prefs[themeModeKey] = value }
                    "appearance.dynamic_color" -> prefs[dynamicColorEnabledKey] = value.toBooleanStrict()
                    "appearance.font_scale" -> { val v = value.toFloat(); require(v.isFinite() && v in 0.8f..1.3f); prefs[appFontScaleKey] = v }
                    "terminal.font_size" -> { val v = value.toInt(); require(v in 10..24); prefs[terminalFontSizeKey] = v }
                    "terminal.haptics" -> prefs[terminalHapticsEnabledKey] = value.toBooleanStrict()
                    else -> error("Unsupported agent config key")
                }
                changed = true
            }
        }
        return changed
    }

}
