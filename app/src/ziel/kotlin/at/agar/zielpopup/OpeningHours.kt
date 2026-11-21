package at.agar.zielpopup

import java.util.Calendar

enum class OpenState { OPEN, CLOSED, UNKNOWN }

/**
 * Liest OpenStreetMap-Öffnungszeiten wie "Mo-Sa 18:00-04:00; Su off".
 * Alles, was zu kompliziert ist, wird als UNKNOWN (unbekannt) gemeldet.
 */
object OpeningHours {

    private val dayNames = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    private class Rule(val days: Set<Int>?, val ranges: List<Pair<Int, Int>>, val off: Boolean)

    fun state(raw: String?, now: Calendar = Calendar.getInstance()): OpenState {
        if (raw.isNullOrBlank()) return OpenState.UNKNOWN
        return try {
            evaluate(parse(raw.trim()), now)
        } catch (e: Exception) {
            OpenState.UNKNOWN
        }
    }

    private fun parse(raw: String): List<Rule> {
        val rules = ArrayList<Rule>()
        for (part in raw.split(";", "||").map { it.trim() }.filter { it.isNotEmpty() }) {
            if (part == "24/7") {
                rules.add(Rule(null, listOf(0 to 1440), false)); continue
            }
            val dayTok = """(?:Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)(?:\s*-\s*(?:Mo|Tu|We|Th|Fr|Sa|Su))?"""
            val m = Regex("""^($dayTok(?:\s*,\s*$dayTok)*)\s*(.*)$""").find(part)
            var days: Set<Int>? = null
            var timePart = part
            if (m != null) {
                val set = HashSet<Int>()
                var onlyHolidays = true
                for (tok in m.groupValues[1].split(",").map { it.trim() }) {
                    if (tok == "PH" || tok == "SH") continue
                    onlyHolidays = false
                    val ends = tok.split("-").map { it.trim() }
                    val a = dayNames.indexOf(ends[0])
                    val b = if (ends.size > 1) dayNames.indexOf(ends[1]) else a
                    if (a < 0 || b < 0) throw IllegalArgumentException()
                    var d = a
                    while (true) { set.add(d); if (d == b) break; d = (d + 1) % 7 }
                }
                if (onlyHolidays) continue // reine Feiertagsregel ignorieren
                days = set
                timePart = m.groupValues[2].trim()
            }
            val lower = timePart.lowercase()
            if (lower == "off" || lower == "closed") {
                rules.add(Rule(days, emptyList(), true)); continue
            }
            if (timePart.isEmpty()) {
                rules.add(Rule(days, listOf(0 to 1440), false)); continue
            }
            val ranges = ArrayList<Pair<Int, Int>>()
            for (t in timePart.split(",").map { it.trim() }) {
                val open = Regex("""^(\d{1,2}):(\d{2})\+$""").find(t)
                if (open != null) {
                    val start = open.groupValues[1].toInt() * 60 + open.groupValues[2].toInt()
                    ranges.add(start to 1440 + 300) // "offenes Ende" = bis ca. 05:00
                    continue
                }
                val r = Regex("""^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$""").find(t)
                    ?: throw IllegalArgumentException("unbekanntes Format")
                val start = r.groupValues[1].toInt() * 60 + r.groupValues[2].toInt()
                var end = r.groupValues[3].toInt() * 60 + r.groupValues[4].toInt()
                if (end <= start) end += 1440 // über Mitternacht
                ranges.add(start to end)
            }
            rules.add(Rule(days, ranges, false))
        }
        if (rules.isEmpty()) throw IllegalArgumentException()
        return rules
    }

    private fun ruleFor(rules: List<Rule>, day: Int): Rule? =
        rules.lastOrNull { it.days == null || day in it.days }

    private fun evaluate(rules: List<Rule>, now: Calendar): OpenState {
        val today = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mo = 0
        val yesterday = (today + 6) % 7
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        ruleFor(rules, today)?.let { r ->
            if (!r.off && r.ranges.any { minute >= it.first && minute < it.second }) return OpenState.OPEN
        }
        ruleFor(rules, yesterday)?.let { r ->
            if (!r.off && r.ranges.any { it.second > 1440 && minute + 1440 < it.second }) return OpenState.OPEN
        }
        return OpenState.CLOSED
    }
}
