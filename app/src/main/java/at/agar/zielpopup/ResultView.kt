package at.agar.zielpopup

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zeichnet ein Ergebnis – gleich im Popup und in der App.
 *  Kopf:  was ist es (groß) · Adresse · Uhrzeit
 *  Liste: LED links · Kategorie · Name + Meter · Öffnungszeiten · Trennstrich
 * Sortieren und Filtern nach den Einstellungen passiert hier.
 */
object ResultView {
    private const val MAX_ROWS = 60

    // ---------------------------------------------------------------- Kopf

    fun header(ctx: Context, r: AnalysisResult): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(ctx).apply {
            text = r.headline ?: r.title
            textSize = 21f
            setTextColor(Glass.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        if (r.headline != null) {
            if (r.title.isNotBlank()) box.addView(small(ctx, r.title.removePrefix("📍 "), 14.5f, Glass.TEXT_SOFT, 3))
            box.addView(small(ctx, timeText(r), 13.5f, Glass.TEXT_SOFT, 2))
        }
        return box
    }

    private fun timeText(r: AnalysisResult): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.GERMAN)
        val eta = r.etaMin
        return if (eta != null && eta > 0) {
            "Ankunft ca. ${fmt.format(Date(r.time + eta * 60_000L))} · in $eta Min."
        } else {
            "Geprüft ${fmt.format(Date(r.time))}"
        }
    }

    // ---------------------------------------------------------------- Liste

    fun body(ctx: Context, r: AnalysisResult): LinearLayout {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Betriebe genau an der Adresse: der erste ist schon oben groß genannt -> nur Status
        r.exact.forEachIndexed { i, row ->
            if (i == 0) {
                list.addView(statusOnly(ctx, row), lp(ctx, 10))
            } else {
                list.addView(line(ctx, 0x26FFFFFF), thin(ctx, 10))
                list.addView(rowView(ctx, row, null), lp(ctx, 0))
            }
        }

        // Suche läuft, Fehler, Adresse nicht erkannt: nur Hinweise, keine Liste
        if (r.headline == null || r.headline.startsWith("❓")) {
            for (n in r.notes) list.addView(small(ctx, n, 14f, Glass.TEXT_SOFT, 6))
            return list
        }

        val (shown, hidden) = visible(ctx, r)

        // kräftiger Strich zwischen Kopf und Liste
        list.addView(line(ctx, 0x4DFFFFFF), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(2).coerceAtLeast(2)
        ).apply { topMargin = ctx.dp(12) })

        val section = if (r.streetMode) "In der Gasse · ${shown.size} Orte" else "In der Nähe · bis ${r.radius} m"
        list.addView(small(ctx, section.uppercase(), 12f, Glass.TEXT_SOFT, 10).apply { letterSpacing = 0.08f })

        when {
            r.nearby.isEmpty() -> list.addView(small(ctx,
                if (r.streetMode) "Wohngegend – keine Lokale in der Gasse gefunden" else "Nichts im Umkreis von ${r.radius} m",
                15f, Glass.TEXT_SOFT, 8))
            shown.isEmpty() -> list.addView(small(ctx,
                "Alle ${r.nearby.size} Orte sind durch deine Filter ausgeblendet", 15f, Glass.TEXT_SOFT, 8))
            else -> shown.take(MAX_ROWS).forEachIndexed { i, row ->
                if (i > 0) list.addView(line(ctx, 0x33FFFFFF), thin(ctx, 0))
                list.addView(rowView(ctx, row, where(row, r.streetMode)), lp(ctx, 0))
            }
        }
        val extra = (shown.size - MAX_ROWS).coerceAtLeast(0)
        if (extra > 0) list.addView(small(ctx, "+ $extra weitere", 13f, Glass.TEXT_SOFT, 6))
        if (hidden > 0 && shown.isNotEmpty()) {
            list.addView(small(ctx, "$hidden ausgeblendet durch Filter", 12.5f, Glass.TEXT_SOFT, 8))
        }
        for (n in r.notes) list.addView(small(ctx, n, 13f, Glass.TEXT_SOFT, 8))
        return list
    }

    /** Filter + Sortierung aus den Einstellungen; liefert (angezeigt, Anzahl ausgeblendet) */
    fun visible(ctx: Context, r: AnalysisResult): Pair<List<PlaceRow>, Int> {
        val off = Prefs.catsOff(ctx)
        val onlyOpen = Prefs.onlyOpen(ctx)
        val showUnknown = Prefs.showUnknown(ctx)
        val filtered = r.nearby.filter { row ->
            (row.catKey == null || row.catKey !in off) && when (row.state) {
                OpenState.OPEN -> true
                OpenState.UNKNOWN -> showUnknown
                OpenState.CLOSED -> !onlyOpen
            }
        }
        val near: Comparator<PlaceRow> = if (r.streetMode) {
            compareBy<PlaceRow>({ houseNum(it.houseNo) }, { it.name.lowercase() })
        } else {
            compareBy<PlaceRow> { it.dist ?: Int.MAX_VALUE }
        }
        val sorted = if (Prefs.openFirst(ctx)) {
            filtered.sortedWith(compareBy<PlaceRow> { stateRank(it.state) }.then(near))
        } else {
            filtered.sortedWith(near)
        }
        return sorted to (r.nearby.size - filtered.size)
    }

    private fun stateRank(s: OpenState) = when (s) {
        OpenState.OPEN -> 0
        OpenState.UNKNOWN -> 1
        OpenState.CLOSED -> 2
    }

    private fun houseNum(h: String?): Int = h?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: Int.MAX_VALUE

    private fun where(row: PlaceRow, streetMode: Boolean): String? =
        if (!streetMode) row.dist?.let { "$it m" } else row.houseNo?.let { "Nr. $it" }

    // ---------------------------------------------------------------- Bausteine

    /** Ein Eintrag: LED links, rechts drei Zeilen */
    private fun rowView(ctx: Context, row: PlaceRow, where: String?): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(11), 0, ctx.dp(11))
        }
        r.addView(Glass.dot(ctx, Glass.ledColor(row.state), 13), LinearLayout.LayoutParams(ctx.dp(13), ctx.dp(13)).apply {
            rightMargin = ctx.dp(14)
        })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = row.head
            textSize = 13f
            setTextColor(Glass.TEXT_SOFT)
        })
        texts.addView(TextView(ctx).apply {
            text = if (where != null) "${row.name} · $where" else row.name
            textSize = 16.5f
            setTextColor(Glass.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, ctx.dp(1), 0, ctx.dp(1))
        })
        texts.addView(TextView(ctx).apply {
            text = row.info
            textSize = 13.5f
            setTextColor(Glass.TEXT_SOFT)
        })
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    /** Nur Status (für den Ort, der oben schon groß steht) */
    private fun statusOnly(ctx: Context, row: PlaceRow): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(Glass.dot(ctx, Glass.ledColor(row.state), 13), LinearLayout.LayoutParams(ctx.dp(13), ctx.dp(13)).apply {
            rightMargin = ctx.dp(14)
        })
        r.addView(TextView(ctx).apply {
            text = row.info
            textSize = 15f
            setTextColor(Glass.TEXT)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun small(ctx: Context, s: String, size: Float, color: Int, topDp: Int) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        setPadding(0, ctx.dp(topDp), 0, 0)
    }

    private fun line(ctx: Context, color: Int) = View(ctx).apply { background = ColorDrawable(color) }

    private fun thin(ctx: Context, topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(1).coerceAtLeast(1)
    ).apply { topMargin = ctx.dp(topDp) }

    private fun lp(ctx: Context, topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = ctx.dp(topDp) }
}
