package at.agar.zielpopup

import android.content.Context
import android.content.SharedPreferences

/** Alle Einstellungen an einer Stelle */
object Prefs {
    private const val NAME = "einstellungen"

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun rawKey(ctx: Context): String? = get(ctx).getString("google_key", null)?.takeIf { it.isNotBlank() }
    fun setKey(ctx: Context, key: String?) {
        get(ctx).edit().putString("google_key", key?.trim() ?: "").apply()
    }

    fun googleOn(ctx: Context): Boolean = get(ctx).getBoolean("google_on", true)
    fun setGoogleOn(ctx: Context, on: Boolean) {
        get(ctx).edit().putBoolean("google_on", on).apply()
    }

    /** Schlüssel nur, wenn Google eingeschaltet ist */
    fun activeKey(ctx: Context): String? = if (googleOn(ctx)) rawKey(ctx) else null

    // ---------- Anzeige im Popup ----------

    /** false = nach Nähe, true = geöffnete zuerst, dann nach Nähe */
    fun openFirst(ctx: Context): Boolean = get(ctx).getBoolean("sort_open_first", false)
    fun setOpenFirst(ctx: Context, on: Boolean) = get(ctx).edit().putBoolean("sort_open_first", on).apply()

    fun onlyOpen(ctx: Context): Boolean = get(ctx).getBoolean("only_open", false)
    fun setOnlyOpen(ctx: Context, on: Boolean) = get(ctx).edit().putBoolean("only_open", on).apply()

    fun showUnknown(ctx: Context): Boolean = get(ctx).getBoolean("show_unknown", true)
    fun setShowUnknown(ctx: Context, on: Boolean) = get(ctx).edit().putBoolean("show_unknown", on).apply()

    const val RADIUS_MIN = 10
    const val RADIUS_MAX = 100
    fun radius(ctx: Context): Int = get(ctx).getInt("radius", 25).coerceIn(RADIUS_MIN, RADIUS_MAX)
    fun setRadius(ctx: Context, m: Int) = get(ctx).edit().putInt("radius", m.coerceIn(RADIUS_MIN, RADIUS_MAX)).apply()

    /** Ausgeschaltete Kategorien (Standard: alle an) */
    fun catsOff(ctx: Context): Set<String> = get(ctx).getStringSet("cats_off", emptySet()) ?: emptySet()
    fun setCatOn(ctx: Context, key: String, on: Boolean) {
        val set = HashSet(catsOff(ctx))
        if (on) set.remove(key) else set.add(key)
        get(ctx).edit().putStringSet("cats_off", set).apply()
    }
}
