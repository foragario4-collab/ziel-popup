package at.agar.zielpopup

/** Was ist dieser Ort? – deutsche Bezeichnung + Symbol für (fast) jede Art von Betrieb */
object Kinds {
    private val OSM: Map<String, Pair<String, String>> = mapOf(
        // Geschäfte
        "shop:florist" to ("💐" to "Blumengeschäft"), "shop:supermarket" to ("🛒" to "Supermarkt"),
        "shop:convenience" to ("🏪" to "Nahversorger"), "shop:bakery" to ("🥐" to "Bäckerei"),
        "shop:butcher" to ("🥩" to "Fleischerei"), "shop:kiosk" to ("📰" to "Trafik"),
        "shop:tobacco" to ("📰" to "Trafik"), "shop:hairdresser" to ("💇" to "Friseur"),
        "shop:clothes" to ("👗" to "Modegeschäft"), "shop:shoes" to ("👟" to "Schuhgeschäft"),
        "shop:mobile_phone" to ("📱" to "Handyshop"), "shop:electronics" to ("🔌" to "Elektronik"),
        "shop:chemist" to ("🧴" to "Drogerie"), "shop:optician" to ("👓" to "Optiker"),
        "shop:jewelry" to ("💍" to "Juwelier"), "shop:car" to ("🚗" to "Autohändler"),
        "shop:car_repair" to ("🔧" to "Autowerkstatt"), "shop:alcohol" to ("🍷" to "Getränkehandel"),
        "shop:beverages" to ("🍷" to "Getränkehandel"), "shop:books" to ("📚" to "Buchhandlung"),
        "shop:furniture" to ("🛋️" to "Möbelgeschäft"), "shop:hardware" to ("🔨" to "Baumarkt"),
        "shop:doityourself" to ("🔨" to "Baumarkt"), "shop:pet" to ("🐾" to "Tierhandlung"),
        "shop:laundry" to ("🧺" to "Wäscherei"), "shop:dry_cleaning" to ("🧺" to "Putzerei"),
        "shop:travel_agency" to ("✈️" to "Reisebüro"), "shop:gift" to ("🎁" to "Geschenkeladen"),
        "shop:department_store" to ("🏬" to "Kaufhaus"), "shop:mall" to ("🏬" to "Einkaufszentrum"),
        "shop:cosmetics" to ("💄" to "Kosmetik"), "shop:perfumery" to ("💄" to "Parfümerie"),
        "shop:sports" to ("⚽" to "Sportgeschäft"), "shop:toys" to ("🧸" to "Spielwaren"),
        "shop:greengrocer" to ("🥦" to "Obst & Gemüse"), "shop:deli" to ("🧀" to "Feinkost"),
        "shop:pastry" to ("🍰" to "Konditorei"), "shop:wine" to ("🍷" to "Weinhandlung"),
        "shop:erotic" to ("🔞" to "Erotikshop"), "shop:massage" to ("💆" to "Massagestudio"),
        "shop:tattoo" to ("🖋️" to "Tattoostudio"), "shop:beauty" to ("💅" to "Kosmetikstudio"),
        // Einrichtungen
        "amenity:pharmacy" to ("💊" to "Apotheke"), "amenity:doctors" to ("🩺" to "Arztpraxis"),
        "amenity:dentist" to ("🦷" to "Zahnarzt"), "amenity:clinic" to ("🏥" to "Klinik"),
        "amenity:hospital" to ("🏥" to "Spital"), "amenity:bank" to ("🏦" to "Bank"),
        "amenity:post_office" to ("📮" to "Post"), "amenity:school" to ("🏫" to "Schule"),
        "amenity:kindergarten" to ("🧸" to "Kindergarten"), "amenity:university" to ("🎓" to "Universität"),
        "amenity:college" to ("🎓" to "Hochschule"), "amenity:police" to ("🚓" to "Polizei"),
        "amenity:fire_station" to ("🚒" to "Feuerwehr"), "amenity:townhall" to ("🏛️" to "Amt"),
        "amenity:courthouse" to ("⚖️" to "Gericht"), "amenity:library" to ("📚" to "Bibliothek"),
        "amenity:fuel" to ("⛽" to "Tankstelle"), "amenity:parking" to ("🅿️" to "Parkplatz"),
        "amenity:car_wash" to ("🚿" to "Autowaschanlage"), "amenity:nursing_home" to ("🏡" to "Pflegeheim"),
        "amenity:social_facility" to ("🤝" to "Sozialeinrichtung"), "amenity:ice_cream" to ("🍦" to "Eissalon"),
        "amenity:veterinary" to ("🐾" to "Tierarzt"), "amenity:embassy" to ("🏳️" to "Botschaft"),
        "amenity:marketplace" to ("🧺" to "Markt"), "amenity:community_centre" to ("🏠" to "Gemeinschaftszentrum"),
        "amenity:arts_centre" to ("🎨" to "Kulturzentrum"), "amenity:theatre" to ("🎭" to "Theater"),
        "amenity:cinema" to ("🎬" to "Kino"), "amenity:restaurant" to ("🍽️" to "Restaurant"),
        "amenity:cafe" to ("☕" to "Café"), "amenity:fast_food" to ("🍔" to "Imbiss"),
        "amenity:events_venue" to ("🎤" to "Veranstaltungsort"),
        "healthcare:doctor" to ("🩺" to "Arztpraxis"), "healthcare:physiotherapist" to ("💪" to "Physiotherapie"),
        // Tourismus / Freizeit / Verkehr
        "tourism:hotel" to ("🏨" to "Hotel"), "tourism:hostel" to ("🏨" to "Hostel"),
        "tourism:guest_house" to ("🏨" to "Pension"), "tourism:apartment" to ("🏢" to "Ferienwohnung"),
        "tourism:museum" to ("🖼️" to "Museum"), "tourism:attraction" to ("📸" to "Sehenswürdigkeit"),
        "tourism:gallery" to ("🖼️" to "Galerie"),
        "leisure:park" to ("🌳" to "Park"), "leisure:sports_centre" to ("🏟️" to "Sportzentrum"),
        "leisure:stadium" to ("🏟️" to "Stadion"), "leisure:swimming_pool" to ("🏊" to "Bad"),
        "leisure:fitness_centre" to ("💪" to "Fitnessstudio"),
        "railway:station" to ("🚉" to "Bahnhof"), "public_transport:station" to ("🚉" to "Station"),
        "aeroway:aerodrome" to ("✈️" to "Flughafen"), "aeroway:terminal" to ("✈️" to "Flughafen-Terminal")
    )

    private val GOOGLE_EMOJI: Map<String, String> = mapOf(
        "florist" to "💐", "supermarket" to "🛒", "grocery_store" to "🛒", "convenience_store" to "🏪",
        "bakery" to "🥐", "pharmacy" to "💊", "drugstore" to "🧴", "doctor" to "🩺", "dentist" to "🦷",
        "hospital" to "🏥", "bank" to "🏦", "atm" to "🏧", "post_office" to "📮", "school" to "🏫",
        "university" to "🎓", "hair_care" to "💇", "hair_salon" to "💇", "clothing_store" to "👗",
        "shoe_store" to "👟", "electronics_store" to "🔌", "jewelry_store" to "💍", "car_repair" to "🔧",
        "car_dealer" to "🚗", "gas_station" to "⛽", "parking" to "🅿️", "police" to "🚓", "lawyer" to "⚖️",
        "book_store" to "📚", "furniture_store" to "🛋️", "hardware_store" to "🔨", "pet_store" to "🐾",
        "laundry" to "🧺", "travel_agency" to "✈️", "gift_shop" to "🎁", "shopping_mall" to "🏬",
        "department_store" to "🏬", "museum" to "🖼️", "tourist_attraction" to "📸", "park" to "🌳",
        "train_station" to "🚉", "subway_station" to "🚇", "bus_station" to "🚌", "airport" to "✈️",
        "veterinary_care" to "🐾", "liquor_store" to "🍷", "cafe" to "☕", "restaurant" to "🍽️"
    )

    /** Symbol + Bezeichnung, oder null wenn es nur ein Wohnhaus/eine Adresse ist */
    fun of(t: Map<String, String>): Pair<String, String>? {
        // 1. Google liefert eine fertige deutsche Bezeichnung ("Blumengeschäft")
        t["_gtype_name"]?.takeIf { it.isNotBlank() }?.let { label ->
            val emoji = Analyzer.classify(t)?.emoji
                ?: t["_gtypes"]?.split(",")?.firstNotNullOfOrNull { GOOGLE_EMOJI[it] }
                ?: "🏢"
            return emoji to label
        }
        // 2. Unsere Besuchs-Kategorien (Bar, Club, Synagoge …)
        Analyzer.classify(t)?.let { return it.emoji to it.label }
        // 3. Alles andere aus OpenStreetMap
        for (k in listOf("amenity", "shop", "healthcare", "tourism", "leisure", "railway", "public_transport", "aeroway")) {
            val v = t[k] ?: continue
            OSM["$k:$v"]?.let { return it }
        }
        if (t["shop"] != null) return "🛍️" to "Geschäft"
        if (t["office"] != null) return "🏢" to "Büro / Firma"
        if (t["craft"] != null) return "🛠️" to "Handwerksbetrieb"
        if (t["_gtypes"] != null) return "🏢" to "Betrieb"
        return null
    }

    /** "✡️ Synagoge · Or Chadasch" */
    fun describe(t: Map<String, String>): String {
        val (emoji, label) = of(t) ?: ("📍" to "Ort")
        val name = t["name"]
        return if (name != null && !name.equals(label, ignoreCase = true)) "$emoji $label · $name" else "$emoji $label"
    }

    /** Ist das ein echter Betrieb/Ort (und nicht nur ein Wohnhaus)? */
    fun isRealPlace(t: Map<String, String>) = of(t) != null
}
