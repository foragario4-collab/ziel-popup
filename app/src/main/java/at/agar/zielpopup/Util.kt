package at.agar.zielpopup

import android.content.Context
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Entfernung zwischen zwei Punkten in Metern */
fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return (r * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt()
}
