package at.agar.zielpopup

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kleines Protokoll der letzten Ereignisse (für „Was hat der Leser gesehen?“) */
object EventLog {
    private const val KEY = "event_log"
    private const val MAX = 25

    fun add(ctx: Context, msg: String) {
        try {
            val p = ctx.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.GERMAN).format(Date())
            val old = p.getString(KEY, "") ?: ""
            val lines = ("$time  $msg\n" + old).lines().filter { it.isNotBlank() }.take(MAX)
            p.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (_: Exception) {
        }
    }

    fun read(ctx: Context): String =
        ctx.getSharedPreferences("einstellungen", Context.MODE_PRIVATE).getString(KEY, null)
            ?.takeIf { it.isNotBlank() } ?: "noch keine Einträge"
}
