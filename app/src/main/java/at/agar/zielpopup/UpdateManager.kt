package at.agar.zielpopup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-App-Update über GitHub-Releases.
 *
 * Jeder GitHub-Build erzeugt ein Release "build-N" mit ZielPopup.apk.
 * Die App kennt ihre eigene Build-Nummer (versionCode = 1000 + N) und vergleicht.
 * Installiert wird mit dem Android-eigenen PackageInstaller – Android fragt dabei einmal nach Bestätigung.
 */
object UpdateManager {
    private const val REPO = "foragario4-collab/ziel-popup"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L // automatisch höchstens alle 3 Stunden

    data class Release(val build: Int, val name: String, val apkUrl: String, val sizeBytes: Long)

    /** Eigene Build-Nummer (0, wenn die App nicht von GitHub gebaut wurde) */
    @Suppress("DEPRECATION")
    fun ownBuild(ctx: Context): Int {
        val code = try {
            if (Build.VERSION.SDK_INT >= 28) ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
            else ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        } catch (e: Exception) {
            0
        }
        return if (code >= 1000) code - 1000 else 0
    }

    /** Neuestes Release von GitHub holen (läuft im Hintergrund-Thread!) */
    fun fetchLatest(): Release {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ZielPopup-Updater")
        }
        try {
            val code = conn.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub: zu viele Anfragen – später nochmal versuchen")
            if (code == 404) throw IOException("Noch kein Release auf GitHub gefunden")
            if (code !in 200..299) throw IOException("GitHub-Fehler $code")
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = json.optString("tag_name")
            val build = Regex("""(\d+)""").find(tag)?.value?.toIntOrNull()
                ?: throw IOException("Unbekannte Release-Bezeichnung: $tag")
            val assets = json.optJSONArray("assets") ?: throw IOException("Release enthält keine APK")
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        build,
                        json.optString("name").ifEmpty { tag },
                        a.optString("browser_download_url"),
                        a.optLong("size")
                    )
                }
            }
            throw IOException("Release enthält keine APK")
        } finally {
            conn.disconnect()
        }
    }

    // ---------- gemerkter Stand (für das Update-Banner) ----------

    fun rememberLatest(ctx: Context, r: Release) {
        Prefs.get(ctx).edit()
            .putInt("upd_build", r.build).putString("upd_name", r.name)
            .putString("upd_url", r.apkUrl).putLong("upd_size", r.sizeBytes)
            .putLong("upd_checked", System.currentTimeMillis())
            .apply()
    }

    /** Bekanntes, neueres Update – oder null */
    fun knownUpdate(ctx: Context): Release? {
        val p = Prefs.get(ctx)
        val b = p.getInt("upd_build", 0)
        val url = p.getString("upd_url", null) ?: return null
        if (b <= ownBuild(ctx)) return null
        return Release(b, p.getString("upd_name", null) ?: "Build $b", url, p.getLong("upd_size", 0L))
    }

    fun dueForAutoCheck(ctx: Context): Boolean =
        System.currentTimeMillis() - Prefs.get(ctx).getLong("upd_checked", 0L) > CHECK_EVERY_MS

    // ---------- Herunterladen ----------

    /** Lädt die APK in den App-Cache. onProgress: 0..100 (läuft im Hintergrund-Thread!) */
    fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "ZielPopup-build-${r.build}.apk")
        val conn = openFollowingRedirects(URL(r.apkUrl))
        try {
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else r.sizeBytes
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (out.length() < 100_000) throw IOException("Download unvollständig")
        return out
    }

    /** GitHub leitet auf einen Download-Server weiter – Weiterleitungen selbst folgen */
    private fun openFollowingRedirects(start: URL): HttpURLConnection {
        var url = start
        repeat(6) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", "ZielPopup-Updater")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("Weiterleitung ohne Ziel")
                url = URL(url, loc)
            } else if (code in 200..299) {
                return c
            } else {
                c.disconnect()
                throw IOException("Download-Fehler $code")
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    // ---------- Installieren ----------

    /** Übergibt die APK an Android. Android zeigt danach selbst den Bestätigungs-Dialog. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("ZielPopup.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Empfängt die Rückmeldung von Android während der Installation */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android möchte, dass du die Installation bestätigst
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirm)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> EventLog.add(context, "Update installiert")
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    EventLog.add(context, "Update fehlgeschlagen ($status): $msg")
                    android.widget.Toast.makeText(context, "Update fehlgeschlagen: $msg", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Throwable) {
            EventLog.add(context, "Update-Fehler: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
