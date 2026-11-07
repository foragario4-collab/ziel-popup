package at.agar.zielpopup

import java.util.Calendar

data class PopupLine(val text: String, val state: OpenState?, val bold: Boolean = false)
data class AnalysisResult(val title: String, val lines: List<PopupLine>)

data class Category(val emoji: String, val label: String, val visit: String, val prio: Int)

private data class Candidate(val place: OsmPlace, val cat: Category, val state: OpenState, val dist: Int?)

object Analyzer {
    const val RADIUS_M = 25   // Umkreis um die Hausnummer (Adressen sind nie ganz genau, daher etwas mehr als 20 m)
    const val STREET_M = 20   // Streifen links/rechts der Gasse, wenn keine Hausnummer da ist
    const val MAX_LINES = 60   // praktisch alles anzeigen, das Popup scrollt
    private var key: String? = null

    private val FILTERS = listOf(
        """["amenity"~"^(bar|pub|biergarten|nightclub|restaurant|cafe|fast_food|place_of_worship|brothel|stripclub|swingerclub|casino|hospital|cinema|theatre)$"]""",
        """["shop"~"^(erotic|massage|tattoo|beauty)$"]""",
        """["leisure"~"^(fitness_centre|adult_gaming_centre)$"]""",
        """["tourism"~"^(hotel|hostel|guest_house)$"]""",
        """["massage"]"""
    )

    private val cache = HashMap<String, Pair<Long, AnalysisResult>>()
    private const val CACHE_MS = 20 * 60 * 1000L

    fun analyze(input: String, googleKey: String? = null): AnalysisResult {
        val cacheKey = input.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")
        cache[cacheKey]?.let { (time, res) ->
            if (System.currentTimeMillis() - time < CACHE_MS) return res
        }
        val res = analyzeFresh(input, googleKey)
        cache[cacheKey] = System.currentTimeMillis() to res
        return res
    }

    private fun analyzeFresh(input: String, googleKey: String?): AnalysisResult {
        key = googleKey?.trim()?.takeIf { it.isNotEmpty() }
        val parsed = AddressParser.parse(input)
        if (parsed.street.isBlank()) return AnalysisResult("❓ Adresse nicht erkannt", emptyList())

        // Ungenaue Eingabe in den offiziellen Straßennamen umwandeln ("karl gunsam" -> "Karl-Gunsam-Gasse")
        val official = try { OsmClient.wienResolveStreet(parsed.street) } catch (e: Exception) { null }
        val addr = if (official != null) parsed.copy(street = official) else parsed
        val shown = "📍 " + addr.street + (addr.rawNumber?.let { " $it" } ?: "")
        val r = search(addr)
        return AnalysisResult(r.title, listOf(PopupLine(shown, null)) + r.lines)
    }

    private fun search(addr: ParsedAddress): AnalysisResult {
        val rx = AddressParser.streetRegex(addr.street)
        return if (addr.houseNumbers.isEmpty()) {
            streetMode(addr, rx, "🛣️ Keine Hausnummer – ganze Gasse geprüft")
        } else {
            pointMode(addr, rx)
        }
    }

    // ---------- Mit Hausnummer ----------
    private fun pointMode(addr: ParsedAddress, rx: String): AnalysisResult {
        val q = "${addr.street} ${addr.rawNumber}, ${addr.postcode ?: ""} Wien".replace("  ", " ")
        // Zuerst das Adressverzeichnis der Stadt Wien, dann OpenStreetMap
        val pos = (try { OsmClient.wienPoint(addr.street, addr.rawNumber ?: "", addr.postcode) } catch (e: Exception) { null })
            ?: (try { OsmClient.geocode(q) } catch (e: Exception) { null })
            ?: return streetMode(addr, rx, "⚠️ Hausnummer nicht gefunden – ganze Gasse geprüft")
        val (lat, lon) = pos

        val numRx = "^(" + addr.houseNumbers.joinToString("|") + ")([^0-9].*)?$"
        // Nur im kleinen Umkreis suchen – ganz Wien zu durchsuchen war zu langsam (Timeout)
        val sb = StringBuilder("[out:json][timeout:15];(")
        for (f in FILTERS) sb.append("nwr(around:$RADIUS_M,$lat,$lon)$f;")
        sb.append("nwr(around:80,$lat,$lon)[\"addr:housenumber\"~\"$numRx\"][\"name\"];")
        sb.append(");out center tags;")

        var note: String? = null
        val places: List<OsmPlace> = run {
            // 1. Google (wenn Schlüssel eingetragen) – kennt fast alle Lokale
            val k = key
            if (k != null) {
                try {
                    return@run GoogleClient.nearby(k, lat, lon, (RADIUS_M + 20).toDouble())
                } catch (e: Exception) {
                    note = "⚠️ ${e.message}"
                }
            }
            // 2. Schnellsuche (Photon) – meist in 1–2 Sekunden fertig
            try {
                val near = OsmClient.fastNear(lat, lon, RADIUS_M + 20)
                // zusätzlich: was steht genau an der Adresse (z. B. Synagoge, Lokal im Haus)?
                val here = OsmClient.reverseAt(lat, lon)
                return@run (if (here != null) near + here else near).distinctBy { it.id }
            } catch (e: Exception) {
            }
            // 3. OpenStreetMap über Overpass, 4. Ersatzsuche über Nominatim
            try {
                OsmClient.overpass(sb.toString())
            } catch (e: Exception) {
                if (note == null) note = "ℹ️ Ersatzsuche (Overpass nicht erreichbar)"
                OsmClient.fallbackPoint(lat, lon, RADIUS_M + 20)
                    .filter { distanceM(lat, lon, it.lat, it.lon) <= RADIUS_M + 15 || it.tags.containsKey("addr:housenumber") }
            }
        }
        val now = Calendar.getInstance()
        val streetRegex = Regex(rx, RegexOption.IGNORE_CASE)

        val exact = places.filter { isExact(it, streetRegex, addr.houseNumbers) }
        val exactIds = exact.map { it.id }.toSet()

        val lines = ArrayList<PopupLine>()
        val title: String
        if (exact.isNotEmpty()) {
            val first = exact.first()
            val cat = classify(first.tags) ?: Category("📍", "Ort", "Besuch", 9)
            title = "🎯 Genaue Adresse: ${first.tags["name"] ?: cat.label}"
            for (p in exact) {
                val c = classify(p.tags) ?: Category("📍", "Ort", "Besuch", 9)
                val st = stateOf(p, now)
                lines.add(PopupLine("${c.emoji} ${p.tags["name"] ?: c.label} · ${c.label} · ${stateText(st)}${hoursText(p)}", st, bold = true))
            }
        } else {
            title = "🏠 Wohnadresse (vermutlich)"
        }

        val nearby = places
            .filter { it.id !in exactIds }
            .mapNotNull { p -> toCandidate(p, now, distanceM(lat, lon, p.lat, p.lon)) }
            .sortedWith(compareBy({ stateRank(it.state) }, { it.cat.prio }, { it.dist ?: 0 }))

        addCandidates(lines, nearby)
        if (exact.isEmpty() && nearby.isEmpty()) {
            lines.add(PopupLine("Nichts im Umkreis von $RADIUS_M m", null))
        }
        note?.let { lines.add(PopupLine(it, null)) }
        return AnalysisResult(title, lines)
    }

    // ---------- Ohne Hausnummer: ganze Gasse ----------
    private fun streetMode(addr: ParsedAddress, rx: String, title: String): AnalysisResult {
        val bbox = OsmClient.VIENNA_BBOX
        // Erst die Gasse finden, dann nur direkt daneben suchen (schnell)
        val sb = StringBuilder("[out:json][timeout:20];")
        sb.append("way[\"highway\"][\"name\"~\"$rx\",i]($bbox)->.g;(")
        for (f in FILTERS) {
            sb.append("nwr(around.g:$STREET_M)$f;")
            sb.append("nwr(around.g:60)[\"addr:street\"~\"$rx\",i]$f;")
        }
        sb.append(");out center tags;")

        val now = Calendar.getInstance()
        val streetRegex = Regex(rx, RegexOption.IGNORE_CASE)
        var note: String? = null
        val box: DoubleArray? by lazy { streetBox(addr) } // nur einmal abfragen
        val raw: List<OsmPlace> = run {
            // 1. Google (wenn Schlüssel eingetragen): Mitte der Gasse, Umkreis = halbe Länge
            val k = key
            if (k != null) {
                try {
                    val b = box
                        ?: return AnalysisResult("❓ Gasse nicht gefunden: ${addr.street}", emptyList())
                    val cLat = (b[0] + b[2]) / 2
                    val cLon = (b[1] + b[3]) / 2
                    val r = (distanceM(b[0], b[1], b[2], b[3]) / 2).coerceIn(40, 450)
                    return@run GoogleClient.nearby(k, cLat, cLon, r.toDouble())
                        .filter { p -> p.tags["addr:street"]?.let { streetRegex.matches(it) } ?: false }
                } catch (e: Exception) {
                    note = "⚠️ ${e.message}"
                }
            }
            // 2. Schnellsuche (Photon) entlang der Gasse
            try {
                val b = box
                    ?: return AnalysisResult("❓ Gasse nicht gefunden: ${addr.street}", emptyList())
                val cLat = (b[0] + b[2]) / 2
                val cLon = (b[1] + b[3]) / 2
                val r = (distanceM(b[0], b[1], b[2], b[3]) / 2).coerceIn(40, 600) + 20
                // Orte mit dieser Gasse als Adresse, und Orte ohne Adressangabe, die im Bereich der Gasse liegen
                return@run OsmClient.fastNear(cLat, cLon, r)
                    .filter { p ->
                        p.tags["addr:street"]?.let { streetRegex.matches(it) }
                            ?: (p.lat in b[0]..b[2] && p.lon in b[1]..b[3])
                    }
            } catch (e: Exception) {
            }
            // 3. Overpass, 4. Ersatzsuche über Nominatim
            try {
                OsmClient.overpass(sb.toString())
            } catch (e: Exception) {
                if (note == null) note = "ℹ️ Ersatzsuche (Overpass nicht erreichbar)"
                val b = box
                    ?: return AnalysisResult("❓ Gasse nicht gefunden: ${addr.street}", emptyList())
                OsmClient.fallbackBox(b[0], b[1], b[2], b[3])
                    .filter { p -> p.tags["addr:street"]?.let { streetRegex.matches(it) } ?: true }
            }
        }
        val list = raw
            .mapNotNull { toCandidate(it, now, null) }
            .sortedWith(compareBy({ stateRank(it.state) }, { it.cat.prio }, { it.place.tags["name"] ?: "" }))

        val lines = ArrayList<PopupLine>()
        addCandidates(lines, list)
        if (list.isEmpty()) lines.add(PopupLine("Wohngegend – keine Lokale in der Gasse gefunden", null))
        note?.let { lines.add(PopupLine(it, null)) }
        return AnalysisResult(title, lines)
    }

    // ---------- Hilfsfunktionen ----------
    private fun streetBox(addr: ParsedAddress): DoubleArray? =
        (try { OsmClient.wienStreetBox(addr.street, addr.postcode) } catch (e: Exception) { null })
            ?: (try {
                OsmClient.geocodeBox("${addr.street}, ${addr.postcode ?: ""} Wien".replace("  ", " "))
            } catch (e: Exception) { null })

    /** Google liefert "jetzt offen" direkt, OpenStreetMap nur Öffnungszeiten-Text */
    private fun stateOf(p: OsmPlace, now: Calendar): OpenState = when (p.tags["_open"]) {
        "1" -> OpenState.OPEN
        "0" -> OpenState.CLOSED
        else -> OpeningHours.state(p.tags["opening_hours"], now)
    }

    private fun addCandidates(lines: MutableList<PopupLine>, list: List<Candidate>) {
        for (c in list.take(MAX_LINES)) {
            val name = c.place.tags["name"] ?: c.cat.label
            val dist = c.dist?.let { "$it m, " } ?: ""
            val head = if (c.state == OpenState.CLOSED) "${c.cat.emoji} ${c.cat.label}: $name" // zu -> nur Info
                       else "${c.cat.emoji} ${c.cat.visit} möglich: $name"
            lines.add(PopupLine("$head ($dist${stateText(c.state)}${hoursText(c.place)})", c.state))
        }
        if (list.size > MAX_LINES) lines.add(PopupLine("+ ${list.size - MAX_LINES} weitere", null))
    }

    private fun stateRank(s: OpenState) = when (s) {
        OpenState.OPEN -> 0
        OpenState.UNKNOWN -> 1
        OpenState.CLOSED -> 2
    }

    private fun toCandidate(p: OsmPlace, now: Calendar, dist: Int?): Candidate? {
        val cat = classify(p.tags) ?: return null
        // Geschlossene Orte werden NICHT mehr versteckt: sie stehen unten, rot, mit Öffnungszeiten
        return Candidate(p, cat, stateOf(p, now), dist)
    }

    /** Öffnungszeiten aus der Karte, kurz gefasst und auf Deutsch */
    private fun hoursText(p: OsmPlace): String {
        val h = p.tags["opening_hours"]?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        var s = h
        listOf("Mo" to "Mo", "Tu" to "Di", "We" to "Mi", "Th" to "Do", "Fr" to "Fr", "Sa" to "Sa", "Su" to "So", "PH" to "Feiertag")
            .forEach { (en, de) -> s = s.replace(Regex("\\b$en\\b"), de) }
        s = s.replace("off", "zu").replace("closed", "zu")
        return " · " + (if (s.length > 45) s.take(44) + "…" else s)
    }

    private fun stateText(s: OpenState) = when (s) {
        OpenState.OPEN -> "jetzt offen"
        OpenState.CLOSED -> "geschlossen"
        OpenState.UNKNOWN -> "Zeiten unbekannt"
    }

    private fun isExact(p: OsmPlace, streetRegex: Regex, numbers: List<String>): Boolean {
        val st = p.tags["addr:street"] ?: return false
        val hn = p.tags["addr:housenumber"] ?: return false
        if (!streetRegex.matches(st)) return false
        val first = Regex("""\d+""").find(hn)?.value ?: return false
        return first in numbers
    }

    fun classify(t: Map<String, String>): Category? {
        val a = t["amenity"]
        val s = t["shop"]
        val l = t["leisure"]
        val tour = t["tourism"]
        val massage = t["massage"]
        return when {
            a == "brothel" || a == "stripclub" || a == "swingerclub" || s == "erotic" ||
                    massage == "erotic" || massage == "sexual" ->
                Category("🔞", "Erotik / Laufhaus", "Erotik-Besuch", 1)
            a == "nightclub" -> Category("🪩", "Club", "Clubbesuch", 1)
            a == "events_venue" -> Category("🎤", "Veranstaltungsort", "Eventbesuch", 2)
            a == "bar" || a == "pub" || a == "biergarten" -> Category("🍸", "Bar", "Barbesuch", 2)
            a == "casino" || l == "adult_gaming_centre" -> Category("🎰", "Casino / Spielhalle", "Casinobesuch", 2)
            a == "place_of_worship" -> when (
                t["religion"] ?: (t["name"] ?: "").lowercase().let { n ->
                    when {
                        n.contains("synagog") || n.contains("tempel") || n.contains("or chadasch") -> "jewish"
                        n.contains("moschee") || n.contains("mosque") -> "muslim"
                        else -> null
                    }
                }
            ) {
                "jewish" -> Category("✡️", "Synagoge", "Synagogenbesuch", 3)
                "muslim" -> Category("🕌", "Moschee", "Moscheebesuch", 3)
                else -> Category("⛪", "Kirche", "Kirchenbesuch", 3)
            }
            tour == "hotel" || tour == "hostel" || tour == "guest_house" -> Category("🏨", "Hotel", "Hotel", 3)
            a == "hospital" -> Category("🏥", "Spital", "Spitalbesuch", 3)
            a == "cinema" || a == "theatre" -> Category("🎭", "Kino / Theater", "Kino-/Theaterbesuch", 3)
            s == "massage" || massage != null -> Category("💆", "Massagestudio", "Studiobesuch", 4)
            s == "tattoo" || s == "beauty" || l == "fitness_centre" -> Category("💪", "Studio", "Studiobesuch", 4)
            a == "restaurant" || a == "cafe" || a == "fast_food" -> Category("🍽️", "Lokal", "Lokalbesuch", 4)
            else -> null
        }
    }
}
