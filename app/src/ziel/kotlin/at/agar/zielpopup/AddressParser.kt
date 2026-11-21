package at.agar.zielpopup

data class ParsedAddress(
    val street: String,
    val rawNumber: String?,          // z. B. "172-174"
    val houseNumbers: List<String>,  // z. B. [172, 173, 174] – leer = keine Hausnummer
    val postcode: String?
)

object AddressParser {

    fun parse(input: String): ParsedAddress {
        var s = input.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim().trim(',').trim()
        s = s.replace(Regex("""(?i)[,\s]*(österreich|austria)$"""), "").trim()
        s = s.replace(Regex("""(?i)^z\.\s*b\.\s*"""), "").trim() // "z. B." vorne ignorieren

        var postcode: String? = null

        // Form "1090 Wien, Straße 1"
        Regex("""(?i)^(1[0-2]\d0)\s*(wien)?\s*,\s*(.+)$""").find(s)?.let {
            postcode = it.groupValues[1]
            s = it.groupValues[3]
        }
        // Form "Straße 1, 1090 Wien" oder "Straße 1 1090 Wien"
        Regex("""(?i)^(.+?)[,\s]+(1[0-2]\d0)(\s+wien)?$""").find(s)?.let {
            postcode = it.groupValues[2]
            s = it.groupValues[1]
        }
        s = s.replace(Regex("""(?i)[,\s]+wien$"""), "").trim().trim(',').trim()

        // Hausnummer am Ende, auch Bereiche (172-174) und Wiener Stiege/Tür (12/3/5)
        val m = Regex("""^(.*?)[\s,]+(\d+[a-zA-Z]?(?:\s*[-–]\s*\d+[a-zA-Z]?)?)(?:\s*/[\w/\s]*)?$""").find(s)
            ?: return ParsedAddress(s, null, emptyList(), postcode)

        val street = m.groupValues[1].trim().trim(',').trim()
        val raw = m.groupValues[2].replace(" ", "")
        return ParsedAddress(street, raw, expandNumbers(raw), postcode)
    }

    private fun firstInt(s: String): Int? = Regex("""\d+""").find(s)?.value?.toIntOrNull()

    private fun expandNumbers(raw: String): List<String> {
        val parts = raw.split(Regex("""[-–]"""))
        if (parts.size == 2) {
            val a = firstInt(parts[0])
            val b = firstInt(parts[1])
            if (a != null && b != null && b >= a && b - a <= 10) {
                return (a..b).map { it.toString() }
            }
        }
        return listOfNotNull(firstInt(raw)?.toString())
    }

    /** Suchmuster für den Straßennamen, egal ob mit Bindestrich, Leerzeichen, ß oder ss */
    fun streetRegex(street: String): String {
        var s = street.trim().lowercase()
        s = s.replace(Regex("""str\.?$"""), "straße").replace("strasse", "straße")
        val sb = StringBuilder("^")
        var lastSep = false
        for (c in s) {
            if (c == ' ' || c == '-') {
                if (!lastSep) sb.append("[- ]?")
                lastSep = true
            } else {
                lastSep = false
                when {
                    c == 'ß' -> sb.append("(ß|ss)")
                    c == '.' -> sb.append(".?")
                    c.isLetterOrDigit() -> sb.append(c)
                }
            }
        }
        sb.append('$')
        return sb.toString()
    }
}
