package at.agar.zielpopup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
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
    private lateinit var statusBox: LinearLayout
    private lateinit var displayBox: LinearLayout
    private var lastResult: AnalysisResult? = null
    private lateinit var historyBox: LinearLayout
    private lateinit var settingsBox: LinearLayout

    private val accent = Glass.ACCENT

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
        root.addView(Glass.label(this, "Ziel", 36f, Glass.TEXT, bold = true).apply { letterSpacing = -0.02f })
        root.addView(Glass.label(this, "Was ist am Fahrtziel?", 16f), lp(dp(2)))
        // Status: feste Box, eine Zeile pro Status – nichts zum Seitwärts-Wischen
        statusBox = card(4)
        root.addView(statusBox, lp(dp(16)))

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

        // Anzeige im Popup: Sortieren, Filter, Umkreis, Kategorien
        root.addView(sectionTitle("Anzeige im Popup"), lp(dp(28)))
        displayBox = card(4)
        root.addView(displayBox, lp(dp(8)))
        buildDisplaySettings()

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
        animateIn(root)
    }

    /** Beim Öffnen: Abschnitte gleiten nacheinander weich von unten herein */
    private fun animateIn(root: LinearLayout) {
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(22).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(40L * i).setDuration(520)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
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
        OverlayPopup.show(this, AnalysisResult("🔎 Suche läuft …", notes = listOf(text)))
        val key = Prefs.activeKey(this)
        val radius = Prefs.radius(this)
        worker.execute {
            val result = try {
                Analyzer.analyze(text, key, radius)
            } catch (e: Exception) {
                AnalysisResult(
                    "⚠️ Fehler bei der Suche",
                    notes = listOf("${e.message ?: "Unbekannter Fehler"} – ist das Internet an?")
                )
            }
            History.add(this, text, result, "Manuell")
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                showResult(result)
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
            indeterminateTintList = ColorStateList.valueOf(accent)
        })
        addView(Glass.label(this@MainActivity, "Suche läuft …", 15f), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
    }

    // ---------------------------------------------------------------- Status oben

    private fun refreshStatus() {
        statusBox.removeAllViews()
        val mOn = mystroEnabled()
        statusRow("Mystro-Leser", if (mOn) "An" else "Aus", if (mOn) Glass.GREEN else Glass.ORANGE) { openAccessibility() }
        statusBox.addView(separator())
        val oOk = overlayOk()
        statusRow("Popup", if (oOk) "Erlaubt" else "Erlauben", if (oOk) Glass.GREEN else Glass.ORANGE) { openOverlay() }
        statusBox.addView(separator())
        when {
            Prefs.rawKey(this) == null -> statusRow("Google", "Nur OpenStreetMap", Glass.GREY, null)
            Prefs.googleOn(this) -> statusRow("Google", "An", Glass.GREEN, null)
            else -> statusRow("Google", "Pausiert", Glass.ORANGE, null)
        }
    }

    /** Eine Status-Zeile: LED · Name · Zustand (in der LED-Farbe) */
    private fun statusRow(name: String, state: String, color: Int, onClick: (() -> Unit)?) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        r.addView(Glass.dot(this, color, 12), LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(14) })
        r.addView(Glass.label(this, name, 16f, Glass.TEXT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Glass.label(this, if (onClick != null) "$state  ›" else state, 15f, color))
        if (onClick != null) {
            r.isClickable = true
            r.background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(Color.WHITE))
            r.setOnClickListener { haptic(it); onClick() }
            Glass.pressEffect(r, 0.98f)
        }
        statusBox.addView(r)
    }

    // ---------------------------------------------------------------- Ergebnis in der App

    private fun showResult(result: AnalysisResult) {
        lastResult = result
        resultBox.removeAllViews()
        resultBox.addView(ResultView.header(this, result), lp(0))
        resultBox.addView(ResultView.body(this, result), lp(dp(2)))
        resultBox.alpha = 0f
        resultBox.translationY = dp(12).toFloat()
        resultBox.animate().alpha(1f).translationY(0f).setDuration(420)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** Nach einer Änderung in "Anzeige im Popup" das letzte Ergebnis neu zeichnen */
    private fun redrawResult() {
        lastResult?.let { if (resultBox.visibility == View.VISIBLE) showResult(it) }
    }

    // ---------------------------------------------------------------- Anzeige im Popup

    private fun buildDisplaySettings() {
        displayBox.removeAllViews()

        // Sortieren
        displayBox.addView(Glass.label(this, "Sortieren nach", 13f).apply { setPadding(0, dp(12), 0, dp(8)) })
        val openFirst = Prefs.openFirst(this)
        displayBox.addView(GlassSegmented(this, listOf("Nähe", "Geöffnet zuerst"), if (openFirst) 1 else 0) { i ->
            Prefs.setOpenFirst(this, i == 1); redrawResult()
        }, lp(0))
        displayBox.addView(Glass.label(this, "„Geöffnet zuerst“ = offene Orte oben, danach nach Nähe", 12.5f).apply {
            setPadding(dp(2), dp(8), 0, dp(12))
        })
        displayBox.addView(separator())

        // Filter
        displayBox.addView(row("Nur geöffnete anzeigen", null, switch(Prefs.onlyOpen(this)) { on ->
            Prefs.setOnlyOpen(this, on); redrawResult()
        }, onClick = null))
        displayBox.addView(separator())
        displayBox.addView(row("Unklare (weiß) anzeigen", "Orte ohne bekannte Öffnungszeiten", switch(Prefs.showUnknown(this)) { on ->
            Prefs.setShowUnknown(this, on); redrawResult()
        }, onClick = null))
        displayBox.addView(separator())

        // Umkreis
        val radiusLabel = Glass.label(this, "${Prefs.radius(this)} m", 16f, Glass.TEXT)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        head.addView(Glass.label(this, "Umkreis", 17f, Glass.TEXT), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(radiusLabel)
        displayBox.addView(head)
        val step = 5
        displayBox.addView(SeekBar(this).apply {
            max = (Prefs.RADIUS_MAX - Prefs.RADIUS_MIN) / step
            progress = (Prefs.radius(this@MainActivity) - Prefs.RADIUS_MIN) / step
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1).coerceAtLeast(1), 0x33000000)
                setSize(dp(26), dp(26))
            }
            splitTrack = false
            setPadding(dp(4), dp(12), dp(4), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    radiusLabel.text = "${Prefs.RADIUS_MIN + p * step} m"
                    if (fromUser) sb?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Prefs.setRadius(this@MainActivity, Prefs.RADIUS_MIN + (sb?.progress ?: 0) * step)
                }
            })
        }, lp(0))
        displayBox.addView(Glass.label(this, "Gilt ab der nächsten Prüfung", 12.5f).apply { setPadding(0, 0, 0, dp(10)) })
        displayBox.addView(separator())

        // Kategorien, zwei Spalten
        displayBox.addView(Glass.label(this, "Kategorien", 13f).apply { setPadding(0, dp(12), 0, dp(4)) })
        val off = Prefs.catsOff(this)
        Cats.ALL.chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { idx, c ->
                line.addView(GlassChip(this, "${c.emoji} ${c.label}", c.key !in off) { on ->
                    Prefs.setCatOn(this@MainActivity, c.key, on)
                    redrawResult()
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx == 1) leftMargin = dp(8)
                })
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) })
            displayBox.addView(line, lp(dp(8)))
        }
        displayBox.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
    }

    private fun switch(checked: Boolean, onChange: (Boolean) -> Unit) = GlassSwitch(this).apply {
        setOn(checked)
        this.onChange = { on -> haptic(this); onChange(on) }
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
        val sw = GlassSwitch(this).apply {
            setOn(hasKey && Prefs.googleOn(this@MainActivity))
            isEnabled = hasKey
            onChange = { on ->
                haptic(this)
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

    /** Dialog im Glas-Look: dunkle Glasfläche, Knöpfe in der Akzentfarbe */
    private fun AlertDialog.glassy(): AlertDialog {
        window?.setBackgroundDrawable(InsetDrawable(
            Glass.surface(this@MainActivity, 28, 0xF2141626.toInt(), 0x80FFFFFF.toInt()), dp(16)
        ))
        for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            getButton(b)?.setTextColor(accent)
        }
        return this
    }

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
            .show().glassy()
    }

    private fun diagnoseDialog() {
        val d = dialog()
            .setTitle("Verbindung testen")
            .setMessage("Teste alle Datenquellen …")
            .setPositiveButton("OK", null)
            .show().glassy()
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
        dialog().setTitle("Mystro-Leser").setMessage(msg).setPositiveButton("OK", null).show().glassy()
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
            Glass.pressEffect(r, 0.98f)
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
            Triple(0xE63A5BFF.toInt(), 460, Pair(-150, -80)),
            Triple(0xD9B23CFF.toInt(), 420, Pair(170, 330)),
            Triple(0xD900C2A8.toInt(), 460, Pair(-150, 680))
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
            intArrayOf(0xB30A0B14.toInt(), 0x990D0F1E.toInt(), 0xB30A0B14.toInt())
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
