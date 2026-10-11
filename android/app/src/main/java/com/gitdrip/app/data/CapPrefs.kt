package com.gitdrip.app.data

import android.content.Context

/** Daily commit cap (D1), stored in app preferences; the engine copy is kept in sync with the bridge `config-set` action. */
object CapPrefs {
    private const val FILE = "gitdrip_prefs"
    private const val KEY = "daily_cap"

    fun get(c: Context): Int = Limits.clampCap(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY, Limits.DEFAULT_CAP))
    fun set(c: Context, n: Int) { c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY, Limits.clampCap(n)).apply() }
}
