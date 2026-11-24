package com.nexos.play.util

import android.content.Context

object Prefs {
    private const val FILE = "nexosplay"
    private const val KEY_BACKEND = "backend_url"

    fun backendUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND, "").orEmpty()

    fun setBackendUrl(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKEND, value).apply()
    }
}
