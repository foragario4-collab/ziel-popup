package at.agar.zielpopup

/** Wiener Gemeindebezirke – aus der Postleitzahl (1xx0 = xx. Bezirk) */
object Bezirke {
    private val NAMES = listOf(
        "Innere Stadt", "Leopoldstadt", "Landstraße", "Wieden", "Margareten", "Mariahilf",
        "Neubau", "Josefstadt", "Alsergrund", "Favoriten", "Simmering", "Meidling",
        "Hietzing", "Penzing", "Rudolfsheim-Fünfhaus", "Ottakring", "Hernals", "Währing",
        "Döbling", "Brigittenau", "Floridsdorf", "Donaustadt", "Liesing"
    )

    /** "1020" -> 2, sonst null */
    fun number(postcode: String?): Int? {
        val m = Regex("""^1(\d\d)0$""").find(postcode?.trim() ?: return null) ?: return null
        return m.groupValues[1].toInt().takeIf { it in 1..23 }
    }

    /** "1020" -> "2. Bezirk · Leopoldstadt" */
    fun label(postcode: String?): String? {
        val n = number(postcode) ?: return null
        return "$n. Bezirk · ${NAMES[n - 1]}"
    }

    /** Mehrere Postleitzahlen (lange Straße über mehrere Bezirke) -> "7., 15. und 16. Bezirk" */
    fun labelMany(postcodes: Collection<String>): String? {
        val nums = postcodes.mapNotNull { number(it) }.distinct().sorted()
        return when (nums.size) {
            0 -> null
            1 -> "${nums[0]}. Bezirk · ${NAMES[nums[0] - 1]}"
            else -> nums.dropLast(1).joinToString(", ") { "$it." } + " und ${nums.last()}. Bezirk"
        }
    }
}
