package com.espiritu.tv

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Trailers for the shows behind Home. The curated shows carry their YouTube trailer with them ([Brands.trailer]);
 * anything else is looked up through TMDB when the user has set a key (`install.sh --tmdb-key`), cached for a week.
 */
object Trailers {
    private const val API = "https://api.themoviedb.org/3"

    fun enabled(prefs: Prefs) = prefs.trailers

    /**
     * The YouTube video id of [show]'s trailer, or null when there isn't one.
     * A "key" of the form `yt:VIDEOID` is a test mode: that one video plays for every show.
     */
    fun find(show: Show, key: String): String? {
        if (key.startsWith("yt:")) return key.removePrefix("yt:").ifBlank { null }
        Brands.trailer(show.id)?.let { return it }
        if (key.isBlank()) return null
        return try {
            val q = URLEncoder.encode(show.name, "UTF-8")
            val year = show.year?.let { "&first_air_date_year=$it" } ?: ""
            val hits = JSONObject(String(Net.get("$API/search/tv?api_key=$key&query=$q$year", 7 * DAY) ?: return null))
                .optJSONArray("results")
            val id = hits?.optJSONObject(0)?.optInt("id") ?: return null
            val videos = JSONObject(String(Net.get("$API/tv/$id/videos?api_key=$key", 7 * DAY) ?: return null)).optJSONArray("results")
            var best: String? = null
            var bestScore = -1
            for (i in 0 until (videos?.length() ?: 0)) {
                val v = videos!!.optJSONObject(i) ?: continue
                if (v.optString("site") != "YouTube") continue
                val type = v.optString("type")
                val score = (if (type == "Trailer") 4 else if (type == "Teaser") 2 else 0) + (if (v.optBoolean("official")) 1 else 0)
                if (score > bestScore) { bestScore = score; best = v.optString("key").ifEmpty { null } }
            }
            best
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * How much to oversize YouTube's 16:9 player so the picture itself fills the screen. Wide (letterboxed) and
     * narrow (pillarboxed) videos show black bars inside the player that can't be switched off, but YouTube's
     * auto-generated frame thumbnails (mq1–mq3) carry the very same bars, so those are measured first.
     * Returns 1 when nothing can be measured.
     */
    fun coverScale(videoId: String): Float {
        var best = 1f
        var measured = false
        for (n in 1..3) {
            val bytes = Net.get("https://i.ytimg.com/vi/$videoId/mq$n.jpg", 30 * DAY) ?: continue
            val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (t: Throwable) { null } ?: continue
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            fun lit(p: Int) = maxOf((p shr 16) and 255, (p shr 8) and 255, p and 255) >= 24
            fun rowLit(y: Int): Boolean { var x = 0; while (x < w) { if (lit(px[y * w + x])) return true; x += 2 }; return false }
            fun colLit(x: Int): Boolean { var y = 0; while (y < h) { if (lit(px[y * w + x])) return true; y += 2 }; return false }
            var top = 0
            while (top < h && !rowLit(top)) top++
            if (top >= h) continue // a black frame says nothing about the shape
            var bottom = 0
            while (bottom < h && !rowLit(h - 1 - bottom)) bottom++
            var left = 0
            while (left < w && !colLit(left)) left++
            var right = 0
            while (right < w && !colLit(w - 1 - right)) right++
            val fh = (h - top - bottom).toFloat() / h
            val fw = (w - left - right).toFloat() / w
            val s = maxOf(1f / fh, 1f / fw)
            // The smallest bars seen across the frames win: a dark scene can only make bars look bigger.
            best = if (measured) minOf(best, s) else s
            measured = true
        }
        return if (measured) (best * 1.03f).coerceIn(1f, 2.5f) else 1f
    }
}
