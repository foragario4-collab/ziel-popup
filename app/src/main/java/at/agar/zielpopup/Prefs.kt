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
}
