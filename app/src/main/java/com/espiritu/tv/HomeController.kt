package com.espiritu.tv

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.view.View
import java.util.concurrent.ExecutorService

/**
 * Decides what fills the Home screen and loads it off the main thread.
 * - Banner: the show left part-way (Continue watching) wins; otherwise the weekly show; otherwise nothing.
 * - Background: the lineup of the app card in focus (mostly brand-new series, never Horror), each show's
 *   text-free backdrop drifting slowly closer, a new one every [ROTATE_MS], in a fresh order on every Home visit.
 */
class HomeController(
    private val ctx: Context,
    private val view: HomeView,
    private val prefs: Prefs,
    private val io: ExecutorService,
    private val main: Handler,
    private val actions: Actions,
) {
    interface Actions {
        fun openPackage(pkg: String)
        /** Back into the show where it stopped (the app's own player for its paused session), not just the app. */
        fun resumePlayback(pkg: String)
        fun showInfo(show: Show?, title: String, pkg: String)
        fun toast(msg: String)
    }

    var pinnedApps: List<AppEntry> = emptyList()
    var allApps: List<AppEntry> = emptyList()
    /** The app behind the Continue-watching banner, if any (for naming what it plays). */
    var continuePkg: String? = null; private set

    private var gen = 0
    private var popGen = 0
    private var popularPkg: String? = null
    private var popularIndex = 0
    private var popularCount = 0
    private var currentShow: Show? = null
    private var pendingPkg: String? = null
    private var lastInput = SystemClock.uptimeMillis()
    /** New on every Home visit: the rotation's order. */
    private var visitSeed = 0L

    private val rotate = object : Runnable {
        override fun run() {
            val pkg = popularPkg
            if (!idle() && pkg != null && popularCount > 1) loadPopular(pkg, popularIndex + 1)
            main.postDelayed(this, ROTATE_MS)
        }
    }

    private val switchProvider = Runnable {
        val pkg = pendingPkg ?: return@Runnable
        if (pkg == popularPkg) return@Runnable
        popularPkg = pkg
        loadPopular(pkg, 0)
    }

    /** Home is back in front: a fresh order of shows, starting over, and the background moving again. */
    fun start() {
        visitSeed = System.nanoTime()
        popularIndex = 0
        currentShow = null
        view.back.motion = prefs.motion
        view.back.resume()
        refresh()
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, ROTATE_MS)
    }

    fun stop() {
        main.removeCallbacks(rotate)
        main.removeCallbacks(switchProvider)
        view.back.pause()
    }

    /** Nobody has touched the remote for a while: the rotation rests (no work, no burn-in). */
    private fun idle() = SystemClock.uptimeMillis() - lastInput > IDLE_MS

    /** A remote key arrived; the rotation picks up again. */
    fun poke() {
        val wasIdle = idle()
        lastInput = SystemClock.uptimeMillis()
        if (wasIdle) {
            main.removeCallbacks(rotate)
            main.postDelayed(rotate, ROTATE_MS)
            view.back.resume()
        }
    }

    /** Settings toggled the background drift. */
    fun motionChanged() { view.back.motion = prefs.motion }

    /** Focus moved onto an app card: after a short pause, the background follows that app. */
    fun appFocused(app: AppEntry?) {
        app ?: return
        if (Brands.provider(app.pkg) == null || app.pkg == popularPkg) return
        pendingPkg = app.pkg
        main.removeCallbacks(switchProvider)
        main.postDelayed(switchProvider, 350)
    }

    /** Re-reads watch history for the banner and the card dot. Safe to call often; stale loads are dropped. */
    fun refresh() {
        val g = ++gen
        io.execute {
            History.init(ctx)
            val play = History.inProgress()
            val weekly = if (play == null) History.weekly() else null
            val playShow = play?.takeIf { it.showGuess.isNotBlank() }?.let { Catalog.resolve(it.guesses) }
            val playEp = play?.let { Catalog.episodeOf(playShow, it) }
            val playApp = play?.let { app(it.pkg) }
            // No show art (app sent no title): the app's own banner card stands in.
            val playArt = playShow?.let(::artUrl)?.let { Images.load(it, px(192 * 1.5f)) } ?: playApp?.card
            val weeklyShow = weekly?.let { Catalog.resolve(listOf(it.play.showGuess) + it.play.guesses) }
            val weeklyArt = weeklyShow?.let(::artUrl)?.let { Images.load(it, px(192 * 1.5f)) }
            main.post {
                if (g != gen) return@post
                continuePkg = play?.pkg
                when {
                    play != null -> view.banner.setContinue(play, playShow, playEp, playArt, playApp?.label ?: Brands.provider(play.pkg)?.name ?: play.pkg) { actions.resumePlayback(play.pkg) }
                    weekly != null -> view.banner.setWeekly(weekly, weeklyShow, weeklyArt) { actions.openPackage(weekly.play.pkg) }
                    else -> view.banner.hide()
                }
                view.grid.setRunning(play?.pkg)
                if (currentShow == null) {
                    val pkg = popularPkg ?: firstProvider().also { popularPkg = it }
                    loadPopular(pkg, popularIndex)
                }
            }
        }
    }

    private fun loadPopular(pkg: String, index: Int) {
        val provider = Brands.provider(pkg) ?: return
        // Saved Up Next shows lead; the rest come in an order that changes every visit.
        val ids = (prefs.upNextIds(pkg) + provider.shows.shuffled(java.util.Random(visitSeed xor pkg.hashCode().toLong()))).distinct()
        if (ids.isEmpty()) return
        val g = ++popGen
        io.execute {
            // Walk the list from [index] to the first show that isn't tagged Horror.
            var i = ((index % ids.size) + ids.size) % ids.size
            var show: Show? = null
            for (attempt in ids.indices) {
                val s = Catalog.show(ids[i])
                if (s != null && s.genres.none { it.equals("Horror", ignoreCase = true) }) { show = s; break }
                i = (i + 1) % ids.size
            }
            val url = show?.let(::artUrl)
            val raw = url?.let { Images.load(it, D.screenW) }
            val creditText = when { raw == null -> null; url!!.contains("image.tmdb.org") -> "Art: TMDB"; else -> "Art: TVmaze" }
            val logo = show?.let { Brands.logo(it.id) }?.let { Images.load(it, 1000) }
            val frame = if (show != null) ready(Backdrop.compose(raw)) else null
            val blur = frame?.let(Backdrop::blurOf)
            val picked = i
            main.post {
                if (g != popGen) return@post
                if (show == null || frame == null) {
                    // Nothing cached and no network: keep whatever is up, or show the plain scene.
                    if (view.hero.visibility != View.VISIBLE) view.showEmpty()
                    return@post
                }
                popularIndex = picked
                popularCount = ids.size
                currentShow = show
                val app = app(pkg)
                view.hero.showPopular(Brands.of(pkg), app?.tile, provider.name, show, logo, show.id in provider.new, prefs.isUpNext(pkg, show))
                if (app == null) view.hero.primary.text = "Get ${provider.name}"
                view.hero.primary.setOnClickListener { actions.openPackage(pkg) }
                view.hero.secondary.setOnClickListener {
                    val saved = prefs.toggleUpNext(pkg, show)
                    view.hero.setUpNext(saved)
                    actions.toast(if (saved) "${show.name} added to Up Next" else "${show.name} removed from Up Next")
                }
                view.setDots(ids.size, picked)
                view.setArt(frame, creditText)
                Backdrop.apply(blur)
            }
        }
    }

    /** Text-free built-in backdrop when the show is in a lineup, else TVmaze's. */
    private fun artUrl(show: Show): String? = Brands.backdrop(show.id) ?: show.art

    private fun app(pkg: String) = pinnedApps.firstOrNull { it.pkg == pkg } ?: allApps.firstOrNull { it.pkg == pkg }

    /** The first streaming app among the pinned cards, then anywhere; with none installed, HBO Max's picks with a "Get" button. */
    private fun firstProvider(): String =
        pinnedApps.firstOrNull { Brands.provider(it.pkg) != null }?.pkg
            ?: allApps.firstOrNull { Brands.provider(it.pkg) != null }?.pkg
            ?: Brands.DEFAULT_PROVIDER

    /** Uploads the frame to the GPU while still on the worker thread, so the crossfade starts without a hitch. */
    private fun ready(frame: Bitmap): Bitmap = frame.also { it.prepareToDraw() }

    private companion object {
        const val ROTATE_MS = 14_000L
        const val IDLE_MS = 10 * 60_000L
    }
}
