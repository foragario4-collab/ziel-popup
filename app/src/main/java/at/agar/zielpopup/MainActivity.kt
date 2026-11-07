package at.agar.zielpopup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Hauptseite: Ziel prüfen · Zuletzt geprüft · Einstellungen */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var input: EditText
    private lateinit var resultBox: LinearLayout
    private lateinit var statusRow: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var settingsBox: LinearLayout

    private val orange = 0xFFFFC46B.toInt()
    private val iosGreen = 0xFF34C759.toInt()

    // ---------------------------------------------------------------- Aufbau

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmClient.init(applicationContext)
        GoogleClient.init(applicationContext)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val frame = FrameLayout(this)
        buildBackdrop(frame)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(64), dp(20), dp(56))
        }

        // Kopf
        root.addView(Glass.label(this, "Ziel", 38f, Glass.TEXT, bold = true))
        root.addView(Glass.label(this, "Was ist am Fahrtziel?", 16f), lp(dp(2)))
        statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(statusRow)
        }, lp(dp(16)))

        // Ziel prüfen
        root.addView(sectionTitle("Ziel prüfen"), lp(dp(28)))
        val searchCard = card(18)
        input = Glass.input(this, "Adresse oder Gasse, z. B. robertgasse 2", single = true).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runCheck(); true
                } else false
            }
        }
        searchCard.addView(input, lp(0))
        searchCard.addView(Glass.button(this, "Prüfen", primary = true) { runCheck() }, lp(dp(12)))
        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        searchCard.addView(resultBox, lp(dp(18)))
        root.addView(searchCard, lp(dp(8)))

        // Zuletzt geprüft
        root.addView(sectionTitle("Zuletzt geprüft"), lp(dp(28)))
        historyBox = card(4)
        root.addView(historyBox, lp(dp(8)))

        // Einstellungen
        root.addView(sectionTitle("Einstellungen"), lp(dp(28)))
        settingsBox = card(4)
        root.addView(settingsBox, lp(dp(8)))

        // Fußzeile
        root.addView(Glass.label(
            this, "Version ${appVersion()}\nDaten: Stadt Wien · OpenStreetMap · optional Google", 12f
        ).apply { gravity = Gravity.CENTER }, lp(dp(28)))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        }
        frame.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(frame)
        enableRealBlur(frame)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshHistory()
        refreshSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    // ---------------------------------------------------------------- Ziel prüfen

    private fun runCheck() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        hideKeyboard()
        haptic(input)
        resultBox.visibility = View.VISIBLE
        resultBox.removeAllViews()
        resultBox.addView(loadingRow())
        OverlayPopup.show(this, AnalysisResult("🔎 Suche läuft …", listOf(PopupLine(text, null))))
        val key = Prefs.activeKey(this)
        worker.execute {
            val result = try {
                Analyzer.analyze(text, key)
            } catch (e: Exception) {
                AnalysisResult(
                    "⚠️ Fehler bei der Suche",
                    listOf(PopupLine("${e.message ?: "Unbekannter Fehler"} – ist das Internet an?", null))
                )
            }
            History.add(this, text, result, "Manuell")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                resultBox.removeAllViews()
                resultBox.addView(Glass.label(this, result.title, 14f), lp(0))
                resultBox.addView(ResultView.lines(this, result), lp(dp(4)))
                OverlayPopup.show(this, result)
                refreshHistory()
                refreshSettings()
            }
        }
    }

    private fun loadingRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        })
        addView(Glass.label(this@MainActivity, "Suche läuft …", 15f), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
    }

    // ---------------------------------------------------------------- Status oben

    private fun refreshStatus() {
        statusRow.removeAllViews()
        addPill(if (mystroEnabled()) "Mystro-Leser an" else "Mystro-Leser aus",
            if (mystroEnabled()) Glass.GREEN else orange) { openAccessibility() }
        addPill(if (overlayOk()) "Popup erlaubt" else "Popup erlauben",
            if (overlayOk()) Glass.GREEN else orange) { openOverlay() }
        val googleText: String
        val googleColor: Int
        when {
            Prefs.rawKey(this) == null -> { googleText = "Nur OpenStreetMap"; googleColor = Glass.GREY }
            Prefs.googleOn(this) -> { googleText = "Google an"; googleColor = Glass.GREEN }
            else -> { googleText = "Google pausiert"; googleColor = Glass.GREY }
        }
        addPill(googleText, googleColor) { /* Einstellungen sind unten */ }
    }

    private fun addPill(text: String, dotColor: Int, onClick: () -> Unit) {
        val sb = SpannableStringBuilder("●  ").append(text)
        sb.setSpan(ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val pill = TextView(this).apply {
            this.text = sb
            textSize = 13f
            setTextColor(Glass.TEXT)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = Glass.pressable(this@MainActivity, 18, 0x1FFFFFFF)
            setOnClickListener { haptic(it); onClick() }
        }
        statusRow.addView(pill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })
    }

    // ---------------------------------------------------------------- Verlauf

    private fun refreshHistory() {
        historyBox.removeAllViews()
        val items = History.all(this)
        if (items.isEmpty()) {
            historyBox.addView(Glass.label(this, "Noch keine Ziele geprüft.", 15f).apply {
                setPadding(0, dp(14), 0, dp(14))
            })
            return
        }
        items.forEachIndexed { i, h ->
            if (i > 0) historyBox.addView(separator())
            val sub = h.address + " · " + timeText(h.time) + (if (h.source == "Mystro") " · Mystro" else "")
            historyBox.addView(row(h.kind.ifEmpty { h.address }, sub, chevron()) {
                input.setText(h.input)
                runCheck()
            })
        }
        historyBox.addView(separator())
        historyBox.addView(row("Verlauf löschen", null, null, danger = true) {
            History.clear(this)
            refreshHistory()
        })
    }

    private fun timeText(t: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = t }
        val now = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val fmt = if (sameDay) "HH:mm" else "dd.MM. HH:mm"
        return SimpleDateFormat(fmt, Locale.GERMAN).format(Date(t))
    }

    // ---------------------------------------------------------------- Einstellungen

    private fun refreshSettings() {
        settingsBox.removeAllViews()
        settingsBox.addView(row(
            "Mystro-Leser", "Liest das Fahrtziel automatisch aus Mystro",
            value(if (mystroEnabled()) "Ein" else "Aus")
        ) { openAccessibility() })
        settingsBox.addView(separator())
        settingsBox.addView(row(
            "Popup über anderen Apps", "Damit das Ergebnis über Uber erscheint",
            value(if (overlayOk()) "Erlaubt" else "Erlauben")
        ) { openOverlay() })
        settingsBox.addView(separator())

        val hasKey = Prefs.rawKey(this) != null
        val sw = Switch(this).apply {
            isChecked = hasKey && Prefs.googleOn(this@MainActivity)
            isEnabled = hasKey
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFE6E6EA.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(iosGreen, 0x55FFFFFF))
            setOnCheckedChangeListener { v, on ->
                haptic(v)
                Prefs.setGoogleOn(this@MainActivity, on)
                refreshStatus()
            }
        }
        settingsBox.addView(row(
            "Google Places",
            if (hasKey) "Mehr Lokale & Öffnungszeiten · aus = nur OpenStreetMap" else "Zuerst Schlüssel eintragen",
            sw, onClick = null
        ))
        settingsBox.addView(separator())
        settingsBox.addView(row(
            "Google-Schlüssel", if (hasKey) "Eingetragen" else "Nicht eingetragen", chevron()
        ) { keyDialog() })
        settingsBox.addView(separator())
        settingsBox.addView(row(
            "Google-Nutzung",
            "Heute ${GoogleClient.usedToday()} von ${GoogleClient.DAILY_LIMIT} · Monat ${GoogleClient.usedMonth()} von ca. 1.000 gratis",
            null, onClick = null
        ))
        settingsBox.addView(separator())
        settingsBox.addView(row("Verbindung testen", "Prüft alle Datenquellen", chevron()) { diagnoseDialog() })
        settingsBox.addView(separator())
        settingsBox.addView(row("Was hat der Leser gesehen?", "Zuletzt aus Mystro gelesener Text", chevron()) {
            readerDialog()
        })
    }

    // ---------------------------------------------------------------- Dialoge

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun keyDialog() {
        val field = EditText(this).apply {
            setText(Prefs.rawKey(this@MainActivity) ?: "")
            hint = "AIza…"
            setSingleLine(true)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(field)
        }
        dialog()
            .setTitle("Google-Schlüssel")
            .setMessage("Wird nur auf diesem Handy gespeichert.")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                Prefs.setKey(this, field.text.toString())
                if (Prefs.rawKey(this) != null) Prefs.setGoogleOn(this, true)
                refreshStatus(); refreshSettings()
            }
            .setNeutralButton("Löschen") { _, _ ->
                Prefs.setKey(this, null)
                refreshStatus(); refreshSettings()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun diagnoseDialog() {
        val d = dialog()
            .setTitle("Verbindung testen")
            .setMessage("Teste alle Datenquellen …")
            .setPositiveButton("OK", null)
            .show()
        val key = Prefs.activeKey(this)
        val hasRaw = Prefs.rawKey(this) != null
        worker.execute {
            val lines = try {
                val list = ArrayList(OsmClient.diagnose())
                if (key == null) {
                    list.add(if (hasRaw) "⏸ Google: pausiert" else "➖ Google: kein Schlüssel (nur OpenStreetMap)")
                } else {
                    list.add(
                        try {
                            val n = GoogleClient.nearby(key, 48.2085, 16.3731, 60.0).size
                            "✅ Google ($n Orte am Stephansplatz)"
                        } catch (e: Exception) {
                            "❌ ${e.message ?: "Google-Fehler"}"
                        }
                    )
                }
                list
            } catch (e: Exception) {
                listOf("Test abgebrochen: ${e.message ?: e.javaClass.simpleName}")
            }
            runOnUiThread {
                if (d.isShowing) d.setMessage(lines.joinToString("\n"))
                refreshSettings()
            }
        }
    }

    private fun readerDialog() {
        val p = Prefs.get(this)
        val msg = "Erkannte Zieladresse:\n" + (p.getString("last_dest", null) ?: "noch nichts") +
                "\n\nGelesener Text:\n" + (p.getString("last_screen", null) ?: "noch nichts gelesen")
        dialog().setTitle("Mystro-Leser").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- Bausteine

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun card(padV: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(padV), dp(18), dp(padV))
        background = Glass.surface(this@MainActivity, 26)
    }

    private fun sectionTitle(s: String) = Glass.label(this, s.uppercase(), 13f).apply {
        letterSpacing = 0.06f
        setPadding(dp(6), 0, 0, 0)
    }

    private fun separator() = View(this).apply {
        background = ColorDrawable(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun chevron() = Glass.label(this, "›", 24f)

    private fun value(s: String) = Glass.label(this, "$s  ›", 16f)

    private fun row(
        title: String,
        subtitle: String?,
        trailing: View?,
        danger: Boolean = false,
        onClick: (() -> Unit)?
    ): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Glass.label(this, title, 17f, if (danger) 0xFFFF8A80.toInt() else Glass.TEXT))
        if (subtitle != null) texts.addView(Glass.label(this, subtitle, 13f), lp(dp(2)))
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            r.addView(trailing, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(12) })
        }
        if (onClick != null) {
            r.isClickable = true
            r.background = RippleDrawable(
                ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(Color.WHITE)
            )
            r.setOnClickListener { haptic(it); onClick() }
        }
        return r
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    // ---------------------------------------------------------------- Hintergrund

    private fun buildBackdrop(frame: FrameLayout) {
        frame.background = Glass.backdrop()
        val blobs = listOf(
            Triple(0xCC6D5BFF.toInt(), 460, Pair(-140, -40)),
            Triple(0xAA00C2FF.toInt(), 420, Pair(120, 320)),
            Triple(0x99FF5FA2.toInt(), 400, Pair(-120, 640))
        )
        for ((color, size, pos) in blobs) {
            val b = Glass.blob(this, color, size)
            b.translationX = dp(pos.first).toFloat()
            b.translationY = dp(pos.second).toFloat()
            frame.addView(b)
        }
    }

    /** Ab Android 12: der Handy-Hintergrund scheint verschwommen durch (echtes Glas) */
    private fun enableRealBlur(frame: FrameLayout) {
        if (Build.VERSION.SDK_INT < 31) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        if (!wm.isCrossWindowBlurEnabled) return
        window.setBackgroundBlurRadius(dp(70))
        frame.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0x990B1026.toInt(), 0x802A1B5E.toInt(), 0x730E4A5C)
        )
    }

    // ---------------------------------------------------------------- System

    private fun mystroEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return list.contains("$packageName/")
    }

    private fun overlayOk() = Settings.canDrawOverlays(this)

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
