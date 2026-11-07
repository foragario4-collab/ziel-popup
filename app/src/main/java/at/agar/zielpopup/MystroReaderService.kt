package at.agar.zielpopup

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bedienungshilfe: Liest NUR Bildschirme von Mystro.
 * Sobald eine neue Zieladresse erscheint, wird sie geprüft und als Popup angezeigt.
 */
class MystroReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastDest: String? = null
    private var lastTime = 0L
    private var busy = false
    private val readRunnable = Runnable { readNow() }

    private fun isMystro(pkg: CharSequence?) = pkg?.toString()?.contains("mystro", ignoreCase = true) == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        OsmClient.init(applicationContext)
        GoogleClient.init(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isMystro(event.packageName) && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // kurz warten, bis Mystro fertig gezeichnet hat
        handler.removeCallbacks(readRunnable)
        handler.postDelayed(readRunnable, 700)
    }

    override fun onInterrupt() {}

    private fun readNow() {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            // alle sichtbaren Fenster, auch ein Mystro-Fenster über Uber
            for (w in windows) {
                val r = w.root ?: continue
                if (isMystro(r.packageName)) roots.add(r)
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { if (isMystro(it.packageName)) roots.add(it) }
        }
        if (roots.isEmpty()) return

        val texts = ArrayList<String>()
        for (r in roots) collect(r, texts, 0)
        if (texts.isEmpty()) return

        val prefs = getSharedPreferences("einstellungen", MODE_PRIVATE)
        val dest = MystroParser.findDestination(texts)
        prefs.edit()
            .putString("last_screen", texts.joinToString("\n").take(4000))
            .putString("last_dest", dest ?: "(keine Adresse erkannt)")
            .apply()
        if (dest == null) return

        val now = System.currentTimeMillis()
        if (dest == lastDest && now - lastTime < 10 * 60 * 1000) return // gleiche Fahrt nicht doppelt
        if (busy) return
        lastDest = dest
        lastTime = now
        busy = true

        OverlayPopup.show(this, AnalysisResult("🔎 Ziel: $dest", emptyList()))
        val key = if (prefs.getBoolean("google_on", true)) prefs.getString("google_key", null) else null
        Thread {
            val res = try {
                Analyzer.analyze(dest, key)
            } catch (e: Exception) {
                AnalysisResult("⚠️ Fehler bei der Suche", listOf(PopupLine(e.message ?: "", null)))
            }
            handler.post {
                busy = false
                OverlayPopup.show(this, res)
            }
        }.start()
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 40) return
        val text = n.text?.toString()
        if (!text.isNullOrBlank()) {
            out.add(text)
        } else {
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }
}
