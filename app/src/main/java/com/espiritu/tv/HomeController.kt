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
 * - Background: a popular show from the app focused in the dock (never Horror), rotating every [ROTATE_MS];
 *   with a TMDB key set, each still gives way to the show's muted trailer after [PHOTO_MS], then moves on.
 */
class HomeController(
    private val ctx: Context,
    private val view: HomeView,
    private val prefs: Prefs,
    private val io: ExecutorService,
    private val main: Handler,
    private val actions: Actions,
) : TrailerView.Listener {
    interface Actions {
        fun openPackage(pkg: String)
        fun showInfo(show: Show?, title: String, pkg: String)
        fun toast(msg: String)
    }

    var dockApps: List<AppEntry> = emptyList()
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
    /** A trailer is being looked up or playing for [currentShow]. */
    private var trailerWanted = false

    init { view.trailer.listener = this }

    private val rotate = object : Runnable {
        override fun run() {
            val pkg = popularPkg
            if (idle()) view.trailer.release()
            else if (!view.trailer.playing && !trailerWanted && pkg != null && popularCount > 1) loadPopular(pkg, popularIndex + 1)
            main.postDelayed(this, ROTATE_MS)
        }
    }

    private val startTrailer = Runnable {
        val show = currentShow ?: return@Runnable
        if (idle() || !Trailers.enabled(prefs)) return@Runnable
        val key = prefs.tmdbKey
        val g = popGen
        trailerWanted = true
        io.execute {
            val id = Trailers.find(show, key)
            val cover = id?.let { Trailers.coverScale(it) } ?: 1f
            main.post {
                if (g != popGen) return@post
                if (id == null) { trailerWanted = false; return@post }
                view.trailer.play(id, cover)
            }
        }
    }

    private val switchProvider = Runnable {
        val pkg = pendingPkg ?: return@Runnable
        if (pkg == popularPkg) return@Runnable
        popularPkg = pkg
        loadPopular(pkg, 0)
    }

    fun start() {
        refresh()
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, ROTATE_MS)
        if (currentShow != null) scheduleTrailer()
    }

    fun stop() {
        main.removeCallbacks(rotate)
        main.removeCallbacks(switchProvider)
        main.removeCallbacks(startTrailer)
        trailerWanted = false
        view.trailer.release()
        view.trailerShown(false)
    }

    /** Nobody has touched the remote for a while: no rotation, no trailers, no WebView. */
    private fun idle() = SystemClock.uptimeMillis() - lastInput > IDLE_MS

    /** A remote key arrived; rotation and trailers pick up again. */
    fun poke() {
        val wasIdle = idle()
        lastInput = SystemClock.uptimeMillis()
        if (wasIdle) {
            main.removeCallbacks(rotate)
            main.postDelayed(rotate, ROTATE_MS)
            if (currentShow != null && !trailerWanted) scheduleTrailer()
        }
    }

    /** Settings toggled trailers. */
    fun trailersChanged() {
        if (Trailers.enabled(prefs)) { if (currentShow != null && !trailerWanted) scheduleTrailer() }
        else {
            main.removeCallbacks(startTrailer)
            trailerWanted = false
            view.trailer.release()
            view.trailerShown(false)
        }
    }

    /** Focus moved in the dock: after a short pause, the background follows that app. */
    fun dockFocused(app: AppEntry?) {
        app ?: return
        if (Brands.provider(app.pkg) == null || app.pkg == popularPkg) return
        pendingPkg = app.pkg
        main.removeCallbacks(switchProvider)
        main.postDelayed(switchProvider, 350)
    }

    /** Re-reads watch history for the banner and the dock dot. Safe to call often; stale loads are dropped. */
    fun refresh() {
        val g = ++gen
        io.execute {
            History.init(ctx)
            val play = History.inProgress()
            val weekly = if (play == null) History.weekly() else null
            val playShow = play?.takeIf { it.showGuess.isNotBlank() }?.let { Catalog.resolve(it.guesses) }
            val playApp = play?.let { app(it.pkg) }
            // No show art (app sent no title): the app's own banner card stands in.
            val playArt = playShow?.art?.let { Images.load(it, px(192 * 1.5f)) } ?: playApp?.card
            val weeklyShow = weekly?.let { Catalog.resolve(listOf(it.play.showGuess) + it.play.guesses) }
            val weeklyArt = weeklyShow?.art?.let { Images.load(it, px(192 * 1.5f)) }
            main.post {
                if (g != gen) return@post
                continuePkg = play?.pkg
                when {
                    play != null -> view.banner.setContinue(play, playShow, playArt, playApp?.label ?: Brands.provider(play.pkg)?.name ?: play.pkg) { actions.openPackage(play.pkg) }
                    weekly != null -> view.banner.setWeekly(weekly, weeklyShow, weeklyArt) { actions.openPackage(weekly.play.pkg) }
                    else -> view.banner.hide()
                }
                view.dock.setRunning(play?.pkg)
                if (currentShow == null) {
                    val pkg = popularPkg ?: firstProvider().also { popularPkg = it }
                    loadPopular(pkg, popularIndex)
                }
            }
        }
    }

    private fun loadPopular(pkg: String, index: Int) {
        val provider = Brands.provider(pkg) ?: return
        val ids = (prefs.upNextIds(pkg) + provider.shows).distinct()
        if (ids.isEmpty()) return
        val g = ++popGen
        main.removeCallbacks(startTrailer)
        trailerWanted = false
        if (view.trailer.playing) view.trailer.stop()
        view.trailerShown(false)
        io.execute {
            // Walk the list from [index] to the first show that isn't tagged Horror.
            var i = ((index % ids.size) + ids.size) % ids.size
            var show: Show? = null
            for (attempt in ids.indices) {
                val s = Catalog.show(ids[i])
                if (s != null && s.genres.none { it.equals("Horror", ignoreCase = true) }) { show = s; break }
                i = (i + 1) % ids.size
            }
            val raw = show?.art?.let { Images.load(it, D.screenW) }
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
                view.hero.showPopular(Brands.of(pkg), app?.tile, provider.name, show, prefs.isUpNext(pkg, show))
                if (app == null) view.hero.primary.text = "Get ${provider.name}"
                view.hero.primary.setOnClickListener { actions.openPackage(pkg) }
                view.hero.secondary.setOnClickListener {
                    val saved = prefs.toggleUpNext(pkg, show)
                    view.hero.setUpNext(saved)
                    actions.toast(if (saved) "${show.name} added to Up Next" else "${show.name} removed from Up Next")
                }
                view.setDots(ids.size, picked)
                view.setArt(frame, raw != null)
                Backdrop.apply(blur)
                scheduleTrailer()
            }
        }
    }

    private fun scheduleTrailer() {
        main.removeCallbacks(startTrailer)
        if (Trailers.enabled(prefs) && !idle()) main.postDelayed(startTrailer, PHOTO_MS)
    }

    // ---- TrailerView.Listener ----

    override fun onTrailerPlaying() {
        view.trailerShown(true)
        view.trailer.reveal { view.trailerCovered() }
    }

    override fun onTrailerEnded() {
        trailerWanted = false
        view.artVisible()
        view.trailer.conceal {
            view.trailerShown(false)
            val pkg = popularPkg
            if (pkg != null && popularCount > 1 && !idle()) loadPopular(pkg, popularIndex + 1)
        }
    }

    private fun app(pkg: String) = dockApps.firstOrNull { it.pkg == pkg } ?: allApps.firstOrNull { it.pkg == pkg }

    /** The first streaming app in the dock, then anywhere; with none installed, HBO Max's picks with a "Get" button. */
    private fun firstProvider(): String =
        dockApps.firstOrNull { Brands.provider(it.pkg) != null }?.pkg
            ?: allApps.firstOrNull { Brands.provider(it.pkg) != null }?.pkg
            ?: Brands.DEFAULT_PROVIDER

    /** Uploads the frame to the GPU while still on the worker thread, so the crossfade starts without a hitch. */
    private fun ready(frame: Bitmap): Bitmap = frame.also { it.prepareToDraw() }

    private companion object {
        const val ROTATE_MS = 14_000L
        const val PHOTO_MS = 7_000L
        const val IDLE_MS = 10 * 60_000L
    }
}
