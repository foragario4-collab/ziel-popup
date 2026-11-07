package at.agar.zielpopup

/** Findet in den Texten des Mystro-Bildschirms die Zieladresse */
object MystroParser {
    // z. B. "22 Min. • 24,4 km"
    private val tripInfo = Regex("""^\d+\s*Min\.?\s*[•·]\s*[\d.,]+\s*km$""", RegexOption.IGNORE_CASE)
    private val streetWord = Regex(
        """(?i)(gasse|straße|strasse|str\.|platz|weg|allee|ring|gürtel|guertel|lände|laende|zeile|markt|kai|promenade|steig|brücke|hof)"""
    )

    fun findDestination(texts: List<String>): String? {
        val t = texts.map { it.trim() }.filter { it.isNotEmpty() }
        // 1. Mystro zeigt: Abholung (Min • km, Adresse), Ziel (Min • km, Adresse) -> Text nach dem LETZTEN "Min • km"
        val idx = t.indices.filter { tripInfo.matches(t[it]) }
        if (idx.size >= 2) {
            val i = idx.last()
            if (i + 1 < t.size) return clean(t[i + 1])
        }
        // 2. Sonst: die letzte Zeile, die wie eine Adresse mit Hausnummer aussieht
        return t.lastOrNull { looksLikeAddress(it) }?.let { clean(it) }
    }

    private fun looksLikeAddress(s: String) =
        s.length < 140 && streetWord.containsMatchIn(s) && Regex("""\d""").containsMatchIn(s)

    /** "Orpheum Wien GmbH, Orpheum, Steigenteschgasse 94B, Wien" -> "Steigenteschgasse 94B, Wien" */
    private fun clean(s: String): String {
        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val streetPart = parts.lastOrNull { streetWord.containsMatchIn(it) } ?: return s.trim()
        val i = parts.lastIndexOf(streetPart)
        return (listOf(streetPart) + parts.drop(i + 1)).joinToString(", ")
    }
}
