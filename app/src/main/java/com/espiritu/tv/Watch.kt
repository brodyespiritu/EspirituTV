package com.espiritu.tv

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** One stretch of watching a title in an app. [guesses] are show-name candidates from the media metadata, best first. */
class Play(
    val pkg: String,
    var title: String,
    var guesses: List<String>,
    var first: Long,
    var last: Long,
    var position: Long,
    var duration: Long,
) {
    val showGuess: String get() = guesses.firstOrNull() ?: title
}

/** A show watched on the same weekday, week after week. [weekday] uses Calendar.DAY_OF_WEEK. */
class Weekly(val play: Play, val weekday: Int, val minuteOfDay: Int, val weeks: Int, val daysUntil: Int)

/**
 * What's been watched, kept on the TV only (files/history.json). Fed by [MediaWatcher];
 * read by Home for Continue watching and the weekly-show banner.
 */
object History {
    private const val MAX = 400
    private val plays = ArrayList<Play>()
    private var file: File? = null
    private var lastSave = 0L

    /** Bumps on every change so Home knows when to refresh. */
    @Volatile var version = 0; private set(v) { field = v; onChange?.invoke() }

    /** Home's refresh hook (the watcher runs in this same process). Called on the thread that made the change. */
    @Volatile var onChange: (() -> Unit)? = null

    fun init(ctx: Context) = synchronized(this) {
        if (file != null) return
        file = File(ctx.filesDir, "history.json").also { load(it) }
    }

    /**
     * Notes what a media session is doing. Some TV apps (Hulu's) publish no metadata at all, only playback
     * state; [label] is then the name the user gave that app's playback, and the entry is kept without a title.
     */
    fun record(ctx: Context, pkg: String, md: MediaMetadata?, st: PlaybackState?, label: String? = null) {
        init(ctx)
        val mdTitle = md?.let { (it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_TITLE)) }
            ?.trim()?.ifEmpty { null }
        val named = label?.trim()?.ifEmpty { null }
        val title = mdTitle ?: named ?: ""
        val guesses = (listOfNotNull(named) + md?.let { m ->
            listOf(MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                .mapNotNull { m.getString(it)?.trim()?.ifEmpty { null } }
        }.orEmpty() + listOfNotNull(mdTitle)).distinct()
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = st?.state ?: PlaybackState.STATE_NONE
        val playing = state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: -1L
        if (playing && st != null && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val now = System.currentTimeMillis()
        synchronized(this) {
            val recent = plays.lastOrNull { it.pkg == pkg && it.title == title }?.takeIf { now - it.last < 6 * HOUR }
            // A new entry needs playback, or real progress: a session parked part-way is what Continue watching is for.
            // (The launcher is usually scanning after the fact, once Home is back in front.)
            val p = recent ?: if (playing || (pos >= 60_000 && state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR))
                Play(pkg, title, guesses, now, now, 0, 0).also { plays += it } else return
            if (named != null && p.guesses.firstOrNull() != named) p.guesses = (listOf(named) + p.guesses).distinct()
            if (p.title.isBlank() && named != null) p.title = named
            p.last = now
            if (pos >= 0) p.position = pos
            if (duration > 0) p.duration = duration
            while (plays.size > MAX) plays.removeAt(0)
            version++
            save(force = !playing)
        }
    }

    /** The user named what an app without metadata is playing: apply it to that app's untitled entries (and ones under [old]). */
    fun relabel(ctx: Context, pkg: String, name: String, old: String? = null) {
        init(ctx)
        val n = name.trim()
        if (n.isEmpty()) return
        synchronized(this) {
            for (p in plays) {
                if (p.pkg == pkg && (p.title.isBlank() || p.guesses.isEmpty() || (old != null && p.title == old))) { p.title = n; p.guesses = listOf(n) }
            }
            version++
            save(force = true)
        }
    }

    /** The newest title left part-way through in the last four days (with no duration known: at least five minutes in). */
    fun inProgress(now: Long = System.currentTimeMillis()): Play? = synchronized(this) {
        plays.asReversed().firstOrNull {
            now - it.last < 4 * DAY && (
                (it.duration >= 10 * 60_000 && it.position >= it.duration * 0.03 && it.position <= it.duration * 0.93) ||
                    (it.duration <= 0 && it.position >= 5 * 60_000))
        }
    }

    /**
     * A show watched on the same weekday in at least three different weeks out of the last six,
     * picking the one coming up soonest (today first).
     */
    fun weekly(now: Long = System.currentTimeMillis()): Weekly? = synchronized(this) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val today = cal.get(Calendar.DAY_OF_WEEK)
        val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val groups = plays
            .filter { now - it.first < 42 * DAY && (it.last - it.first >= 10 * 60_000 || it.position >= 10 * 60_000) }
            .filter { it.duration <= 0 || it.duration >= 15 * 60_000 }
            .filter { it.showGuess.isNotBlank() }
            .groupBy { key(it.showGuess) }
        var best: Weekly? = null
        for (group in groups.values) {
            val dates = HashMap<Int, MutableSet<Int>>()      // weekday -> distinct dates
            val minutes = HashMap<Int, MutableList<Int>>()   // weekday -> start times
            for (p in group) {
                cal.timeInMillis = p.first
                val wd = cal.get(Calendar.DAY_OF_WEEK)
                dates.getOrPut(wd) { HashSet() } += cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
                minutes.getOrPut(wd) { ArrayList() } += cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            }
            val (wd, ds) = dates.maxByOrNull { it.value.size } ?: continue
            if (ds.size < 3) continue
            val minute = minutes[wd]!!.sorted().let { it[it.size / 2] }
            var until = (wd - today + 7) % 7
            if (until == 0 && nowMinute > minute + 180) until = 7
            val cand = Weekly(group.maxBy { it.last }, wd, minute, ds.size, until)
            val b = best
            if (b == null || cand.daysUntil < b.daysUntil || (cand.daysUntil == b.daysUntil && cand.weeks > b.weeks)) best = cand
        }
        best
    }

    fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Everything recorded, oldest first (for the adb DUMP hook). */
    fun all(): List<Play> = synchronized(this) { plays.toList() }

    fun clear(ctx: Context) {
        init(ctx)
        synchronized(this) { plays.clear(); version++; save(force = true) }
    }

    /** Sample history for trying the screen out: a show watched the last three Tuesdays, and one left half-way. */
    fun seedDemo(ctx: Context) {
        init(ctx)
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        synchronized(this) {
            for (weeksAgo in 1..3) {
                // The Tuesdays before today.
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_MONTH, -1)
                while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.TUESDAY) cal.add(Calendar.DAY_OF_MONTH, -1)
                cal.add(Calendar.DAY_OF_MONTH, -7 * (weeksAgo - 1))
                cal.set(Calendar.HOUR_OF_DAY, 20); cal.set(Calendar.MINUTE, 2); cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis
                plays += Play("com.google.android.youtube.tvunplugged", "Dancing with the Stars", listOf("Dancing with the Stars"),
                    start, start + 85 * 60_000, 85 * 60_000, 0)
            }
            plays += Play("com.netflix.ninja", "Chapter Seven: The Massacre at Hawkins Lab",
                listOf("Stranger Things", "Chapter Seven: The Massacre at Hawkins Lab"),
                now - 3 * HOUR, now - 2 * HOUR, 61 * 60_000, 98 * 60_000)
            plays.sortBy { it.first }
            version++
            save(force = true)
        }
    }

    private fun save(force: Boolean) {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 20_000) return
        lastSave = now
        val arr = JSONArray()
        for (p in plays) arr.put(JSONObject().apply {
            put("pkg", p.pkg); put("t", p.title); put("g", JSONArray(p.guesses))
            put("f", p.first); put("l", p.last); put("p", p.position); put("d", p.duration)
        })
        try {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (t: Throwable) {}
    }

    private fun load(f: File) {
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val g = o.optJSONArray("g")
                plays += Play(o.getString("pkg"), o.getString("t"),
                    g?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    o.getLong("f"), o.getLong("l"), o.optLong("p"), o.optLong("d"))
            }
        } catch (t: Throwable) {}
    }
}

/**
 * Listens to the TV's media sessions (what's playing in Netflix, YouTube TV, …) and writes it to [History].
 * Needs notification access, which install.sh grants with `cmd notification allow_listener`.
 */
class MediaWatcher : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private val watching = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    override fun onListenerConnected() {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        sessions = msm
        val me = ComponentName(this, MediaWatcher::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(changed, me, main)
            attach(msm.getActiveSessions(me))
        } catch (e: SecurityException) {
            sessions = null
        }
    }

    override fun onListenerDisconnected() {
        try { sessions?.removeOnActiveSessionsChangedListener(changed) } catch (t: Throwable) {}
        watching.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watching.clear()
        sessions = null
    }

    private fun attach(list: List<MediaController>) {
        val live = list.map { it.sessionToken }.toSet()
        watching.keys.filter { it !in live }.forEach { t -> watching.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        for (c in list) {
            if (c.packageName == packageName || c.sessionToken in watching) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = note(c)
                override fun onPlaybackStateChanged(state: PlaybackState?) = note(c)
                override fun onSessionDestroyed() = note(c)
            }
            c.registerCallback(cb, main)
            watching[c.sessionToken] = c to cb
            note(c)
        }
    }

    private fun note(c: MediaController) {
        try { History.record(this, c.packageName, c.metadata, c.playbackState, Prefs(this).watching(c.packageName)) } catch (t: Throwable) {}
    }

    companion object {
        /** The live session of [pkg], if the app still has one (apps keep it, paused, after you leave them). */
        fun session(ctx: Context, pkg: String): MediaController? {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
            return try {
                msm.getActiveSessions(ComponentName(ctx, MediaWatcher::class.java)).firstOrNull { it.packageName == pkg }
            } catch (t: Throwable) {
                null
            }
        }

        /**
         * One pass over the sessions active right now. Allowed whenever the listener is enabled, bound or not,
         * which matters on TVs that kill the launcher in the background: Home scans on return and every half minute.
         */
        fun scan(ctx: Context): Int {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return 0
            val prefs = Prefs(ctx)
            return try {
                var n = 0
                for (c in msm.getActiveSessions(ComponentName(ctx, MediaWatcher::class.java))) {
                    if (c.packageName == ctx.packageName) continue
                    History.record(ctx, c.packageName, c.metadata, c.playbackState, prefs.watching(c.packageName))
                    n++
                }
                n
            } catch (t: Throwable) {
                0
            }
        }
    }
}
