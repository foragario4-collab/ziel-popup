package at.agar.zielpopup

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OsmPlace(val id: String, val lat: Double, val lon: Double, val tags: Map<String, String>)

/** Gratis-Dienste von OpenStreetMap: Nominatim (Adresse -> Koordinaten) und Overpass (Umgebung) */
object OsmClient {
    private const val UA = "ZielPopup/0.9 (private Fahrer-App, Wien)"
    const val VIENNA_BBOX = "48.11,16.18,48.33,16.58"

    // Wenn ein Server überlastet ist, wird automatisch der nächste probiert
    private val OVERPASS_SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    init {
        // Keine alten, bereits abgebrochenen Verbindungen wiederverwenden
        System.setProperty("http.keepAlive", "false")
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "de")
            setRequestProperty("Connection", "close")
            useCaches = false
            connectTimeout = 6000
            readTimeout = 10000
        }

    private fun read(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Fehler $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    // Nominatim erlaubt höchstens 1 Anfrage pro Sekunde
    private var lastNominatim = 0L

    @Synchronized
    private fun nominatimGet(url: String): String {
        val wait = 1000 - (System.currentTimeMillis() - lastNominatim)
        if (wait > 0) Thread.sleep(wait)
        try {
            return read(open(url))
        } finally {
            lastNominatim = System.currentTimeMillis()
        }
    }

    // ---------- Adresse -> Koordinaten ----------
    private fun geocodeOnce(query: String): Pair<Double, Double>? {
        val arr = JSONArray(
            nominatimGet(
                "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=at&q=" +
                        URLEncoder.encode(query, "UTF-8")
            )
        )
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return o.getString("lat").toDouble() to o.getString("lon").toDouble()
    }

    fun geocode(query: String): Pair<Double, Double>? {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return geocodeOnce(query)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1500)
            }
        }
        throw IOException("Adresssuche: ${lastError?.message ?: "kein Ergebnis"}")
    }

    // ---------- Offizielles Adressverzeichnis der Stadt Wien (kennt jede Wiener Gasse) ----------
    private fun wienFeatures(address: String): JSONArray? {
        val clean = address
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
        val txt = read(
            open(
                "https://data.wien.gv.at/daten/OGDAddressService.svc/GetAddressInfo?crs=EPSG:4326&Address=" +
                        URLEncoder.encode(clean, "UTF-8")
            )
        )
        val arr = JSONObject(txt).optJSONArray("features")
        return if (arr == null || arr.length() == 0) null else arr
    }

    /** Bei mehreren Treffern den mit passender Postleitzahl nehmen */
    private fun pick(arr: JSONArray, postcode: String?): JSONObject? {
        if (postcode != null) {
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val p = f.optJSONObject("properties") ?: continue
                if (p.optString("PostalCode") == postcode || p.optString("PLZ") == postcode) return f
            }
        }
        return arr.optJSONObject(0)
    }

    fun wienPoint(street: String, number: String, postcode: String?): Pair<Double, Double>? {
        val f = pick(wienFeatures("$street $number") ?: return null, postcode) ?: return null
        val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        return c.getDouble(1) to c.getDouble(0)
    }

    // ---------- Ungenaue Eingabe -> offizieller Straßenname ----------
    private fun norm(s: String) = s.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace(Regex("[^a-z0-9]"), "")

    private val SUFFIXES = listOf("Gasse", "Straße", "Platz", "Weg", "Allee", "Ring", "Gürtel", "Lände", "Zeile")

    /**
     * "karl gunsam", "karl gunsam gasse", "Karl Gunsamgasse" -> "Karl-Gunsam-Gasse".
     * Fragt das Adressverzeichnis der Stadt Wien mit mehreren Schreibweisen.
     */
    fun wienResolveStreet(input: String): String? {
        val parts = input.trim().split(Regex("""[\s-]+""")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val words = parts.map { norm(it) }.filter { it.length >= 2 }
        if (words.isEmpty()) return null

        val cap = parts.map { it.replaceFirstChar { c -> c.uppercase() } }
        val candidates = LinkedHashSet<String>()
        candidates.add(input.trim())
        candidates.add(cap.joinToString("-"))
        candidates.add(cap.joinToString(" "))
        val last = norm(parts.last())
        val hasSuffix = last == "str" || SUFFIXES.any { last.endsWith(norm(it)) }
        if (!hasSuffix) {
            for (s in SUFFIXES) {
                candidates.add(cap.joinToString("-") + "-" + s)          // Karl-Gunsam-Gasse
                candidates.add(cap.joinToString(" ") + " " + s)          // Gumpendorfer Straße
                candidates.add(cap.joinToString("-") + s.lowercase())    // Gunsamgasse
            }
        }

        // Alle Schreibweisen gleichzeitig fragen (die Stadt Wien hat kein Limit wie Nominatim)
        val list = candidates.take(16)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = list.map { c ->
                pool.submit(java.util.concurrent.Callable {
                    try { wienFeatures(c) } catch (e: Exception) { null }
                })
            }
            for (f in futures) { // Reihenfolge = Priorität
                val arr = try { f.get(8, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { null } ?: continue
                for (i in 0 until minOf(arr.length(), 8)) {
                    val p = arr.optJSONObject(i)?.optJSONObject("properties") ?: continue
                    val name = p.optString("StreetName").ifEmpty { p.optString("Adresse") }
                    if (name.isEmpty()) continue
                    val n = norm(name)
                    if (words.all { n.contains(it) }) return name
                }
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    /** Umriss einer Gasse laut Stadt Wien: [Süd, West, Nord, Ost] */
    fun wienStreetBox(street: String, postcode: String?): DoubleArray? {
        val f = pick(wienFeatures(street) ?: return null, postcode) ?: return null
        val pad = 0.0003
        val bb = f.optJSONArray("bbox")
        if (bb != null && bb.length() >= 4) {
            return doubleArrayOf(
                bb.getDouble(1) - pad, bb.getDouble(0) - pad,
                bb.getDouble(3) + pad, bb.getDouble(2) + pad
            )
        }
        val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val lat = c.getDouble(1)
        val lon = c.getDouble(0)
        return doubleArrayOf(lat - 0.0025, lon - 0.0035, lat + 0.0025, lon + 0.0035)
    }

    /** Umriss einer Gasse: [Süd, West, Nord, Ost] */
    fun geocodeBox(query: String): DoubleArray? {
        val arr = JSONArray(
            nominatimGet(
                "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=at&q=" +
                        URLEncoder.encode(query, "UTF-8")
            )
        )
        if (arr.length() == 0) return null
        val bb = arr.getJSONObject(0).optJSONArray("boundingbox") ?: return null
        val pad = 0.0003 // ca. 25-30 m Rand
        return doubleArrayOf(
            bb.getString(0).toDouble() - pad, bb.getString(2).toDouble() - pad,
            bb.getString(1).toDouble() + pad, bb.getString(3).toDouble() + pad
        )
    }

    // ---------- Schnellsuche: Photon (1 Anfrage für alle Kategorien) + Öffnungszeiten in 1 Anfrage ----------
    private val PHOTON_TAGS = listOf(
        "amenity:bar", "amenity:pub", "amenity:biergarten", "amenity:nightclub", "amenity:brothel",
        "amenity:stripclub", "amenity:swingerclub", "amenity:casino", "amenity:place_of_worship",
        "amenity:restaurant", "amenity:cafe", "amenity:fast_food", "amenity:hospital", "amenity:cinema",
        "amenity:theatre", "amenity:events_venue", "amenity:arts_centre", "amenity:community_centre",
        "tourism:hotel", "tourism:hostel", "tourism:guest_house",
        "shop:erotic", "shop:massage", "shop:tattoo", "shop:beauty",
        "leisure:fitness_centre", "leisure:adult_gaming_centre", "leisure:dance"
    )

    /** Alle interessanten Orte im Umkreis – eine einzige, schnelle Anfrage */
    fun fastNear(lat: Double, lon: Double, radiusM: Int): List<OsmPlace> {
        val sb = StringBuilder("https://photon.komoot.io/reverse?lat=$lat&lon=$lon&limit=50&lang=de")
        if (radiusM > 300) sb.append("&radius=").append(radiusM / 1000.0 + 0.1) // Kilometer, nur bei langen Gassen
        for (t in PHOTON_TAGS) sb.append("&osm_tag=").append(t)
        val json = JSONObject(read(open(sb.toString())))
        val features = json.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<OsmPlace>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val pr = f.optJSONObject("properties") ?: continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val type = when (pr.optString("osm_type")) {
                "N" -> "node"; "W" -> "way"; "R" -> "relation"; else -> continue
            }
            val tags = HashMap<String, String>()
            val k = pr.optString("osm_key")
            val v = pr.optString("osm_value")
            if (k.isNotEmpty() && v.isNotEmpty()) tags[k] = v
            pr.optString("name").takeIf { it.isNotEmpty() }?.let { tags["name"] = it }
            pr.optString("street").takeIf { it.isNotEmpty() }?.let { tags["addr:street"] = it }
            pr.optString("housenumber").takeIf { it.isNotEmpty() }?.let { tags["addr:housenumber"] = it }
            val pLat = c.getDouble(1)
            val pLon = c.getDouble(0)
            if (distanceM(lat, lon, pLat, pLon) > radiusM) continue // Umkreis selbst prüfen
            out.add(OsmPlace(type + "/" + pr.optLong("osm_id"), pLat, pLon, tags))
        }
        return addHours(out.distinctBy { it.id })
    }

    /** Öffnungszeiten (und Religion bei Gebetshäusern) für alle Treffer auf einmal nachladen */
    private fun addHours(places: List<OsmPlace>): List<OsmPlace> {
        if (places.isEmpty()) return places
        return try {
            val ids = ArrayList<String>()
            for (p in places.take(50)) {
                val parts = p.id.split("/")
                if (parts.size != 2) continue
                val letter = when (parts[0]) { "node" -> "N"; "way" -> "W"; "relation" -> "R"; else -> null } ?: continue
                ids.add(letter + parts[1])
            }
            if (ids.isEmpty()) return places
            val arr = JSONArray(
                nominatimGet("https://nominatim.openstreetmap.org/lookup?format=json&extratags=1&osm_ids=" + ids.joinToString(","))
            )
            val extra = HashMap<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                o.optJSONObject("extratags")?.let { extra[o.optString("osm_type") + "/" + o.optLong("osm_id")] = it }
            }
            places.map { p ->
                val ex = extra[p.id]
                if (ex == null) {
                    p
                } else {
                    val t = HashMap(p.tags)
                    val keys = ex.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (!t.containsKey(key)) t[key] = ex.optString(key)
                    }
                    p.copy(tags = t)
                }
            }
        } catch (e: Exception) {
            places // ohne Öffnungszeiten weiter, lieber schnell als gar nichts
        }
    }

    // ---------- Ersatzsuche über Nominatim (wenn Overpass nicht antwortet) ----------
    private val FALLBACK_TERMS = listOf(
        "bar", "nightclub", "brothel", "Laufhaus", "place of worship", "hotel", "Studio"
    )

    /** Das Objekt direkt an diesem Punkt (z. B. eine Synagoge im Gebäude) */
    fun reverseAt(lat: Double, lon: Double): OsmPlace? = try {
        val o = JSONObject(
            nominatimGet(
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&extratags=1&addressdetails=1&lat=$lat&lon=$lon"
            )
        )
        if (o.has("error")) null else toPlace(o)
    } catch (e: Exception) {
        null
    }

    fun fallbackPoint(lat: Double, lon: Double, radiusM: Int): List<OsmPlace> {
        val out = ArrayList<OsmPlace>()
        try {
            val o = JSONObject(
                nominatimGet(
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&extratags=1&addressdetails=1&lat=$lat&lon=$lon"
                )
            )
            if (!o.has("error")) toPlace(o)?.let { out.add(it) }
        } catch (_: Exception) {
        }
        val dLat = radiusM / 111000.0
        val dLon = radiusM / 74000.0 // Wien liegt auf ca. 48° Breite
        out.addAll(fallbackBox(lat - dLat, lon - dLon, lat + dLat, lon + dLon))
        return out.distinctBy { it.id }
    }

    fun fallbackBox(south: Double, west: Double, north: Double, east: Double): List<OsmPlace> {
        val out = ArrayList<OsmPlace>()
        var anySuccess = false
        var lastError: Exception? = null
        for (term in FALLBACK_TERMS) {
            try {
                val arr = JSONArray(
                    nominatimGet(
                        "https://nominatim.openstreetmap.org/search?format=jsonv2&extratags=1&addressdetails=1&limit=20&bounded=1" +
                                "&viewbox=$west,$north,$east,$south&q=" + URLEncoder.encode(term, "UTF-8")
                    )
                )
                anySuccess = true
                for (i in 0 until arr.length()) toPlace(arr.getJSONObject(i))?.let { out.add(it) }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!anySuccess) throw IOException("Ersatzsuche: ${lastError?.message ?: "keine Antwort"}")
        return out.distinctBy { it.id }
    }

    private fun toPlace(o: JSONObject): OsmPlace? {
        val cat = o.optString("category")
        val type = o.optString("type")
        if (cat.isEmpty() || type.isEmpty()) return null
        val tags = HashMap<String, String>()
        tags[cat] = type
        o.optString("name").takeIf { it.isNotEmpty() }?.let { tags["name"] = it }
        o.optJSONObject("extratags")?.let { ex ->
            val keys = ex.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                tags[k] = ex.optString(k)
            }
        }
        o.optJSONObject("address")?.let { ad ->
            ad.optString("road").takeIf { it.isNotEmpty() }?.let { tags["addr:street"] = it }
            ad.optString("house_number").takeIf { it.isNotEmpty() }?.let { tags["addr:housenumber"] = it }
        }
        val lat = o.optString("lat").toDoubleOrNull() ?: return null
        val lon = o.optString("lon").toDoubleOrNull() ?: return null
        return OsmPlace(o.optString("osm_type") + "/" + o.optLong("osm_id"), lat, lon, tags)
    }

    // ---------- Umgebung ----------
    // Wenn Overpass gerade nicht geht, 10 Minuten lang gleich die Ersatzsuche nehmen (spart Wartezeit)
    private var overpassDownUntil = 0L
    private var prefs: android.content.SharedPreferences? = null

    /** Einmal beim App-Start aufrufen, damit sich die App merkt, ob Overpass gerade geht */
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("einstellungen", android.content.Context.MODE_PRIVATE)
        overpassDownUntil = prefs?.getLong("overpass_down_until", 0L) ?: 0L
    }

    fun overpass(query: String): List<OsmPlace> {
        if (System.currentTimeMillis() < overpassDownUntil) throw IOException("Overpass pausiert")
        var lastError: Exception? = null
        for (server in OVERPASS_SERVERS.take(1)) {
            try {
                return runOverpass(server, query)
            } catch (e: Exception) {
                lastError = e
            }
        }
        // 6 Stunden lang nicht mehr probieren -> keine Wartezeit bei den nächsten Suchen
        overpassDownUntil = System.currentTimeMillis() + 6 * 60 * 60 * 1000L
        prefs?.edit()?.putLong("overpass_down_until", overpassDownUntil)?.apply()
        throw IOException("Umgebungssuche: alle Server ausgelastet (${lastError?.message}) – kurz warten und nochmal")
    }

    private fun runOverpass(server: String, query: String): List<OsmPlace> {
        val conn = open(server).apply {
            connectTimeout = 3000
            readTimeout = 7000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        conn.outputStream.use {
            it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8))
        }
        val json = JSONObject(read(conn))
        val els = json.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<OsmPlace>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tagsObj = e.optJSONObject("tags") ?: continue
            val center = e.optJSONObject("center")
            val lat = if (e.has("lat")) e.getDouble("lat") else center?.getDouble("lat") ?: continue
            val lon = if (e.has("lon")) e.getDouble("lon") else center?.getDouble("lon") ?: continue
            val tags = HashMap<String, String>()
            val keys = tagsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                tags[k] = tagsObj.optString(k)
            }
            out.add(OsmPlace(e.optString("type") + "/" + e.optLong("id"), lat, lon, tags))
        }
        return out.distinctBy { it.id }
    }

    // ---------- Verbindungstest: prüft jeden Server einzeln ----------
    fun diagnose(): List<String> {
        val out = ArrayList<String>()
        out.add(check("Internet allgemein") { read(open("https://www.wikipedia.org/")) })
        out.add(check("Adressen Stadt Wien") {
            wienPoint("Stephansplatz", "1", "1010") ?: throw IOException("kein Ergebnis")
        })
        out.add(check("Adresssuche (Nominatim)") {
            geocodeOnce("Stephansplatz 1, 1010 Wien") ?: throw IOException("kein Ergebnis")
        })
        out.add(
            try {
                val n = fastNear(48.2085, 16.3731, 150).size
                if (n == 0) "❌ Schnellsuche Photon\n     0 Orte am Stephansplatz gefunden"
                else "✅ Schnellsuche Photon ($n Orte am Stephansplatz)"
            } catch (e: Exception) {
                "❌ Schnellsuche Photon\n     ${e.javaClass.simpleName}: ${e.message ?: ""}"
            }
        )
        out.add(
            try {
                val p = reverseAt(48.2144, 16.38869) // Robertgasse 2
                "ℹ️ Test Robertgasse 2: " + (p?.tags?.get("name") ?: "kein Ort eingetragen") +
                        (p?.tags?.get("amenity")?.let { " ($it)" } ?: "")
            } catch (e: Exception) {
                "❌ Test Robertgasse 2: ${e.message ?: ""}"
            }
        )
        val testQuery = "[out:json][timeout:10];node(around:50,48.2085,16.3731)[\"amenity\"];out 1;"
        for (s in OVERPASS_SERVERS) {
            out.add(check("Umgebung: " + URL(s).host) { runOverpass(s, testQuery) })
        }
        return out
    }

    private fun check(name: String, block: () -> Any): String = try {
        block()
        "✅ $name"
    } catch (e: Exception) {
        "❌ $name\n     ${e.javaClass.simpleName}: ${e.message ?: ""}"
    }
}
