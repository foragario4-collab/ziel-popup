package at.agar.zielpopup

/**
 * Findet in den Texten des Mystro-Bildschirms Abholung und Ziel.
 *
 * Mystro zeigt zwei Varianten:
 *  A) Echte Fahrtanfrage:   "1 Min. • 0,7 km" · Abholadresse · Zieladresse        (nur EINE Zeit/Strecken-Zeile)
 *  B) Nachträgliche Ansicht: "2 Min. • 1,3 km" · Abholadresse · "22 Min. • 24,4 km" · Zieladresse
 * In beiden Fällen gilt: Abholung kommt zuerst, das Ziel danach.
 *
 * Abgedeckte Sonderfälle: "< 1 Min.", "1 Std. 5 Min.", "850 m", Zeit/Strecke als getrennte Texte,
 * doppelt gezeichnete Karten (Übergang/Animation), halb geladene Karten, Fahrgast-Namen.
 */
object MystroParser {

    data class Trip(val pickup: String?, val dest: String, val etaMin: Int?)

    // Zeit-Teil: "< 1 Min.", "12 Min", "1 Std. 5 Min.", "1 h 5 min", "1 Std."
    private const val TIME =
        """[<~≈]?\s*(?:\d+\s*(?:Std\.?|Stunden?|h)\s*)?(?:\d+\s*(?:Min\.?|Minuten?))?"""
    // Strecken-Teil: "24,4 km", "850 m"
    private const val DIST = """[\d.,]+\s*(?:km|m)"""

    private val tripInfo = Regex("""^$TIME\s*[•·]\s*$DIST$""", RegexOption.IGNORE_CASE)
    private val timeOnly = Regex("""^$TIME$""", RegexOption.IGNORE_CASE)
    private val distOnly = Regex("""^$DIST$""", RegexOption.IGNORE_CASE)
    private val hasTimeWord = Regex("""(?i)(min|std|stunde|\dh)""")

    private val streetWord = Regex(
        """(?i)(gasse|straße|strasse|str\.|platz|weg|allee|ring|gürtel|guertel|lände|laende|zeile|markt|kai|promenade|steig|brücke|hof)"""
    )

    /** Knöpfe und Beschriftungen, die nie eine Adresse sind */
    private val STOP = setOf(
        "karte", "online gehen", "offline gehen", "pausieren", "fortsetzen", "annehmen", "ablehnen",
        "karte zurücksetzen", "offline bleiben wenn die fahrt endet", "gesamtpreis", "€/std.", "€/km",
        "€-", "uberx", "uber", "bolt", "comfort", "uber comfort", "uber green", "xl", "uberxl",
        "google maps", "keine internetverbindung! bitte warten sie."
    )

    private val namePattern = Regex("""^\p{Lu}\p{Ll}+(\s\p{Lu}\p{Ll}+)?\s\p{Lu}\.?$""")
    private val numbersOnly = Regex("""^[\d.,\s]+$""")

    /** Ist das eine Zeit/Strecken-Zeile ("12 Min. • 4,3 km")? */
    private fun isTripLine(s: String): Boolean = tripInfo.matches(s) && hasTimeWord.containsMatchIn(s)

    private fun isNoise(s: String): Boolean {
        val l = s.trim().lowercase()
        if (l in STOP) return true
        if (l.startsWith("€") || l.endsWith("€")) return true
        if (numbersOnly.matches(l)) return true // reine Zahlen (Bewertung, IDs)
        if (l.length <= 2) return true
        // Name des Fahrgasts, z. B. "Carrie T" oder "Hirokazu K." – nie eine Adresse
        if (namePattern.matches(s.trim())) return true
        return false
    }

    /** Falls Mystro "2 Min." "•" "1,3 km" als einzelne Texte liefert -> zu einer Zeile zusammenfügen */
    private fun normalize(texts: List<String>): List<String> {
        val t = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < t.size) {
            if (timeOnly.matches(t[i]) && hasTimeWord.containsMatchIn(t[i])) {
                var j = i + 1
                if (j < t.size && (t[j] == "•" || t[j] == "·")) j++
                if (j < t.size && distOnly.matches(t[j])) {
                    out.add("${t[i]} • ${t[j]}")
                    i = j + 1
                    continue
                }
            }
            out.add(t[i])
            i++
        }
        // direkt aufeinanderfolgende Doppelungen entfernen ("Karte", "Karte")
        return out.filterIndexed { k, s -> k == 0 || s != out[k - 1] }
    }

    /** Minuten aus "1 Std. 5 Min." / "12 Min." / "< 1 Min." */
    private fun minutes(line: String): Int? {
        val timePart = line.substringBefore("•").substringBefore("·")
        val h = Regex("""(?i)(\d+)\s*(?:Std|Stunde|h)""").find(timePart)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(?i)(\d+)\s*Min""").find(timePart)?.groupValues?.get(1)?.toIntOrNull()
        if (m == null && h == 0) return null
        return h * 60 + (m ?: 0)
    }

    /**
     * Teilt den Bildschirmtext an jeder Zeit/Strecken-Zeile in Blöcke.
     *  - Block mit 2+ Zeilen  -> Abholung, Ziel                  (Variante A)
     *  - Block mit 1 Zeile + nächster Block -> Ziel im nächsten Block (Variante B)
     * Mystro zeichnet die Karte manchmal doppelt – darum gewinnt der LETZTE Treffer.
     */
    fun findTrip(texts: List<String>): Trip? {
        val t = normalize(texts)
        val idx = t.indices.filter { isTripLine(t[it]) }
        // Ohne Zeit/Strecken-Zeile ist es kein Fahrt-Bildschirm -> lieber nichts als etwas Falsches
        if (idx.isEmpty()) return null

        val blocks = idx.mapIndexed { k, i ->
            val stop = if (k + 1 < idx.size) idx[k + 1] else t.size
            t.subList(i + 1, stop).filter { !isNoise(it) }
        }
        var found: Trip? = null
        var k = 0
        while (k < blocks.size) {
            val b = blocks[k]
            val next = blocks.getOrNull(k + 1)
            if (b.size == 1 && next != null && next.isNotEmpty() && next[0] != b[0]) {
                val m1 = minutes(t[idx[k]])
                val m2 = minutes(t[idx[k + 1]])
                val eta = if (m1 != null && m2 != null) (m1 + m2).takeIf { it in 1..300 } else null
                found = Trip(clean(b[0]), clean(next[0]), eta)
                k += 2
                continue
            }
            if (b.size >= 2) found = Trip(clean(b[0]), clean(b[1]), null)
            k++
        }
        return found
    }

    fun findDestination(texts: List<String>): String? = findTrip(texts)?.dest

    /** Ankunft am Ziel nur, wenn Mystro beide Zeiten zeigt (Anfahrt + Fahrt) */
    fun findEtaMinutes(texts: List<String>): Int? = findTrip(texts)?.etaMin

    /** "Orpheum Wien GmbH, Orpheum, Steigenteschgasse 94B, Wien" -> "Steigenteschgasse 94B, Wien" */
    private fun clean(s: String): String {
        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val streetPart = parts.lastOrNull { streetWord.containsMatchIn(it) } ?: return s.trim()
        val i = parts.lastIndexOf(streetPart)
        return (listOf(streetPart) + parts.drop(i + 1)).joinToString(", ")
    }
}
