package com.irregular.xenopowermeter

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irregular.xenopowermeter.data.model.RangeMode

object AppSettings {
    private const val PREF_NAME = "app_settings"
    private const val KEY_LANGUAGE = "language_name"
    private const val KEY_LANGUAGE_LEGACY = "language"
    private const val KEY_COLOR_MODE = "color_mode_name"
    private const val KEY_COLOR_MODE_LEGACY = "color_mode"
    private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_RANGE = "range_name"
    private const val KEY_RANGE_LEGACY = "range"

    enum class Language { SYSTEM, CHINESE, ENGLISH, JAPANESE }
    enum class ColorMode { SYSTEM, LIGHT, DARK }

    private var prefs: SharedPreferences? = null

    var language by mutableStateOf(Language.SYSTEM)
        private set
    var colorMode by mutableStateOf(ColorMode.SYSTEM)
        private set
    var notificationEnabled by mutableStateOf(true)
        private set
    var autoConnect by mutableStateOf(true)
        private set
    var range by mutableStateOf(RangeMode.AUTO)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        language = loadEnum(KEY_LANGUAGE, KEY_LANGUAGE_LEGACY, Language.values(), Language.SYSTEM)
        colorMode = loadEnum(KEY_COLOR_MODE, KEY_COLOR_MODE_LEGACY, ColorMode.values(), ColorMode.SYSTEM)
        notificationEnabled = prefs?.getBoolean(KEY_NOTIFICATION_ENABLED, true) ?: true
        autoConnect = prefs?.getBoolean(KEY_AUTO_CONNECT, true) ?: true
        range = loadEnum(KEY_RANGE, KEY_RANGE_LEGACY, RangeMode.values(), RangeMode.AUTO)
    }

    /** Persist enums by name so inserting/reordering entries can't shift saved values. */
    private fun <T : Enum<T>> loadEnum(key: String, legacyKey: String, values: Array<T>, default: T): T {
        val name = prefs?.getString(key, null)
        if (name != null) {
            return values.firstOrNull { it.name == name } ?: default
        }
        val legacyOrdinal = prefs?.getInt(legacyKey, -1) ?: -1
        return values.getOrNull(legacyOrdinal) ?: default
    }

    /**
     * Context override carrying the in-app language, used for hot language
     * switching: string lookups resolve immediately without recreating the
     * activity. The system per-app locale stays in sync via applyLocale() at
     * the next cold start.
     */
    fun localizedContext(base: Context): Context {
        val locale = when (language) {
            Language.SYSTEM -> return base
            Language.CHINESE -> java.util.Locale.SIMPLIFIED_CHINESE
            Language.ENGLISH -> java.util.Locale.ENGLISH
            Language.JAPANESE -> java.util.Locale.JAPANESE
        }
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocales(android.os.LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    fun updateLanguage(lang: Language) {
        language = lang
        prefs?.edit()?.putString(KEY_LANGUAGE, lang.name)?.remove(KEY_LANGUAGE_LEGACY)?.apply()
    }

    fun updateColorMode(mode: ColorMode) {
        colorMode = mode
        prefs?.edit()?.putString(KEY_COLOR_MODE, mode.name)?.remove(KEY_COLOR_MODE_LEGACY)?.apply()
    }

    fun updateNotificationEnabled(enabled: Boolean) {
        notificationEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_NOTIFICATION_ENABLED, enabled)?.apply()
    }

    fun updateAutoConnect(enabled: Boolean) {
        autoConnect = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_CONNECT, enabled)?.apply()
    }

    fun updateRange(mode: RangeMode) {
        range = mode
        prefs?.edit()?.putString(KEY_RANGE, mode.name)?.remove(KEY_RANGE_LEGACY)?.apply()
    }
}
