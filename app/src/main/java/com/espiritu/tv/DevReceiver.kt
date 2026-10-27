package com.espiritu.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * adb-only hooks (the receiver needs android.permission.DUMP, which apps can't hold):
 *   adb shell am broadcast -a com.espiritu.tv.DEMO       sample watch history, to see the in-progress dot
 *   adb shell am broadcast -a com.espiritu.tv.CLEAR      forget all watch history
 *   adb shell am broadcast -a com.espiritu.tv.SCAN       look at the media sessions active right now
 *   adb shell am broadcast -a com.espiritu.tv.NEWS_TEST  show the newest CNAW2 article as a banner over whatever is on screen
 *   adb shell am broadcast -a com.espiritu.tv.DUMP       print the watch history to logcat (tag EspirituTV)
 *   adb shell am broadcast -a com.espiritu.tv.WATCHING --es pkg com.hulu.livingroomplus --es name "The Mentalist"
 *                                                        name what an app that sends no title is playing
 */
class DevReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "com.espiritu.tv.DEMO" -> History.seedDemo(ctx)
            "com.espiritu.tv.CLEAR" -> History.clear(ctx)
            "com.espiritu.tv.SCAN" -> Log.i(TAG, "scanned ${MediaWatcher.scan(ctx)} active session(s)")
            "com.espiritu.tv.NEWS_TEST" -> NewsService.start(ctx, NewsService.ACTION_TEST)
            "com.espiritu.tv.DUMP" -> dump(ctx)
            "com.espiritu.tv.WATCHING" -> {
                val pkg = intent.getStringExtra("pkg").orEmpty()
                val name = intent.getStringExtra("name").orEmpty()
                if (pkg.isNotBlank()) {
                    val old = Prefs(ctx).watching(pkg)
                    Prefs(ctx).setWatching(pkg, name)
                    if (name.isNotBlank()) History.relabel(ctx, pkg, name, old)
                    MediaWatcher.scan(ctx)
                    intent.getStringExtra("episode")?.let(History::seasonEpisode)?.let { (s, e) -> History.setEpisode(ctx, pkg, s, e) }
                    Log.i(TAG, "$pkg is playing: ${name.ifBlank { "(cleared)" }}")
                }
            }
        }
    }

    private fun dump(ctx: Context) {
        History.init(ctx)
        val f = SimpleDateFormat("EEE MMM d HH:mm", Locale.US)
        val all = History.all()
        Log.i(TAG, "watch history: ${all.size} entries")
        for (p in all.takeLast(60)) {
            val pos = if (p.duration > 0) "${p.position / 60_000}/${p.duration / 60_000} min" else "${p.position / 60_000} min in"
            Log.i(TAG, "${f.format(Date(p.first))} → ${f.format(Date(p.last))}  ${p.pkg}  \"${p.title}\"  guesses=${p.guesses}  $pos")
        }
        History.inProgress()?.let { Log.i(TAG, "in progress: \"${it.title}\" (${it.showGuess}) on ${it.pkg}: ${it.position / 60_000} min" + (if (it.duration > 0) " of ${it.duration / 60_000}" else "")) }
        History.weekly()?.let { Log.i(TAG, "weekly: ${it.play.showGuess} weekday=${it.weekday} weeks=${it.weeks} daysUntil=${it.daysUntil}") }
    }

    private companion object {
        const val TAG = "EspirituTV"
    }
}
