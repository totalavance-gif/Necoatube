package com.nexos.play.util

import android.content.Context

object Prefs {
    private const val FILE = "nexosplay"
    private const val KEY_BACKEND = "backend_url"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_SPEED = "speed"
    private const val KEY_POSITIONS = "positions"

    fun backendUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND, "").orEmpty()

    fun setBackendUrl(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKEND, value).apply()
    }

    fun shuffle(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHUFFLE, false)

    fun setShuffle(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHUFFLE, value).apply()
    }

    fun darkMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, true)

    fun setDarkMode(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, value).apply()
    }

    fun applyTheme(ctx: Context) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (darkMode(ctx)) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun speed(ctx: Context): Float =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1f).takeIf { it > 0 } ?: 1f

    fun setSpeed(ctx: Context, value: Float) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, value).apply()
    }

    fun positions(ctx: Context): MutableMap<String, Long> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_POSITIONS, "").orEmpty()
        if (raw.isBlank()) return mutableMapOf()
        return runCatching {
            val o = org.json.JSONObject(raw)
            mutableMapOf<String, Long>().also { m ->
                o.keys().forEach { k -> m[k] = o.optLong(k) }
            }
        }.getOrDefault(mutableMapOf())
    }

    fun savePositions(ctx: Context, map: Map<String, Long>) {
        val o = org.json.JSONObject()
        map.toList().takeLast(100).forEach { (k, v) -> o.put(k, v) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_POSITIONS, o.toString()).apply()
    }
}
