package at.agar.zielpopup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Testversion (Phase 1): Adresse eintippen -> Popup erscheint */
class MainActivity : Activity() {

    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var permButton: Button
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
        val pad = dp(16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Ziel-Popup – Version " + appVersion()
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Zieladresse eintippen und prüfen. Das Popup erscheint oben am Bildschirm und schließt sich nach 20 Sekunden oder beim Antippen."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        })
        input = EditText(this).apply {
            hint = "z. B. Karl-Gunsam-Gasse 1, 1110 Wien"
            setSingleLine(false)
        }
        root.addView(input)
        root.addView(Button(this).apply {
            text = "Ziel prüfen"
            setOnClickListener { runCheck() }
        })
        root.addView(Button(this).apply {
            text = "Verbindung testen"
            setOnClickListener { runDiagnose() }
        })
        permButton = Button(this).apply {
            text = "Popup-Berechtigung erteilen"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        root.addView(permButton)

        // ---------- Mystro-Leser ----------
        mystroStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(mystroStatus)
        root.addView(Button(this).apply {
            text = "Mystro-Leser ein-/ausschalten"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Was hat der Mystro-Leser gesehen?"
            setOnClickListener {
                val p = prefs()
                output.text = "Erkannte Zieladresse: " + (p.getString("last_dest", null) ?: "noch nichts") +
                        "\n\nGelesener Text:\n" + (p.getString("last_screen", null) ?: "noch nichts gelesen")
            }
        })
        // ---------- Google-Schlüssel ----------
        root.addView(TextView(this).apply {
            text = "Google-Schlüssel (optional, für viel mehr Lokale):"
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
        })
        keyInput = EditText(this).apply {
            hint = "AIza…"
            setSingleLine(true)
            setText(googleKey() ?: "")
        }
        root.addView(keyInput)
        root.addView(Button(this).apply {
            text = "Schlüssel speichern"
            setOnClickListener {
                prefs().edit().putString("google_key", keyInput.text.toString().trim()).apply()
                output.text = if (googleKey() != null) "✅ Google-Schlüssel gespeichert" else "Google-Schlüssel entfernt"
            }
        })

        output = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })
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
