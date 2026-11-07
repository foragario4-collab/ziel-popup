package at.agar.zielpopup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val time: Long, val input: String, val address: String, val kind: String, val source: String)

/** Die letzten geprüften Ziele (manuell oder von Mystro) */
object History {
    private const val KEY = "history"
    private const val MAX = 15

    fun add(ctx: Context, input: String, result: AnalysisResult, source: String) {
        if (result.title.startsWith("❓") || result.title.startsWith("⚠️")) return
        val kind = result.lines.firstOrNull { it.style == 1 }?.text ?: ""
        val item = HistoryItem(System.currentTimeMillis(), input, result.title.removePrefix("📍 "), kind, source)
        val list = ArrayList(all(ctx).filterNot { it.address == item.address })
        list.add(0, item)
        save(ctx, list.take(MAX))
    }

    fun all(ctx: Context): List<HistoryItem> {
        val raw = Prefs.get(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                HistoryItem(
                    o.optLong("t"), o.optString("i"), o.optString("a"),
                    o.optString("k"), o.optString("s")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        Prefs.get(ctx).edit().remove(KEY).apply()
    }

    private fun save(ctx: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        for (h in list) {
            arr.put(
                JSONObject().put("t", h.time).put("i", h.input).put("a", h.address)
                    .put("k", h.kind).put("s", h.source)
            )
        }
        Prefs.get(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
