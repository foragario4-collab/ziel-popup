package at.agar.zielpopup

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Google Places API (New) – kennt fast jedes Lokal inkl. Öffnungszeiten.
 * Läuft nur übers Internet, braucht KEINE Google-Play-Dienste.
 *
 * Schutz vor Kosten:
 *  - eigenes Tageslimit (Standard 33 Abfragen/Tag ≈ 1.000/Monat = Gratis-Kontingent)
 *  - Zwischenspeicher 7 Tage pro Ort; "jetzt offen" wird aus den gespeicherten Zeiten neu berechnet
 */
object GoogleClient {
    const val DAILY_LIMIT = 33
    private const val CACHE_DAYS = 7
    private const val PACKAGE = "at.agar.zielpopup"
    private const val CERT_SHA1 = "F0A2087735A5FCB8BC67A916F47E82B59F4814DD"

    private const val FIELDS =
        "places.id,places.displayName,places.types,places.primaryType,places.formattedAddress," +
                "places.location,places.businessStatus,places.primaryTypeDisplayName,places.currentOpeningHours.openNow," +
                "places.currentOpeningHours.weekdayDescriptions,places.regularOpeningHours.periods"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("google", Context.MODE_PRIVATE)
    }

    // ---------- Zähler ----------
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun month() = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun usedToday(): Int = prefs?.let { if (it.getString("day", "") == today()) it.getInt("day_count", 0) else 0 } ?: 0
    fun usedMonth(): Int = prefs?.let { if (it.getString("month", "") == month()) it.getInt("month_count", 0) else 0 } ?: 0

    private fun countCall() {
        val p = prefs ?: return
        p.edit()
            .putString("day", today()).putInt("day_count", usedToday() + 1)
            .putString("month", month()).putInt("month_count", usedMonth() + 1)
            .apply()
    }

    // ---------- Hauptfunktion ----------
    fun nearby(key: String, lat: Double, lon: Double, radiusM: Double): List<OsmPlace> {
        val cacheKey = String.format(Locale.US, "c_%.4f_%.4f_%d", lat, lon, radiusM.toInt())
        readCache(cacheKey)?.let { return it }

        if (usedToday() >= DAILY_LIMIT) {
            throw IOException("Google-Tageslimit ($DAILY_LIMIT) erreicht – heute nur OpenStreetMap")
        }
        val text = request(key, lat, lon, radiusM)
        countCall()
        val places = parse(text)
        prefs?.edit()?.putString(cacheKey, JSONObject().put("t", System.currentTimeMillis()).put("p", toJson(places)).toString())?.apply()
        return places
    }

    private fun request(key: String, lat: Double, lon: Double, radiusM: Double): String {
        val body = JSONObject()
            .put("maxResultCount", 20)
            .put("languageCode", "de")
            .put("rankPreference", "DISTANCE")
            .put(
                "locationRestriction", JSONObject().put(
                    "circle", JSONObject()
                        .put("center", JSONObject().put("latitude", lat).put("longitude", lon))
                        .put("radius", radiusM.coerceIn(1.0, 50000.0))
                )
            )
        val conn = (URL("https://places.googleapis.com/v1/places:searchNearby").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Goog-Api-Key", key.trim())
            setRequestProperty("X-Goog-FieldMask", FIELDS)
            // erlaubt, den Schlüssel in der Google-Konsole auf genau diese App zu beschränken
            setRequestProperty("X-Android-Package", PACKAGE)
            setRequestProperty("X-Android-Cert", CERT_SHA1)
        }
        val code: Int
        val text: String
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            code = conn.responseCode
            text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).getJSONObject("error").optString("message")
            } catch (e: Exception) {
                ""
            }
            throw IOException(explain(code, msg))
        }
        return text
    }

    /** Verständliche Fehlermeldungen statt Google-Fachsprache */
    private fun explain(code: Int, msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("api key not valid") || m.contains("api_key_invalid") -> "Google: Schlüssel ungültig – bitte neu kopieren"
            m.contains("has not been used") || m.contains("disabled") -> "Google: „Places API (New)“ ist nicht aktiviert"
            m.contains("billing") -> "Google: Rechnungskonto fehlt oder ist nicht aktiv"
            m.contains("android") || m.contains("blocked") || m.contains("referer") -> "Google: Schlüssel-Einschränkung passt nicht zur App"
            code == 429 || m.contains("quota") -> "Google: Tageslimit in der Google-Konsole erreicht"
            else -> "Google-Fehler $code: ${msg.take(120)}"
        }
    }

    // ---------- Antwort lesen ----------
    private fun parse(text: String): List<OsmPlace> {
        val arr = JSONObject(text).optJSONArray("places") ?: return emptyList()
        val out = ArrayList<OsmPlace>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val status = p.optString("businessStatus")
            if (status == "CLOSED_PERMANENTLY") continue
            val loc = p.optJSONObject("location") ?: continue
            val types = ArrayList<String>()
            p.optJSONArray("types")?.let { t -> for (k in 0 until t.length()) types.add(t.optString(k)) }
            p.optString("primaryType").takeIf { it.isNotEmpty() }?.let { types.add(it) }

            val tags = HashMap<String, String>()
            val name = p.optJSONObject("displayName")?.optString("text")?.takeIf { it.isNotEmpty() }
            name?.let { tags["name"] = it }
            mapTypes(types, name ?: "", tags)
            tags["_gtypes"] = types.joinToString(",")
            if (status == "CLOSED_TEMPORARILY") tags["_temp_closed"] = "1"
            p.optJSONObject("primaryTypeDisplayName")?.optString("text")?.takeIf { it.isNotEmpty() }
                ?.let { tags["_gtype_name"] = it } // deutsche Bezeichnung, z. B. "Blumengeschäft"

            val first = p.optString("formattedAddress").substringBefore(",").trim()
            if (first.isNotEmpty()) {
                val a = AddressParser.parse(first)
                if (a.street.isNotEmpty()) tags["addr:street"] = a.street.replace(Regex("""(?i)str\.$"""), "straße")
                a.rawNumber?.let { tags["addr:housenumber"] = it }
            }
            p.optJSONObject("currentOpeningHours")?.let { oh ->
                if (oh.has("openNow")) tags["_open"] = if (oh.optBoolean("openNow")) "1" else "0"
                oh.optJSONArray("weekdayDescriptions")?.let { wd ->
                    val list = ArrayList<String>()
                    for (k in 0 until wd.length()) list.add(wd.optString(k))
                    if (list.size == 7) tags["_week"] = list.joinToString("|")
                }
            }
            p.optJSONObject("regularOpeningHours")?.optJSONArray("periods")?.let { tags["_periods"] = it.toString() }
            out.add(OsmPlace("g/" + p.optString("id"), loc.optDouble("latitude"), loc.optDouble("longitude"), tags))
        }
        return out
    }

    // ---------- Zwischenspeicher ----------
    private fun toJson(list: List<OsmPlace>): JSONArray {
        val a = JSONArray()
        for (p in list) {
            val t = JSONObject()
            for ((k, v) in p.tags) if (k != "_open") t.put(k, v) // "jetzt offen" nicht speichern
            a.put(JSONObject().put("id", p.id).put("lat", p.lat).put("lon", p.lon).put("tags", t))
        }
        return a
    }

    private fun readCache(key: String): List<OsmPlace>? {
        val raw = prefs?.getString(key, null) ?: return null
        return try {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.getLong("t") > CACHE_DAYS * 24L * 3600 * 1000) return null
            val arr = o.getJSONArray("p")
            val out = ArrayList<OsmPlace>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val t = e.getJSONObject("tags")
                val tags = HashMap<String, String>()
                val keys = t.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    tags[k] = t.optString(k)
                }
                // "jetzt offen" aus den gespeicherten Zeiten neu berechnen
                tags["_periods"]?.let { per -> openFromPeriods(per)?.let { tags["_open"] = if (it) "1" else "0" } }
                out.add(OsmPlace(e.getString("id"), e.getDouble("lat"), e.getDouble("lon"), tags))
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Google-Zeiten: Tag 0 = Sonntag. Liefert null, wenn nicht auswertbar. */
    private fun openFromPeriods(json: String): Boolean? = try {
        val arr = JSONArray(json)
        val now = Calendar.getInstance()
        val week = 7 * 1440
        val nowMin = (now.get(Calendar.DAY_OF_WEEK) - 1) * 1440 + now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        var open = false
        for (i in 0 until arr.length()) {
            val per = arr.getJSONObject(i)
            val o = per.getJSONObject("open")
            val start = o.getInt("day") * 1440 + o.optInt("hour") * 60 + o.optInt("minute")
            val c = per.optJSONObject("close")
            if (c == null) { open = true; break } // rund um die Uhr
            var end = c.getInt("day") * 1440 + c.optInt("hour") * 60 + c.optInt("minute")
            if (end <= start) end += week
            if ((nowMin in start until end) || (nowMin + week in start until end)) { open = true; break }
        }
        open
    } catch (e: Exception) {
        null
    }

    /** Heutige Zeile aus Googles Wochenliste, z. B. "heute 18:00–02:00" */
    fun todayText(tags: Map<String, String>): String? {
        val week = tags["_week"]?.split("|") ?: return null
        if (week.size != 7) return null
        val idx = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 // Google: Montag zuerst
        val line = week[idx].substringAfter(":", week[idx]).trim()
        return "heute $line"
    }

    /** Google-Kategorien in die gleichen Kategorien wie bei OpenStreetMap übersetzen */
    private fun mapTypes(types: List<String>, name: String, tags: MutableMap<String, String>) {
        fun has(vararg t: String) = t.any { it in types }
        val n = name.lowercase()
        when {
            n.contains("laufhaus") || n.contains("bordell") -> tags["amenity"] = "brothel"
            has("adult_entertainment_club", "adult_entertainment_store") -> tags["amenity"] = "stripclub"
            has("night_club", "dance_hall") -> tags["amenity"] = "nightclub"
            has("bar", "pub", "wine_bar", "cocktail_bar", "sports_bar", "lounge_bar", "beer_garden",
                "irish_pub", "hookah_bar", "bar_and_grill", "brewpub") -> tags["amenity"] = "bar"
            has("casino") -> tags["amenity"] = "casino"
            has("synagogue") -> { tags["amenity"] = "place_of_worship"; tags["religion"] = "jewish" }
            has("mosque") -> { tags["amenity"] = "place_of_worship"; tags["religion"] = "muslim" }
            has("church", "place_of_worship", "hindu_temple", "buddhist_temple") ->
                tags["amenity"] = "place_of_worship"
            has("event_venue", "concert_hall", "banquet_hall", "wedding_venue", "performing_arts_theater",
                "amphitheatre", "convention_center") -> tags["amenity"] = "events_venue"
            has("hotel", "lodging", "hostel", "motel", "bed_and_breakfast", "guest_house", "inn") ->
                tags["tourism"] = "hotel"
            has("hospital") -> tags["amenity"] = "hospital"
            has("movie_theater") -> tags["amenity"] = "cinema"
            has("massage", "spa", "sauna", "wellness_center") -> tags["shop"] = "massage"
            has("gym", "fitness_center", "yoga_studio") -> tags["leisure"] = "fitness_centre"
            has("beauty_salon", "nail_salon", "tattoo_shop", "hair_salon", "barber_shop") -> tags["shop"] = "beauty"
            has("restaurant", "cafe", "coffee_shop", "fast_food_restaurant", "meal_takeaway", "bistro") ||
                    types.any { it.endsWith("_restaurant") } -> tags["amenity"] = "restaurant"
        }
    }
}
