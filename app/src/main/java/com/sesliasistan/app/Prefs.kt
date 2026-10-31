package com.sesliasistan.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = (sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        set(v) = sp.edit().putString("model", v.trim()).apply()

    var assistantName: String
        get() = (sp.getString("name", "Harun") ?: "Harun").ifBlank { "Harun" }
        set(v) = sp.edit().putString("name", v.trim()).apply()

    var autoListen: Boolean
        get() = sp.getBoolean("auto_listen", true)
        set(v) = sp.edit().putBoolean("auto_listen", v).apply()

    var speak: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    var confirmSms: Boolean
        get() = sp.getBoolean("confirm_sms", true)
        set(v) = sp.edit().putBoolean("confirm_sms", v).apply()

    var wakeWord: Boolean
        get() = sp.getBoolean("wake_word", false)
        set(v) = sp.edit().putBoolean("wake_word", v).apply()

    companion object {
        // "latest" takma adı Google yeni model çıkardıkça otomatik güncellenir
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
    }
}
