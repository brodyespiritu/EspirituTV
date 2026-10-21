package com.espiritu.tv

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

const val HOUR = 3_600_000L
const val DAY = 24 * HOUR

/** Small HTTP GET with a disk cache. Serves a stale copy when the network is down, so the TV still looks right offline. */
object Net {
    private lateinit var dir: File

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.cacheDir, "net").apply { mkdirs() }
        // Forget anything untouched for 45 days; listing the folder is not worth a startup stall.
        val d = dir
        Thread({
            val cutoff = System.currentTimeMillis() - 45 * DAY
            d.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }, "net-sweep").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun get(url: String, ttl: Long): ByteArray? {
        val f = File(dir, sha1(url))
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ttl) return safeRead(f)
        val fresh = fetch(url)
        if (fresh != null) {
            val tmp = File(dir, f.name + ".tmp")
            try { tmp.writeBytes(fresh); tmp.renameTo(f) } catch (t: Throwable) { tmp.delete() }
            return fresh
        }
        return if (f.exists()) safeRead(f) else null
    }

    private fun safeRead(f: File) = try { f.readBytes() } catch (t: Throwable) { null }

    private fun fetch(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "EspirituTV/1.0 (Android TV launcher)")
        try {
            if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
        } finally {
            c.disconnect()
        }
    } catch (t: Throwable) {
        null
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Decodes remote images at no more than the size asked for (a 4K backdrop becomes screen-sized) and keeps a few in memory.
 * On low-RAM TVs the cache shrinks and photos decode as 16-bit, which halves their footprint.
 */
object Images {
    private var lowRam = false
    private lateinit var mem: LruCache<String, Bitmap>

    fun init(ctx: Context) {
        if (::mem.isInitialized) return
        lowRam = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        mem = object : LruCache<String, Bitmap>(if (lowRam) 12 * 1024 * 1024 else 32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }

    fun load(url: String, maxWidth: Int): Bitmap? {
        val key = "$maxWidth|$url"
        mem.get(key)?.let { return it }
        val bytes = Net.get(url, 60 * DAY) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (lowRam) inPreferredConfig = Bitmap.Config.RGB_565
        }
        var bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) } catch (e: OutOfMemoryError) { null } ?: return null
        // Sampling only halves; bring anything still wider than needed down to size so it isn't held or drawn oversized.
        if (bmp.width > maxWidth) {
            val scaled = Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        mem.put(key, bmp)
        return bmp
    }
}
