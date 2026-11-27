package com.nexos.play.util

import android.content.Context

object Prefs {
    private const val FILE = "nexosplay"
    private const val KEY_BACKEND = "backend_url"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_SPEED = "speed"
    private const val KEY_QUALITY = "quality_height"
    private const val KEY_PREBUFFER = "prebuffer_30"
    private const val KEY_POSITIONS = "positions"
    private const val KEY_UPDATE_CHECK = "update_check"

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

    fun darkMode(ctx: Context): Boolean = themeMode(ctx) != 0

    fun setDarkMode(ctx: Context, value: Boolean) {
        setThemeMode(ctx, if (value) 1 else 0)
    }

    /** 0 = claro, 1 = oscuro, 2 = negro OLED. Migra el booleano viejo. */
    fun themeMode(ctx: Context): Int {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.contains("theme_mode")) return p.getInt("theme_mode", 1)
        return if (p.getBoolean(KEY_DARK, true)) 1 else 0
    }

    fun setThemeMode(ctx: Context, value: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt("theme_mode", value).apply()
    }

    fun applyTheme(ctx: Context) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (themeMode(ctx) == 0) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
    }

    /** Llamar antes de setContentView en cada activity. */
    fun applyOverlay(activity: android.app.Activity) {
        if (themeMode(activity) == 2) {
            activity.setTheme(com.nexos.play.R.style.Theme_NexosPlay_Black)
        }
    }

    fun speed(ctx: Context): Float =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, 1f).takeIf { it > 0 } ?: 1f

    fun setSpeed(ctx: Context, value: Float) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, value).apply()
    }

    fun prebuffer30(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREBUFFER, true)

    fun setPrebuffer30(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREBUFFER, value).apply()
    }

    /** Altura preferida (720 por defecto: arranque rapido). */
    fun qualityHeight(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_QUALITY, 720).takeIf { it > 0 } ?: 720

    fun setQualityHeight(ctx: Context, value: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_QUALITY, value).apply()
    }

    fun podcastMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("podcast_mode", false)

    fun setPodcastMode(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("podcast_mode", value).apply()
    }

    fun skipSilence(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean("skip_silence", false)

    fun setSkipSilence(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("skip_silence", value).apply()
    }

    fun blockedChannels(ctx: Context): MutableSet<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet("blocked_channels", emptySet())
            ?.toMutableSet() ?: mutableSetOf()

    fun isBlocked(ctx: Context, key: String): Boolean =
        key.isNotBlank() && blockedChannels(ctx).contains(key)

    fun setBlocked(ctx: Context, key: String, blocked: Boolean) {
        if (key.isBlank()) return
        val set = blockedChannels(ctx)
        if (blocked) set.add(key) else set.remove(key)
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putStringSet("blocked_channels", set).apply()
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

    /** true si toca revisar update (máx. 1 vez al día); marca la revisión. */
    fun dueUpdateCheck(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_UPDATE_CHECK, 0)
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return false
        prefs.edit().putLong(KEY_UPDATE_CHECK, System.currentTimeMillis()).apply()
        return true
    }
}
