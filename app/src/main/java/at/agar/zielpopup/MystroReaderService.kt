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
    private var pending = false
    private var lastRead = 0L
    private val readRunnable = Runnable {
        pending = false
        lastRead = System.currentTimeMillis()
        readNow()
    }

    private fun isMystro(pkg: CharSequence?) = pkg?.toString()?.contains("mystro", ignoreCase = true) == true

    private var lastHello = 0L
    private var lastLog = 0L
    private val seenApps = LinkedHashSet<String>()

    private fun prefs() = getSharedPreferences("einstellungen", MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        OsmClient.init(applicationContext)
        GoogleClient.init(applicationContext)
        prefs().edit().putLong("svc_connected", System.currentTimeMillis()).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // Protokoll für die Fehlersuche: welche Apps hat der Leser zuletzt gesehen?
        if (pkg.isNotEmpty() && pkg != packageName) {
            seenApps.remove(pkg); seenApps.add(pkg)
            while (seenApps.size > 8) seenApps.remove(seenApps.first())
            val now = System.currentTimeMillis()
            if (now - lastLog > 2000) {
                lastLog = now
                prefs().edit()
                    .putLong("evt_time", now)
                    .putString("evt_apps", seenApps.toList().asReversed().joinToString("\n"))
                    .apply()
            }
        }

        if (!isMystro(pkg) && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        // sichtbares Lebenszeichen: Mystro geöffnet -> kurzer Hinweis (höchstens alle 10 Minuten)
        if (isMystro(pkg) && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastHello > 10 * 60 * 1000 && !busy) {
                lastHello = now
                OverlayPopup.show(this, AnalysisResult("👀 Mystro-Leser aktiv", notes = listOf("Wartet auf eine Fahrt …")), 3000L)
            }
        }
        // Bei echten Fahrten ändert sich der Bildschirm pausenlos (Karte, Position, Countdown).
        // Darum NICHT jedes Mal neu warten, sondern spätestens alle 0,6 Sekunden sicher lesen.
        if (pending) return
        pending = true
        val sinceLast = System.currentTimeMillis() - lastRead
        handler.postDelayed(readRunnable, if (sinceLast > 1500) 400L else 600L)
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
        val prefs = prefs()
        if (roots.isEmpty()) {
            prefs.edit().putString("last_problem", "Mystro-Fenster nicht gefunden").apply()
            return
        }

        val texts = ArrayList<String>()
        for (r in roots) collect(r, texts, 0)
        if (texts.isEmpty()) {
            prefs.edit().putString("last_problem", "Mystro liefert keinen Text (Bildschirm leer)")
                .putLong("last_read_time", System.currentTimeMillis()).apply()
            return
        }
        val dest = MystroParser.findDestination(texts)
        prefs.edit()
            .putString("last_screen", texts.joinToString("\n").take(4000))
            .putString("last_dest", dest ?: "(keine Adresse erkannt)")
            .putLong("last_read_time", System.currentTimeMillis())
            .putString("last_problem", if (dest == null) "Kein Fahrt-Bildschirm (keine Zieladresse gefunden)" else "–")
            .apply()
        if (dest == null) return

        val now = System.currentTimeMillis()
        if (dest == lastDest && now - lastTime < 10 * 60 * 1000) return // gleiche Fahrt nicht doppelt
        // Sicherheitsnetz: hängt eine Suche länger als 40 Sekunden, nicht ewig blockieren
        if (busy && now - lastTime < 40_000) return
        lastDest = dest
        lastTime = now
        busy = true

        val eta = MystroParser.findEtaMinutes(texts)
        OverlayPopup.show(this, AnalysisResult("🔎 Ziel: $dest"))
        val key = Prefs.activeKey(this)
        val radius = Prefs.radius(this)
        Thread {
            var res = AnalysisResult("⚠️ Fehler bei der Suche", notes = listOf("Unbekannter Fehler"))
            try {
                res = Analyzer.analyze(dest, key, radius).copy(etaMin = eta)
            } catch (e: Throwable) {
                res = AnalysisResult("⚠️ Fehler bei der Suche", notes = listOf(e.message ?: e.javaClass.simpleName))
            }
            try { History.add(this, dest, res, "Mystro") } catch (_: Throwable) { }
            val finalRes = res
            handler.post {
                busy = false
                OverlayPopup.show(this, finalRes)
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
