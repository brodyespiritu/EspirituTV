package com.espiritu.tv

import android.os.Build
import android.text.Html
import org.json.JSONArray

class Article(val id: Long, val title: String, val link: String, val image: String?, val date: String)

/**
 * CNAW2's newest article, from the public site's WordPress API: one tiny request (title, link, picture),
 * so the TV can ask every minute for next to nothing.
 */
object News {
    private const val FEED = "https://cnaw2news.com/wp-json/wp/v2/posts?per_page=1&_fields=id,date,link,title,jetpack_featured_media_url"
    const val SITE = "cnaw2news.com"

    fun latest(): Article? {
        return try {
            val bytes = Net.get(FEED, 0) ?: return null
            val o = JSONArray(String(bytes)).optJSONObject(0) ?: return null
            val raw = o.optJSONObject("title")?.optString("rendered").orEmpty()
            @Suppress("DEPRECATION")
            val title = (if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY) else Html.fromHtml(raw)).toString().trim()
            if (title.isEmpty()) return null
            Article(o.optLong("id"), title, o.optString("link"), o.optString("jetpack_featured_media_url").ifEmpty { null }, o.optString("date"))
        } catch (t: Throwable) {
            null
        }
    }
}
