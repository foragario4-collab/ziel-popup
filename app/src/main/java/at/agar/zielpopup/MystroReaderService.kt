package at.agar.zielpopup

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bedienungshilfe: Liest NUR Bildschirme von Mystro.
 * Sobald eine neue Fahrt erscheint, wird das Ziel geprüft und als Popup angezeigt.
 *
 * Wichtig für den Schichtbetrieb: Hier darf NIE eine Ausnahme nach außen gelangen.
 * Stürzt eine Bedienungshilfe ab, schaltet Android sie ab – und dann geht für den Rest der Schicht nichts mehr.
 */
class MystroReaderService : AccessibilityService() {

    companion object {
        /** Läuft der Leser gerade wirklich? (für den Schicht-Check in der App) */
        @Volatile
        var instance: MystroReaderService? = null
            private set

        private const val SAME_TRIP_MS = 3 * 60 * 1000L   // gleiche Fahrt 3 Minuten lang nicht doppelt zeigen
        private const val BUSY_LIMIT_MS = 40_000L          // hängt eine Suche, nach 40 s trotzdem weitermachen
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastKey: String? = null        // Abholung + Ziel der zuletzt gezeigten Fahrt
    private var lastTime = 0L
    private var reopened = false               // Mystro seit dem letzten Popup neu geöffnet?
    private var busy = false
    private var busySince = 0L
    private var pending = false
    private var lastRead = 0L
    private var lastHello = 0L
    private var lastSkipLog: String? = null
    private var lastLog = 0L
    private val seenApps = LinkedHashSet<String>()

    private val readRunnable = Runnable {
        pending = false
        lastRead = System.currentTimeMillis()
        safely("Lesen") { readNow() }
    }

    private fun isMystro(pkg: CharSequence?) = pkg?.toString()?.contains("mystro", ignoreCase = true) == true

    private fun prefs() = getSharedPreferences("einstellungen", MODE_PRIVATE)

    /** Führt etwas aus und fängt JEDEN Fehler ab – der Leser darf nie abstürzen */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            try {
                EventLog.add(this, "FEHLER beim $what: ${e.javaClass.simpleName} ${e.message ?: ""}")
            } catch (_: Throwable) {
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        safely("Start") {
            OsmClient.init(applicationContext)
            GoogleClient.init(applicationContext)
            prefs().edit().putLong("svc_connected", System.currentTimeMillis()).apply()
            EventLog.add(this, "Leser gestartet")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        safely("Stopp") { EventLog.add(this, "Leser wurde von Android beendet") }
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        safely("Ereignis") { handleEvent(event) }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // Protokoll für die Fehlersuche: welche Apps hat der Leser zuletzt gesehen?
        if (pkg.isNotEmpty() && pkg != packageName) {
            seenApps.remove(pkg)
            seenApps.add(pkg)
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

        if (isMystro(pkg) && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Mystro wurde (wieder) geöffnet -> dieselbe Fahrt darf erneut angezeigt werden
            if (!OverlayPopup.isShowing()) reopened = true
            // sichtbares Lebenszeichen, höchstens alle 10 Minuten, nie über einem Ergebnis
            val now = System.currentTimeMillis()
            if (now - lastHello > 10 * 60 * 1000 && !busy && !OverlayPopup.isShowing()) {
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

    private fun readNow() {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            // alle sichtbaren Fenster, auch ein Mystro-Fenster über Uber
            for (w in windows) {
                val r = w.root ?: continue
                if (isMystro(r.packageName)) roots.add(r)
            }
        } catch (_: Throwable) {
        }
        if (roots.isEmpty()) {
            try {
                rootInActiveWindow?.let { if (isMystro(it.packageName)) roots.add(it) }
            } catch (_: Throwable) {
            }
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
        val trip = MystroParser.findTrip(texts)
        prefs.edit()
            .putString("last_screen", texts.joinToString("\n").take(4000))
            .putString("last_dest", trip?.dest ?: "(keine Adresse erkannt)")
            .putLong("last_read_time", System.currentTimeMillis())
            .putString("last_problem", if (trip == null) "Kein Fahrt-Bildschirm (keine Zieladresse gefunden)" else "–")
            .apply()
        if (trip == null) return
        process(trip, "Mystro")
    }

    /** Ab hier läuft alles gleich – egal ob echte Fahrt oder Schicht-Check */
    private fun process(trip: MystroParser.Trip, source: String) {
        val now = System.currentTimeMillis()
        val key = (trip.pickup ?: "") + " → " + trip.dest

        // Gleiche Fahrt nicht ständig neu zeigen – außer Mystro wurde neu geöffnet
        val reopenOk = reopened && now - lastTime > 5_000
        if (key == lastKey && now - lastTime < SAME_TRIP_MS && !reopenOk) return

        if (busy && now - busySince < BUSY_LIMIT_MS) {
            if (key != lastSkipLog) {
                lastSkipLog = key
                EventLog.add(this, "Fahrt erkannt, wartet (Suche läuft noch): ${trip.dest}")
            }
            return
        }
        EventLog.add(this, "Fahrt erkannt ($source): ${trip.pickup ?: "?"} → ${trip.dest}")
        lastKey = key
        lastTime = now
        reopened = false
        busy = true
        busySince = now

        // Sofort das Ziel zeigen – die Details kommen nach, sobald die Suche fertig ist
        OverlayPopup.show(this, AnalysisResult("🔎 Ziel: ${trip.dest}"))
        val apiKey = Prefs.activeKey(this)
        val radius = Prefs.radius(this)
        Thread {
            var res = AnalysisResult("⚠️ Fehler bei der Suche", notes = listOf("Unbekannter Fehler"))
            try {
                res = Analyzer.analyze(trip.dest, apiKey, radius).copy(etaMin = trip.etaMin)
            } catch (e: Throwable) {
                res = AnalysisResult(
                    "📍 ${trip.dest}",
                    notes = listOf("Suche fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
                )
                try { EventLog.add(this, "Suche fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") } catch (_: Throwable) { }
            }
            try { History.add(this, trip.dest, res, source) } catch (_: Throwable) { }
            val finalRes = res
            handler.post {
                busy = false
                safely("Anzeigen") { OverlayPopup.show(this, finalRes) }
            }
        }.start()
    }

    /** Schicht-Check: spielt eine Fahrt durch GENAU denselben Weg wie eine echte Mystro-Anfrage */
    fun selfTest(texts: List<String>) {
        handler.post {
            safely("Schicht-Check") {
                val trip = MystroParser.findTrip(texts)
                if (trip == null) {
                    EventLog.add(this, "Schicht-Check: kein Ziel erkannt")
                    OverlayPopup.show(this, AnalysisResult("⚠️ Schicht-Check", notes = listOf("Kein Ziel erkannt")))
                    return@safely
                }
                lastKey = null // Test darf immer angezeigt werden
                busy = false
                process(trip, "Schicht-Check")
            }
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 40) return
        try {
            val text = n.text?.toString()
            if (!text.isNullOrBlank()) {
                out.add(text)
            } else {
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
        } catch (_: Throwable) {
            // ein einzelnes kaputtes Element überspringen, Rest weiterlesen
        }
    }
}
