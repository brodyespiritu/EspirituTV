package com.espiritu.tv

import android.os.Build
import android.text.Html
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

class Show(
    val id: Int,
    val name: String,
    val genres: List<String>,
    val year: String?,
    val summary: String,
    /** Weekly air days ("Tuesday") and time ("20:00") for scheduled TV; empty for streaming drops. */
    val days: List<String>,
    val time: String?,
    val network: String?,
    val art: String?,
    /** The art is a title card, so the hero shouldn't print the name over it again. */
    val titleInArt: Boolean,
)

/**
 * Show details and artwork from TVmaze (free, no account needed; data CC BY-SA, credited on screen).
 * Everything is cached on disk, so the hero still fills in when the network is slow or down.
 */
object Catalog {
    private const val API = "https://api.tvmaze.com"

    fun show(id: Int): Show? = parse(Net.get("$API/shows/$id?embed=images", 3 * DAY))

    fun search(name: String): Show? =
        if (name.isBlank()) null
        else parse(Net.get("$API/singlesearch/shows?embed=images&q=" + URLEncoder.encode(name, "UTF-8"), 14 * DAY))

    class Episode(val season: Int, val number: Int, val name: String)

    /** Every episode of a show, in order (cached for three days). */
    fun episodes(showId: Int): List<Episode> = try {
        val arr = org.json.JSONArray(String(Net.get("$API/shows/$showId/episodes", 3 * DAY) ?: return emptyList()))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> if (o.optInt("season") > 0 && o.optInt("number") > 0) Episode(o.optInt("season"), o.optInt("number"), o.optString("name")) else null }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Which episode [play] is on: its season/episode (rolled into the next season when counting ran past the end),
     * or, when the app only gave the episode's name, that name looked up in the show's episode list.
     */
    fun episodeOf(show: Show?, play: Play): Episode? {
        val list = show?.let { episodes(it.id) }.orEmpty()
        if (play.episode > 0) {
            var s = play.season.coerceAtLeast(1)
            var e = play.episode
            while (true) {
                val count = list.count { it.season == s }
                if (count == 0 || e <= count) break
                e -= count; s++
            }
            return Episode(s, e, list.firstOrNull { it.season == s && it.number == e }?.name.orEmpty())
        }
        if (show == null || play.title.isBlank() || norm(play.title) == norm(show.name)) return null
        val t = norm(play.title.substringAfterLast(": "))
        return list.firstOrNull { norm(it.name) == t || norm(it.name) == norm(play.title) }
    }

    /** Finds the show a media session was playing, trying the likeliest metadata fields first. */
    fun resolve(guesses: List<String>): Show? {
        for (g in guesses.map(::clean).filter { it.length >= 2 }.distinct()) {
            val s = search(g) ?: continue
            if (similar(s.name, g)) return s
        }
        return null
    }

    /** Drops episode markers apps put in titles: "Stranger Things: S4 E7", "… - Season 2", "(2016)". */
    private fun clean(s: String) = s
        .replace(Regex("""\s*[:\-–|]\s*(S\d+\s*:?\s*E\d+|Season \d+|Episode \d+).*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
        .trim()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun similar(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.startsWith(y) || y.startsWith(x))
    }

    private fun parse(bytes: ByteArray?): Show? = try {
        bytes?.let { parse(JSONObject(String(it))) }
    } catch (t: Throwable) {
        null
    }

    private fun parse(o: JSONObject): Show {
        var art: String? = null
        var artScore = -1
        var card: String? = null
        o.optJSONObject("_embedded")?.optJSONArray("images")?.let { images ->
            for (i in 0 until images.length()) {
                val im = images.optJSONObject(i) ?: continue
                val orig = im.optJSONObject("resolutions")?.optJSONObject("original") ?: continue
                val url = orig.optString("url").ifEmpty { null } ?: continue
                val w = orig.optInt("width"); val h = orig.optInt("height").coerceAtLeast(1)
                when (im.optString("type")) {
                    "background" -> {
                        val score = (if (im.optBoolean("main")) 4 else 0) +
                            (if (abs(w.toFloat() / h - 16f / 9f) < 0.06f) 2 else 0) + (if (w >= 1600) 1 else 0)
                        if (score > artScore) { art = url; artScore = score }
                    }
                    "typography" -> if (card == null && w > h) card = url
                }
            }
        }
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val schedule = o.optJSONObject("schedule")
        val days = schedule?.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val network = o.optJSONObject("network")?.optString("name")?.ifEmpty { null }
            ?: o.optJSONObject("webChannel")?.optString("name")?.ifEmpty { null }
        return Show(
            id = o.optInt("id"),
            name = o.optString("name"),
            genres = genres,
            year = o.optString("premiered").take(4).ifEmpty { null },
            summary = shorten(text(o.optString("summary"))),
            days = days,
            time = schedule?.optString("time")?.ifEmpty { null },
            network = network,
            art = art ?: card,
            titleInArt = art == null && card != null,
        )
    }

    @Suppress("DEPRECATION")
    private fun text(html: String): String =
        (if (Build.VERSION.SDK_INT >= 24) Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY) else Html.fromHtml(html))
            .toString().replace(Regex("\\s+"), " ").trim()

    /** The first sentence or two, about two lines on screen. */
    private fun shorten(s: String, max: Int = 170): String {
        if (s.length <= max) return s
        val sentences = Regex("""(?<=[.!?])\s+""").split(s)
        val out = StringBuilder()
        for (sentence in sentences) {
            if (out.isNotEmpty() && out.length + sentence.length + 1 > max) break
            if (out.isNotEmpty()) out.append(' ')
            out.append(sentence)
        }
        if (out.length <= max + 40) return out.toString()
        val cut = out.substring(0, max).substringBeforeLast(' ')
        return "$cut…"
    }
}
