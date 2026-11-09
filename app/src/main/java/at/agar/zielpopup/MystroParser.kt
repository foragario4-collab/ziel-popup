package at.agar.zielpopup

/**
 * Findet in den Texten des Mystro-Bildschirms die Zieladresse.
 *
 * Mystro zeigt zwei Varianten:
 *  A) Echte Fahrtanfrage:   "1 Min. • 0,7 km" · Abholadresse · Zieladresse        (nur EINE Min/km-Zeile)
 *  B) Nachträgliche Ansicht: "2 Min. • 1,3 km" · Abholadresse · "22 Min. • 24,4 km" · Zieladresse
 * In beiden Fällen gilt: Abholung kommt zuerst, das Ziel danach.
 */
object MystroParser {
    private val tripInfo = Regex("""^\d+\s*Min\.?\s*[•·]\s*[\d.,]+\s*km$""", RegexOption.IGNORE_CASE)
    private val minOnly = Regex("""^\d+\s*Min\.?$""", RegexOption.IGNORE_CASE)
    private val kmOnly = Regex("""^[\d.,]+\s*km$""", RegexOption.IGNORE_CASE)
    private val streetWord = Regex(
        """(?i)(gasse|straße|strasse|str\.|platz|weg|allee|ring|gürtel|guertel|lände|laende|zeile|markt|kai|promenade|steig|brücke|hof)"""
    )

    /** Knöpfe und Beschriftungen, die nie eine Adresse sind */
    private val STOP = setOf(
        "karte", "online gehen", "offline gehen", "pausieren", "fortsetzen", "annehmen", "ablehnen",
        "karte zurücksetzen", "offline bleiben wenn die fahrt endet", "gesamtpreis", "€/std.", "€/km",
        "€-", "uberx", "uber", "bolt", "comfort", "uber comfort", "uber green", "xl", "uberxl"
    )

    private fun isNoise(s: String): Boolean {
        val l = s.trim().lowercase()
        if (l in STOP) return true
        if (l.startsWith("€")) return true
        if (Regex("""^[\d.,\s]+$""").matches(l)) return true // reine Zahlen (Bewertung, IDs)
        if (l.length <= 2) return true
        return false
    }

    /** Falls Mystro "2 Min." "•" "1,3 km" als einzelne Texte liefert -> zu einer Zeile zusammenfügen */
    private fun normalize(texts: List<String>): List<String> {
        val t = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < t.size) {
            if (minOnly.matches(t[i])) {
                var j = i + 1
                if (j < t.size && (t[j] == "•" || t[j] == "·")) j++
                if (j < t.size && kmOnly.matches(t[j])) {
                    out.add("${t[i]} • ${t[j]}")
                    i = j + 1
                    continue
                }
            }
            out.add(t[i])
            i++
        }
        return out
    }

    fun findDestination(texts: List<String>): String? {
        val t = normalize(texts)
        val idx = t.indices.filter { tripInfo.matches(t[it]) }
        // Ohne "Min • km" ist es kein Fahrt-Bildschirm -> lieber nichts anzeigen als etwas Falsches
        if (idx.isEmpty()) return null

        if (idx.size >= 2) {
            // Variante B: Ziel steht direkt nach der zweiten Min/km-Zeile
            return t.drop(idx.last() + 1).firstOrNull { !isNoise(it) }?.let { clean(it) }
        }
        // Variante A: nach der einzigen Min/km-Zeile kommen Abholung und dann Ziel
        val after = t.drop(idx.first() + 1).filter { !isNoise(it) && !tripInfo.matches(it) }
        return after.getOrNull(1)?.let { clean(it) }
    }

    /** Ankunft am Ziel nur, wenn Mystro beide Zeiten zeigt (Anfahrt + Fahrt) */
    fun findEtaMinutes(texts: List<String>): Int? {
        val mins = normalize(texts).filter { tripInfo.matches(it) }
            .mapNotNull { Regex("""^\d+""").find(it)?.value?.toIntOrNull() }
        if (mins.size < 2) return null
        return mins.takeLast(2).sum().takeIf { it in 1..240 }
    }

    /** "Orpheum Wien GmbH, Orpheum, Steigenteschgasse 94B, Wien" -> "Steigenteschgasse 94B, Wien" */
    private fun clean(s: String): String {
        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val streetPart = parts.lastOrNull { streetWord.containsMatchIn(it) } ?: return s.trim()
        val i = parts.lastIndexOf(streetPart)
        return (listOf(streetPart) + parts.drop(i + 1)).joinToString(", ")
    }
}
