package at.agar.zielpopup

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Places API (New) – kennt fast jedes Lokal inkl. "jetzt offen".
 * Läuft nur übers Internet, braucht KEINE Google-Play-Dienste.
 */
object GoogleClient {
    private const val FIELDS =
        "places.id,places.displayName,places.types,places.primaryType,places.formattedAddress," +
                "places.location,places.businessStatus,places.currentOpeningHours.openNow"

    fun nearby(key: String, lat: Double, lon: Double, radiusM: Double): List<OsmPlace> {
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
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Goog-Api-Key", key.trim())
            setRequestProperty("X-Goog-FieldMask", FIELDS)
        }
        val text: String
        val code: Int
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
            throw IOException("Google-Fehler $code: ${msg.take(150)}")
        }

        val arr = JSONObject(text).optJSONArray("places") ?: return emptyList()
        val out = ArrayList<OsmPlace>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (p.optString("businessStatus") == "CLOSED_PERMANENTLY") continue
            val loc = p.optJSONObject("location") ?: continue
            val types = ArrayList<String>()
            p.optJSONArray("types")?.let { t -> for (k in 0 until t.length()) types.add(t.optString(k)) }
            p.optString("primaryType").takeIf { it.isNotEmpty() }?.let { types.add(it) }

            val tags = HashMap<String, String>()
            val name = p.optJSONObject("displayName")?.optString("text")?.takeIf { it.isNotEmpty() }
            name?.let { tags["name"] = it }
            mapTypes(types, name ?: "", tags)

            // Adresse "Karl-Gunsam-Gasse 1, 1110 Wien, Österreich" -> Straße + Hausnummer
            val first = p.optString("formattedAddress").substringBefore(",").trim()
            if (first.isNotEmpty()) {
                val a = AddressParser.parse(first)
                if (a.street.isNotEmpty()) {
                    tags["addr:street"] = a.street.replace(Regex("""(?i)str\.$"""), "straße")
                }
                a.rawNumber?.let { tags["addr:housenumber"] = it }
            }
            p.optJSONObject("currentOpeningHours")?.let { oh ->
                if (oh.has("openNow")) tags["_open"] = if (oh.optBoolean("openNow")) "1" else "0"
            }
            out.add(OsmPlace("g/" + p.optString("id"), loc.optDouble("latitude"), loc.optDouble("longitude"), tags))
        }
        return out
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
