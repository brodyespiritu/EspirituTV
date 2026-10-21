package com.espiritu.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Over-the-air updates. Once a day (and from Settings → Check for updates) the launcher reads a small JSON
 * feed that `release.sh` publishes next to each GitHub release:
 *
 *   {"versionCode": 2, "versionName": "1.1", "apkUrl": "https://…/EspirituTV.apk", "sha256": "…", "notes": "…"}
 *
 * When the feed's versionCode beats the installed one, Home shows an "Update available" chip and Settings an
 * "Update to 1.1" row. Installing downloads the APK into the cache, checks its SHA-256 and hands it to
 * PackageInstaller, which asks the TV's usual "Install?" question (the answer comes back via [UpdateReceiver]).
 */
object Updater {
    /** TODO: this repository doesn't exist yet — create it (README › Updates) or point this at yours. */
    const val REPO = "brodyespiritu/EspirituTV"
    const val FEED_URL = "https://github.com/$REPO/releases/latest/download/latest.json"
    const val ACTION_STATUS = "com.espiritu.tv.UPDATE_STATUS"

    class Release(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String, val notes: String)

    sealed class Result {
        class Available(val release: Release) : Result()
        object UpToDate : Result()
        object Failed : Result()
    }

    /** The release the feed offers beyond what's installed, or null. Read and written on the main thread. */
    var available: Release? = null; private set
    /** Main thread, after every check. */
    var onChange: (() -> Unit)? = null
    /** An APK downloaded and verified while this app wasn't allowed to install yet; MainActivity retries it on resume. */
    var pending: File? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastCheck = 0L

    /** Debug builds read the feed from the Mac (see app/build.gradle.kts), so the whole flow runs on the emulator. */
    private val feed get() = BuildConfig.FEED_URL.ifEmpty { FEED_URL }

    fun installedCode(ctx: Context): Long = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (t: Throwable) {
        Long.MAX_VALUE
    }

    fun installedName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (t: Throwable) { "" }

    /** From onResume: at most once a day (Net keeps the day's feed on disk, so restarts don't refetch either). */
    fun checkDaily(ctx: Context) {
        if (System.currentTimeMillis() - lastCheck < DAY) return
        check(ctx, force = false, done = null)
    }

    /** Reads the feed off the main thread; [force] skips the day-old cache. [done] runs on the main thread. */
    fun check(ctx: Context, force: Boolean, done: ((Result) -> Unit)?) {
        val app = ctx.applicationContext
        io.execute {
            val release = Net.get(feed, if (force) 0 else DAY)?.let(::parse)
            val result = when {
                release == null -> Result.Failed
                release.versionCode > installedCode(app) -> Result.Available(release)
                else -> Result.UpToDate
            }
            main.post {
                // A failed check (no network, no cached feed) keeps what we knew and tries again in an hour.
                lastCheck = System.currentTimeMillis() - if (result is Result.Failed) DAY - HOUR else 0L
                if (result !is Result.Failed) available = (result as? Result.Available)?.release
                onChange?.invoke()
                done?.invoke(result)
            }
        }
    }

    private fun parse(bytes: ByteArray): Release? = try {
        val j = JSONObject(String(bytes))
        Release(
            j.getLong("versionCode"), j.getString("versionName"), j.getString("apkUrl"),
            j.getString("sha256").trim().lowercase(), j.optString("notes", "").trim(),
        )
    } catch (t: Throwable) {
        null
    }

    /** Whether the TV lets this app install packages: the "Install unknown apps" switch (Android 8+), or the old global one. */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls()
        else try { @Suppress("DEPRECATION") Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1 } catch (t: Throwable) { true }

    /** The settings screen with that switch. */
    fun allowIntent(ctx: Context): Intent = (
        if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        else Intent(Settings.ACTION_SECURITY_SETTINGS)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Fetches [release]'s APK into the cache (an earlier verified copy is reused), checking its SHA-256 as it streams.
     * [progress] gets 0–100 and [done] the file or an error message, both on the main thread.
     */
    fun download(ctx: Context, release: Release, progress: (Int) -> Unit, done: (File?, String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val dir = File(app.cacheDir, "update").apply { mkdirs() }
            val apk = File(dir, "EspirituTV-${release.versionCode}.apk")
            if (apk.exists() && sha256(apk) == release.sha256) {
                main.post { done(apk, null) }
                return@execute
            }
            val tmp = File(dir, apk.name + ".part")
            val error = try {
                val c = URL(release.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "EspirituTV/${installedName(app)} (Android TV launcher)")
                try {
                    if (c.responseCode !in 200..299) "The update server answered ${c.responseCode}" else {
                        val total = c.contentLength.toLong()
                        val md = MessageDigest.getInstance("SHA-256")
                        var got = 0L
                        var lastPct = -1
                        c.inputStream.use { inp ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    md.update(buf, 0, n)
                                    got += n
                                    if (total > 0) {
                                        val pct = (got * 100 / total).toInt()
                                        if (pct != lastPct) { lastPct = pct; main.post { progress(pct) } }
                                    }
                                }
                            }
                        }
                        if (hex(md.digest()) == release.sha256) null else "The download didn't match the release (checksum)"
                    }
                } finally {
                    c.disconnect()
                }
            } catch (t: Throwable) {
                "Couldn't download the update"
            }
            if (error == null && tmp.renameTo(apk)) main.post { done(apk, null) }
            else { tmp.delete(); main.post { done(null, error ?: "Couldn't save the update") } }
        }
    }

    /** Hands [apk] to the system installer. Its confirmation dialog, or the outcome, arrives through [UpdateReceiver]. */
    fun install(ctx: Context, apk: File, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            val error = try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(apk.length())
                    // After the first OTA update this app is the installer of record, and Android 12+ can skip the dialog.
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("EspirituTV.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val status = Intent(app, UpdateReceiver::class.java).setAction(ACTION_STATUS)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(app, id, status, flags).intentSender)
                }
                null
            } catch (t: Throwable) {
                "Couldn't start the install: ${t.message ?: t.javaClass.simpleName}"
            }
            main.post { done(error) }
        }
    }

    /** Drops downloads no newer than the running version (the update that just installed, or stale ones). */
    fun cleanUp(ctx: Context) {
        val app = ctx.applicationContext
        io.execute {
            val mine = installedCode(app)
            File(app.cacheDir, "update").listFiles()?.forEach { f ->
                val code = Regex("""EspirituTV-(\d+)\.apk""").matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()
                if (code == null || code <= mine) f.delete()
            }
        }
    }

    private fun sha256(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        hex(md.digest())
    } catch (t: Throwable) {
        ""
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/** PackageInstaller's answer: opens the TV's confirmation when it asks for one, otherwise reports how it went. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try {
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) {
                    Toast.makeText(ctx, "Couldn't open the installer", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {} // Android restarts the launcher on the new version.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(ctx, "Update cancelled", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(ctx, "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }
}
