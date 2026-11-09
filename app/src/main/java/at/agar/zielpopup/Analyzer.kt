package at.agar.zielpopup

import java.util.Calendar

/** Eine Kategorie, die man in den Einstellungen ein- und ausschalten kann */
data class Category(val key: String, val emoji: String, val label: String, val visit: String?, val prio: Int)

/**
 * Ein Ort in der Liste.
 *  head  = "⛪ Kirchenbesuch möglich"
 *  name  = "Pfarrkirche St. Leopold"
 *  info  = "jetzt geöffnet · heute 07:00–19:00"
 */
data class PlaceRow(
    val catKey: String?,
    val head: String,
    val name: String,
    val dist: Int?,
    val houseNo: String?,
    val state: OpenState,
    val info: String
)

/**
 * Ergebnis einer Prüfung.
 *  headline = was ist es (groß), title = Adresse, nearby = Orte rundherum.
 *  Sortieren und Filtern passiert erst beim Anzeigen (siehe ResultView),
 *  damit Änderungen in den Einstellungen sofort wirken.
 */
data class AnalysisResult(
    val title: String,
    val headline: String? = null,
    val exact: List<PlaceRow> = emptyList(),
    val nearby: List<PlaceRow> = emptyList(),
    val streetMode: Boolean = false,
    val radius: Int = 25,
    val notes: List<String> = emptyList(),
    val time: Long = System.currentTimeMillis(),
    val etaMin: Int? = null
)

object Cats {
    val ALL = listOf(
        Category("bar", "🍸", "Bar", "Barbesuch", 2),
        Category("lokal", "🍽️", "Restaurant / Café", "Lokalbesuch", 4),
        Category("disko", "🪩", "Diskothek / Club", "Clubbesuch", 1),
        Category("nachtclub", "💃", "Nachtclub", "Nachtclubbesuch", 1),
        Category("laufhaus", "🔞", "Laufhaus", "Laufhausbesuch", 1),
        Category("saunaclub", "🧖", "Saunaclub", "Saunaclubbesuch", 1),
        Category("sexstudio", "🔞", "Sexstudio / Erotik", "Studiobesuch", 1),
        Category("casino", "🎰", "Casino / Wettbüro", "Casinobesuch", 2),
        Category("kirche", "⛪", "Kirche", "Kirchenbesuch", 3),
        Category("synagoge", "✡️", "Synagoge", "Synagogenbesuch", 3),
        Category("moschee", "🕌", "Moschee", "Moscheebesuch", 3),
        Category("hotel", "🏨", "Hotel", "Hotelbesuch", 3),
        Category("spital", "🏥", "Spital", "Spitalbesuch", 3),
        Category("bahnhof", "🚉", "Bahnhof", null, 3),
        Category("theater", "🎭", "Theater, Kino & Events", "Theater-/Eventbesuch", 3),
        Category("fitness", "💪", "Fitnessstudio", "Trainingsbesuch", 4),
        Category("massage", "💆", "Massage & Kosmetik", "Studiobesuch", 4)
    )
    private val BY_KEY = ALL.associateBy { it.key }
    fun get(key: String): Category = BY_KEY.getValue(key)
}

object Analyzer {
    const val STREET_M = 20   // Streifen links/rechts der Gasse, wenn keine Hausnummer da ist
    private var key: String? = null

    private val FILTERS = listOf(
        """["amenity"~"^(bar|pub|biergarten|nightclub|restaurant|cafe|fast_food|place_of_worship|brothel|stripclub|swingerclub|casino|gambling|hospital|cinema|theatre|events_venue)$"]""",
        """["shop"~"^(erotic|massage|tattoo|beauty|bookmaker)$"]""",
        """["leisure"~"^(fitness_centre|adult_gaming_centre|sauna)$"]""",
        """["tourism"~"^(hotel|hostel|guest_house)$"]""",
        """["railway"="station"]""",
        """["massage"]"""
    )

    private val cache = HashMap<String, Pair<Long, AnalysisResult>>()
    private const val CACHE_MS = 20 * 60 * 1000L

    fun analyze(input: String, googleKey: String? = null, radius: Int = 25): AnalysisResult {
        val cacheKey = input.lowercase().replace(Regex("[^a-z0-9äöüß]"), "") +
                (if (googleKey.isNullOrBlank()) "|osm" else "|g") + "|$radius"
        cache[cacheKey]?.let { (time, res) ->
            if (System.currentTimeMillis() - time < CACHE_MS) return res.copy(time = System.currentTimeMillis())
        }
        val res = analyzeFresh(input, googleKey, radius)
        cache[cacheKey] = System.currentTimeMillis() to res
        return res
    }

    private fun analyzeFresh(input: String, googleKey: String?, radius: Int): AnalysisResult {
        key = googleKey?.trim()?.takeIf { it.isNotEmpty() }
        val parsed = AddressParser.parse(input)
        if (parsed.street.isBlank()) return AnalysisResult("❓ Adresse nicht erkannt")

        // 1. Ziel außerhalb Wiens (z. B. "Vienna Airport Terminal 1, Schwechat") -> freie Ortssuche
        if (isOutsideVienna(input)) {
            placeMode(input, radius)?.let { return it }
        }

        // Ungenaue Eingabe in den offiziellen Straßennamen umwandeln ("karl gunsam" -> "Karl-Gunsam-Gasse")
        val official = try { OsmClient.wienResolveStreet(parsed.street) } catch (e: Exception) { null }

        // 2. Keine Wiener Straße und kein Straßenwort (z. B. "Hauptbahnhof", "Ernst-Happel-Stadion") -> Ortssuche
        if (official == null && !STREET_WORD.containsMatchIn(parsed.street)) {
            placeMode(input, radius)?.let { return it }
        }
        val addr = if (official != null) parsed.copy(street = official) else parsed
        val shown = "📍 " + addr.street + (addr.rawNumber?.let { " $it" } ?: "") +
                (addr.postcode?.let { ", $it Wien" } ?: ", Wien")
        return search(addr, radius).copy(title = shown)
    }

    private fun search(addr: ParsedAddress, radius: Int): AnalysisResult {
        val rx = AddressParser.streetRegex(addr.street)
        return if (addr.houseNumbers.isEmpty()) {
            streetMode(addr, rx, "🛣️ Ganze Gasse (keine Hausnummer)")
        } else {
            pointMode(addr, rx, radius)
        }
    }

    /** Bei Google immer etwas mehr holen (Adresspunkte sind nie ganz genau); gerundet, damit der Zwischenspeicher greift */
    private fun fetchRadius(radius: Int): Int = if (radius <= 25) 45 else ((radius + 20 + 24) / 25) * 25

    // ---------- Mit Hausnummer ----------
    private fun pointMode(addr: ParsedAddress, rx: String, radius: Int): AnalysisResult {
        val q = "${addr.street} ${addr.rawNumber}, ${addr.postcode ?: ""} Wien".replace("  ", " ")
        // Zuerst das Adressverzeichnis der Stadt Wien, dann OpenStreetMap
        val pos = (try { OsmClient.wienPoint(addr.street, addr.rawNumber ?: "", addr.postcode) } catch (e: Exception) { null })
            ?: (try { OsmClient.geocode(q) } catch (e: Exception) { null })
            ?: return streetMode(addr, rx, "⚠️ Hausnummer nicht gefunden – ganze Gasse geprüft")
        val (lat, lon) = pos
        val fetchR = fetchRadius(radius)

        val numRx = "^(" + addr.houseNumbers.joinToString("|") + ")([^0-9].*)?$"
        // Nur im kleinen Umkreis suchen – ganz Wien zu durchsuchen war zu langsam (Timeout)
        val sb = StringBuilder("[out:json][timeout:15];(")
        for (f in FILTERS) sb.append("nwr(around:$radius,$lat,$lon)$f;")
        sb.append("nwr(around:80,$lat,$lon)[\"addr:housenumber\"~\"$numRx\"][\"name\"];")
        sb.append(");out center tags;")

        val notes = ArrayList<String>()
        val places: List<OsmPlace> = run {
            // 1. Google (wenn Schlüssel eingetragen) – kennt fast alle Lokale
            val k = key
            if (k != null) {
                try {
                    return@run GoogleClient.nearby(k, lat, lon, fetchR.toDouble())
                } catch (e: Exception) {
                    notes.add("⚠️ ${e.message}")
                }
            }
            // 2. Schnellsuche (Photon) – meist in 1–2 Sekunden fertig
            try {
                val near = OsmClient.fastNear(lat, lon, fetchR)
                // zusätzlich: was steht genau an der Adresse (z. B. Synagoge, Lokal im Haus)?
                val here = OsmClient.reverseAt(lat, lon)
                return@run (if (here != null) near + here else near).distinctBy { it.id }
            } catch (e: Exception) {
            }
            // 3. OpenStreetMap über Overpass, 4. Ersatzsuche über Nominatim
            try {
                OsmClient.overpass(sb.toString())
            } catch (e: Exception) {
                if (notes.isEmpty()) notes.add("ℹ️ Ersatzsuche (Overpass nicht erreichbar)")
                OsmClient.fallbackPoint(lat, lon, fetchR)
                    .filter { distanceM(lat, lon, it.lat, it.lon) <= radius + 15 || it.tags.containsKey("addr:housenumber") }
            }
        }
        val now = Calendar.getInstance()
        val streetRegex = Regex(rx, RegexOption.IGNORE_CASE)

        val exactPlaces = places.filter { isExact(it, streetRegex, addr.houseNumbers) && Kinds.isRealPlace(it.tags) }
        val exactIds = exactPlaces.map { it.id }.toSet()

        val headline = exactPlaces.firstOrNull()?.let { Kinds.describe(it.tags) } ?: "🏠 Wohnadresse"
        val exact = exactPlaces.map { p ->
            val (emoji, label) = Kinds.of(p.tags) ?: ("📍" to "Ort")
            val st = stateOf(p, now)
            PlaceRow(null, "$emoji $label", p.tags["name"] ?: label, null, null, st, infoText(p, st))
        }

        val nearby = places
            .filter { it.id !in exactIds }
            .mapNotNull { p -> toRow(p, now, distanceM(lat, lon, p.lat, p.lon)) }
            .filter { (it.dist ?: 0) <= radius }

        return AnalysisResult("", headline, exact, nearby, streetMode = false, radius = radius, notes = notes)
    }

    // ---------- Ort statt Adresse (Flughafen, Bahnhof, Ziel außerhalb Wiens) ----------
    private val STREET_WORD = Regex(
        """(?i)(gasse|straße|strasse|str\.?$|platz|weg|allee|ring|gürtel|lände|zeile|markt|kai|promenade|steig|brücke|hof|berg|graben|bastei)"""
    )

    private fun isOutsideVienna(input: String): Boolean {
        val parts = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        val locality = parts.last().lowercase()
        return !locality.contains("wien") && !locality.contains("vienna") &&
                !Regex("""^1[0-2]\d0""").containsMatchIn(locality)
    }

    private fun placeMode(input: String, radius: Int): AnalysisResult? {
        val hit = try { OsmClient.searchPlace(input) } catch (e: Exception) { null } ?: return null
        val lat = hit.lat
        val lon = hit.lon
        val fetchR = fetchRadius(radius)
        val notes = ArrayList<String>()
        val places: List<OsmPlace> = run {
            val k = key
            if (k != null) {
                try {
                    return@run GoogleClient.nearby(k, lat, lon, fetchR.toDouble())
                } catch (e: Exception) {
                    notes.add("⚠️ ${e.message}")
                }
            }
            try {
                OsmClient.fastNear(lat, lon, fetchR)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val now = Calendar.getInstance()
        val real = Kinds.isRealPlace(hit.tags)
        val headline = when {
            real -> Kinds.describe(hit.tags)
            hit.tags["addr:housenumber"] != null -> "🏠 Wohnadresse"
            else -> "📍 " + (hit.tags["name"] ?: input)
        }
        val exact = if (real) {
            val (emoji, label) = Kinds.of(hit.tags) ?: ("📍" to "Ort")
            val st = stateOf(hit, now)
            listOf(PlaceRow(null, "$emoji $label", hit.tags["name"] ?: label, null, null, st, infoText(hit, st)))
        } else emptyList()
        val nearby = places
            .filter { it.id != hit.id && it.tags["name"] != hit.tags["name"] }
            .mapNotNull { p -> toRow(p, now, distanceM(lat, lon, p.lat, p.lon)) }
            .filter { (it.dist ?: 0) <= radius }
        return AnalysisResult("📍 $input", headline, exact, nearby, streetMode = false, radius = radius, notes = notes)
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
        val notes = ArrayList<String>()
        val notFound = AnalysisResult("", "❓ Gasse nicht gefunden: ${addr.street}")
        val box: DoubleArray? by lazy { streetBox(addr) } // nur einmal abfragen
        val raw: List<OsmPlace> = run {
            // 1. Google (wenn Schlüssel eingetragen): Mitte der Gasse, Umkreis = halbe Länge
            val k = key
            if (k != null) {
                try {
                    val b = box ?: return notFound
                    val cLat = (b[0] + b[2]) / 2
                    val cLon = (b[1] + b[3]) / 2
                    val r = (distanceM(b[0], b[1], b[2], b[3]) / 2).coerceIn(40, 450)
                    return@run GoogleClient.nearby(k, cLat, cLon, r.toDouble())
                        .filter { p -> p.tags["addr:street"]?.let { streetRegex.matches(it) } ?: false }
                } catch (e: Exception) {
                    notes.add("⚠️ ${e.message}")
                }
            }
            // 2. Schnellsuche (Photon) entlang der Gasse
            try {
                val b = box ?: return notFound
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
                if (notes.isEmpty()) notes.add("ℹ️ Ersatzsuche (Overpass nicht erreichbar)")
                val b = box ?: return notFound
                OsmClient.fallbackBox(b[0], b[1], b[2], b[3])
                    .filter { p -> p.tags["addr:street"]?.let { streetRegex.matches(it) } ?: true }
            }
        }
        val list = raw.mapNotNull { toRow(it, now, null) }
        return AnalysisResult("", title, emptyList(), list, streetMode = true, notes = notes)
    }

    // ---------- Hilfsfunktionen ----------
    private fun streetBox(addr: ParsedAddress): DoubleArray? =
        (try { OsmClient.wienStreetBox(addr.street, addr.postcode) } catch (e: Exception) { null })
            ?: (try {
                OsmClient.geocodeBox("${addr.street}, ${addr.postcode ?: ""} Wien".replace("  ", " "))
            } catch (e: Exception) { null })

    /** Google liefert "jetzt offen" direkt, OpenStreetMap nur Öffnungszeiten-Text */
    private fun stateOf(p: OsmPlace, now: Calendar): OpenState {
        if (p.tags["_temp_closed"] == "1") return OpenState.CLOSED
        when (p.tags["_open"]) {
            "1" -> return OpenState.OPEN
            "0" -> return OpenState.CLOSED
        }
        // Google gibt bei 24-Stunden-Betrieben manchmal nur den Text "24 Stunden geöffnet"
        if (p.tags["_week"]?.contains("24 Stunden", ignoreCase = true) == true) return OpenState.OPEN
        val st = OpeningHours.state(p.tags["opening_hours"], now)
        // Hotels und Spitäler haben oft keine Zeiten eingetragen, sind aber rund um die Uhr besetzt
        if (st == OpenState.UNKNOWN && alwaysOpen(p.tags)) return OpenState.OPEN
        return st
    }

    private fun alwaysOpen(t: Map<String, String>): Boolean {
        val g = t["_gtypes"] ?: ""
        return t["tourism"] in setOf("hotel", "hostel", "guest_house") || t["amenity"] == "hospital" ||
                g.contains("lodging") || g.contains("hotel") || g.contains("hospital")
    }

    private fun toRow(p: OsmPlace, now: Calendar, dist: Int?): PlaceRow? {
        val cat = classify(p.tags) ?: return null
        val st = stateOf(p, now)
        // Geschlossene Orte werden nicht versteckt: rote LED, nur Bezeichnung statt "… möglich"
        val head = if (st == OpenState.CLOSED || cat.visit == null) "${cat.emoji} ${cat.label}"
                   else "${cat.emoji} ${cat.visit} möglich"
        return PlaceRow(cat.key, head, p.tags["name"] ?: cat.label, dist, p.tags["addr:housenumber"], st, infoText(p, st))
    }

    /** Untere Zeile: Status + heutige Öffnungszeiten */
    private fun infoText(p: OsmPlace, s: OpenState): String = statusText(p, s) + hoursText(p)

    /** Status mit passendem Hinweis statt immer nur "Zeiten unbekannt" */
    private fun statusText(p: OsmPlace, s: OpenState): String {
        if (p.tags["_temp_closed"] == "1") return "vorübergehend geschlossen"
        if (s != OpenState.UNKNOWN) return stateText(s)
        val t = p.tags
        return when {
            t["amenity"] == "place_of_worship" -> "Gottesdienstzeiten unbekannt"
            t["amenity"] == "events_venue" -> "Zeiten je nach Veranstaltung"
            t["amenity"] == "nightclub" || t["amenity"] == "stripclub" -> "Zeiten je nach Event"
            else -> stateText(s)
        }
    }

    private fun hoursText(p: OsmPlace): String {
        if (p.tags["_temp_closed"] == "1") return ""
        GoogleClient.todayText(p.tags)?.let { return " · $it" } // Google: heutige Zeiten
        val h = p.tags["opening_hours"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return if (alwaysOpen(p.tags)) " · rund um die Uhr" else ""
        var s = h
        listOf("Mo" to "Mo", "Tu" to "Di", "We" to "Mi", "Th" to "Do", "Fr" to "Fr", "Sa" to "Sa", "Su" to "So", "PH" to "Feiertag")
            .forEach { (en, de) -> s = s.replace(Regex("\\b$en\\b"), de) }
        s = s.replace("off", "zu").replace("closed", "zu")
        return " · " + (if (s.length > 45) s.take(44) + "…" else s)
    }

    private fun stateText(s: OpenState) = when (s) {
        OpenState.OPEN -> "jetzt geöffnet"
        OpenState.CLOSED -> "jetzt geschlossen"
        OpenState.UNKNOWN -> "Zeiten unbekannt"
    }

    private fun isExact(p: OsmPlace, streetRegex: Regex, numbers: List<String>): Boolean {
        val st = p.tags["addr:street"] ?: return false
        val hn = p.tags["addr:housenumber"] ?: return false
        if (!streetRegex.matches(st)) return false
        val first = Regex("""\d+""").find(hn)?.value ?: return false
        return first in numbers
    }

    private val WETT = Regex("""(wettbüro|wettbuero|sportwett|wettcafe|wett-?shop)""")

    fun classify(t: Map<String, String>): Category? {
        val a = t["amenity"]
        val s = t["shop"]
        val l = t["leisure"]
        val tour = t["tourism"]
        val massage = t["massage"]
        val n = (t["name"] ?: "").lowercase()
        val g = t["_gtypes"] ?: ""
        return when {
            a == "brothel" || n.contains("laufhaus") || n.contains("bordell") -> Cats.get("laufhaus")
            n.contains("saunaclub") || n.contains("sauna club") || n.contains("sauna-club") || n.contains("fkk") ->
                Cats.get("saunaclub")
            a == "stripclub" || a == "swingerclub" || n.contains("nachtclub") || n.contains("night club") ||
                    n.contains("table dance") || n.contains("tabledance") -> Cats.get("nachtclub")
            s == "erotic" || massage == "erotic" || massage == "sexual" || n.contains("sexstudio") ||
                    n.contains("erotik") || n.contains("erotic") -> Cats.get("sexstudio")
            a == "nightclub" || l == "dance" -> Cats.get("disko")
            a == "bar" || a == "pub" || a == "biergarten" -> Cats.get("bar")
            a == "casino" || a == "gambling" || l == "adult_gaming_centre" || s == "bookmaker" ||
                    WETT.containsMatchIn(n) -> Cats.get("casino")
            a == "place_of_worship" -> when (
                t["religion"] ?: n.let {
                    when {
                        it.contains("synagog") || it.contains("tempel") || it.contains("or chadasch") -> "jewish"
                        it.contains("moschee") || it.contains("mosque") || it.contains("cami") ||
                                it.contains("masjid") || it.contains("džamija") || it.contains("dzamija") -> "muslim"
                        else -> null
                    }
                }
            ) {
                "jewish" -> Cats.get("synagoge")
                "muslim" -> Cats.get("moschee")
                else -> Cats.get("kirche")
            }
            tour == "hotel" || tour == "hostel" || tour == "guest_house" -> Cats.get("hotel")
            a == "hospital" -> Cats.get("spital")
            t["railway"] == "station" || g.contains("train_station") -> Cats.get("bahnhof")
            a == "cinema" || a == "theatre" || a == "events_venue" -> Cats.get("theater")
            l == "fitness_centre" -> Cats.get("fitness")
            s == "massage" || massage != null || s == "tattoo" || s == "beauty" -> Cats.get("massage")
            a == "restaurant" || a == "cafe" || a == "fast_food" -> Cats.get("lokal")
            else -> null
        }
    }
}
