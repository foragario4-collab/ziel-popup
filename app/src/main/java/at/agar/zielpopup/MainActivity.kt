package at.agar.zielpopup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Testversion (Phase 1): Adresse eintippen -> Popup erscheint */
class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var permButton: View
    private lateinit var keyInput: EditText
    private lateinit var mystroStatus: TextView

    private fun mystroEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.contains(packageName + "/")
    }

    private fun prefs() = getSharedPreferences("einstellungen", MODE_PRIVATE)
    private fun googleKey(): String? = prefs().getString("google_key", null)?.takeIf { it.isNotBlank() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmClient.init(applicationContext)

        // Vollbild hinter Statusleiste, durchsichtige Leisten
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val pad = dp(18)
        val gap = dp(12)

        // Hintergrund: Farbverlauf + weich verschwommene Farbkugeln (der "Liquid"-Effekt)
        val frame = android.widget.FrameLayout(this).apply { background = Glass.backdrop() }
        val blobs = listOf(
            Triple(0x996D5BFF.toInt(), 320, Pair(-80, 40)),
            Triple(0x8800C2FF.toInt(), 280, Pair(180, 360)),
            Triple(0x77FF5FA2.toInt(), 260, Pair(-40, 640))
        )
        for ((color, size, pos) in blobs) {
            val b = Glass.blob(this, color, size)
            b.translationX = dp(pos.first).toFloat()
            b.translationY = dp(pos.second).toFloat()
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                b.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(160f, 160f, android.graphics.Shader.TileMode.DECAL)
                )
            } else {
                b.alpha = 0.45f
            }
            frame.addView(b)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(56), pad, dp(40))
        }
        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Glass.surface(this@MainActivity, 28)
        }
        fun LinearLayout.add(v: View, top: Int = gap) {
            addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = top })
        }

        // Kopf
        root.add(Glass.label(this, "Ziel-Popup", 32f, Glass.TEXT, bold = true), 0)
        root.add(Glass.label(this, "Version " + appVersion() + " · was ist am Ziel?", 14f), dp(2))

        // Karte 1: Suche
        val search = card()
        search.add(Glass.label(this, "Zieladresse", 13f), 0)
        input = Glass.input(this, "z. B. Karl-Gunsam-Gasse 1", single = false)
        search.add(input, dp(8))
        search.add(Glass.button(this, "Ziel prüfen", primary = true) { runCheck() })
        root.add(search, dp(24))

        // Karte 2: Mystro
        val mystro = card()
        mystroStatus = Glass.label(this, "", 16f, Glass.TEXT)
        mystro.add(mystroStatus, 0)
        mystro.add(Glass.button(this, "Mystro-Leser ein/aus") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        mystro.add(Glass.button(this, "Was hat der Leser gesehen?") {
            val p = prefs()
            output.text = "Erkannte Zieladresse: " + (p.getString("last_dest", null) ?: "noch nichts") +
                    "\n\nGelesener Text:\n" + (p.getString("last_screen", null) ?: "noch nichts gelesen")
        })
        permButton = Glass.button(this, "Popup-Berechtigung erteilen", primary = true) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        mystro.add(permButton)
        root.add(mystro, dp(16))

        // Karte 3: Einstellungen
        val settings = card()
        settings.add(Glass.label(this, "Google-Schlüssel (optional)", 13f), 0)
        keyInput = Glass.input(this, "AIza…", single = true).apply { setText(googleKey() ?: "") }
        settings.add(keyInput, dp(8))
        settings.add(Glass.button(this, "Schlüssel speichern") {
            prefs().edit().putString("google_key", keyInput.text.toString().trim()).apply()
            output.text = if (googleKey() != null) "✅ Google-Schlüssel gespeichert" else "Google-Schlüssel entfernt"
        })
        settings.add(Glass.button(this, "Verbindung testen") { runDiagnose() })
        root.add(settings, dp(16))

        // Ausgabe
        output = Glass.label(this, "", 15f, Glass.TEXT).apply {
            setTextIsSelectable(true)
            setPadding(dp(4), 0, dp(4), 0)
        }
        root.add(output, dp(20))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        frame.addView(scroll, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        permButton.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        mystroStatus.text = if (mystroEnabled()) "✅ Mystro-Leser ist eingeschaltet" else "❌ Mystro-Leser ist aus"
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun runDiagnose() {
        output.text = "Teste Verbindungen … (dauert bis zu 1 Minute)"
        Thread {
            val lines = try {
                val list = ArrayList(OsmClient.diagnose())
                val k = googleKey()
                if (k == null) {
                    list.add("➖ Google: kein Schlüssel eingetragen")
                } else {
                    list.add(
                        try {
                            val n = GoogleClient.nearby(k, 48.2085, 16.3731, 60.0).size
                            "✅ Google ($n Orte am Stephansplatz gefunden)"
                        } catch (e: Exception) {
                            "❌ Google\n     ${e.message ?: ""}"
                        }
                    )
                }
                list
            } catch (e: Exception) {
                listOf("Test abgebrochen: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
            runOnUiThread { output.text = lines.joinToString("\n") }
        }.start()
    }

    private fun runCheck() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        output.text = "Suche läuft …"
        OverlayPopup.show(this, AnalysisResult("🔎 Suche läuft …", listOf(PopupLine(text, null))))
        Thread {
            val result = try {
                Analyzer.analyze(text, googleKey())
            } catch (e: Exception) {
                AnalysisResult(
                    "⚠️ Fehler bei der Suche",
                    listOf(PopupLine("${e.message ?: "Unbekannter Fehler"} – ist das Internet an?", null))
                )
            }
            runOnUiThread {
                output.text = (listOf(result.title) + result.lines.map { it.text }).joinToString("\n")
                OverlayPopup.show(this, result)
            }
        }.start()
    }
}
