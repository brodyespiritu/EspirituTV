package com.espiritu.tv

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.ExecutorService

/**
 * Decides what fills the Home screen and loads it off the main thread:
 * a show left part-way (Continue watching) wins; otherwise a popular show from the app
 * focused in the dock, rotating every [ROTATE_MS]. Also feeds the weekly banner.
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
        fun showInfo(show: Show?, title: String, pkg: String)
        fun toast(msg: String)
    }

    var dockApps: List<AppEntry> = emptyList()
    var allApps: List<AppEntry> = emptyList()

    private var gen = 0
    private var popGen = 0
    private var continuing = false
    private var popularPkg: String? = null
    private var popularIndex = 0
    private var popularCount = 0
    private var firstFocusDone = false
    private var pendingPkg: String? = null
    private var lastInput = SystemClock.uptimeMillis()

    private val rotate = object : Runnable {
        override fun run() {
            val pkg = popularPkg
            if (!idle() && !continuing && pkg != null && popularCount > 1) loadPopular(pkg, popularIndex + 1)
            main.postDelayed(this, ROTATE_MS)
        }
    }

    /** Nobody has touched the remote for a while: stop rotating art (no work, no burn-in). */
    private fun idle() = SystemClock.uptimeMillis() - lastInput > IDLE_MS

    /** A remote key arrived; rotation picks up again. */
    fun poke() {
        val wasIdle = idle()
        lastInput = SystemClock.uptimeMillis()
        if (wasIdle) {
            main.removeCallbacks(rotate)
            main.postDelayed(rotate, ROTATE_MS)
        }
    }
    private val switchProvider = Runnable {
        val pkg = pendingPkg ?: return@Runnable
        if (pkg == popularPkg) return@Runnable
        popularPkg = pkg
        if (!continuing) loadPopular(pkg, 0)
    }

    fun start() {
        refresh()
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, ROTATE_MS)
    }

    fun stop() {
        main.removeCallbacks(rotate)
        main.removeCallbacks(switchProvider)
    }

    /** Focus moved in the dock: after a short pause, the popular hero follows that app. */
    fun dockFocused(app: AppEntry?) {
        app ?: return
        if (Brands.provider(app.pkg) == null || app.pkg == popularPkg) return
        pendingPkg = app.pkg
        main.removeCallbacks(switchProvider)
        main.postDelayed(switchProvider, 350)
    }

    /** Re-reads watch history and rebuilds the screen. Safe to call often; stale loads are dropped. */
    fun refresh() {
        val g = ++gen
        io.execute {
            History.init(ctx)
            val play = History.inProgress()
            val weekly = History.weekly()
            val show = play?.let { Catalog.resolve(it.guesses) }
            val raw = show?.art?.let { Images.load(it, D.screenW) }
            val frame = if (play != null) ready(Backdrop.compose(raw)) else null
            val blur = frame?.let(Backdrop::blurOf)
            val weeklyShow = weekly?.let { Catalog.resolve(listOf(it.play.showGuess) + it.play.guesses) }
            val thumb = weeklyShow?.art?.let { Images.load(it, px(192 * 1.5f)) }
            main.post {
                if (g != gen) return@post
                if (weekly != null) view.banner.set(weekly, weeklyShow, thumb) { actions.openPackage(weekly.play.pkg) }
                else view.banner.hide()
                view.dock.setRunning(play?.pkg)
                if (play != null && frame != null) showContinue(play, show, frame, raw != null, blur)
                else {
                    continuing = false
                    val pkg = popularPkg ?: firstProvider().also { popularPkg = it }
                    loadPopular(pkg, popularIndex)
                }
            }
        }
    }

    private fun showContinue(play: Play, show: Show?, frame: Bitmap, credited: Boolean, blur: Bitmap?) {
        continuing = true
        popGen++
        val app = app(play.pkg)
        view.hero.showContinue(Brands.of(play.pkg), app?.tile, show, play)
        view.hero.primary.setOnClickListener { actions.openPackage(play.pkg) }
        view.hero.secondary.setOnClickListener { actions.showInfo(show, show?.name ?: play.showGuess, play.pkg) }
        view.setDots(0, 0)
        view.setArt(frame, credited)
        Backdrop.apply(blur)
        if (!firstFocusDone) { firstFocusDone = true; view.hero.primary.requestFocus() }
    }

    private fun loadPopular(pkg: String, index: Int) {
        val provider = Brands.provider(pkg) ?: return
        val ids = (prefs.upNextIds(pkg) + provider.shows).distinct()
        if (ids.isEmpty()) return
        val i = ((index % ids.size) + ids.size) % ids.size
        val id = ids[i]
        val g = ++popGen
        io.execute {
            val show = Catalog.show(id)
            val raw = show?.art?.let { Images.load(it, D.screenW) }
            val frame = if (show != null) ready(Backdrop.compose(raw)) else null
            val blur = frame?.let(Backdrop::blurOf)
            main.post {
                if (g != popGen || continuing) return@post
                if (show == null || frame == null) {
                    // Nothing cached and no network: keep whatever is up, or show the plain scene.
                    if (view.hero.visibility != android.view.View.VISIBLE) view.showEmpty()
                    return@post
                }
                popularIndex = i
                popularCount = ids.size
                val app = app(pkg)
                view.hero.showPopular(Brands.of(pkg), app?.tile, provider.name, show, prefs.isUpNext(pkg, show))
                if (app == null) view.hero.primary.text = "Get ${provider.name}"
                view.hero.primary.setOnClickListener { actions.openPackage(pkg) }
                view.hero.secondary.setOnClickListener {
                    val saved = prefs.toggleUpNext(pkg, show)
                    view.hero.setUpNext(saved)
                    actions.toast(if (saved) "${show.name} added to Up Next" else "${show.name} removed from Up Next")
                }
                view.setDots(ids.size, i)
                view.setArt(frame, raw != null)
                Backdrop.apply(blur)
                firstFocusDone = true
            }
        }
    }

    /** Uploads the frame to the GPU while still on the worker thread, so the crossfade starts without a hitch. */
    private fun ready(frame: Bitmap): Bitmap = frame.also { it.prepareToDraw() }

    private fun app(pkg: String) = dockApps.firstOrNull { it.pkg == pkg } ?: allApps.firstOrNull { it.pkg == pkg }

    /** The first streaming app in the dock, then anywhere; with none installed, HBO Max's picks with a "Get" button. */
    private fun firstProvider(): String =
        dockApps.firstOrNull { Brands.provider(it.pkg) != null }?.pkg
            ?: allApps.firstOrNull { Brands.provider(it.pkg) != null }?.pkg
            ?: Brands.DEFAULT_PROVIDER

    private companion object {
        const val ROTATE_MS = 14_000L
        const val IDLE_MS = 10 * 60_000L
    }
}
