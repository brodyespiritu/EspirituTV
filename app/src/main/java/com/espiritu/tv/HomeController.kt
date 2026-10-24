package com.espiritu.tv

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import java.util.concurrent.ExecutorService

/**
 * Decides what fills the Home screen and loads it off the main thread.
 * - Banner: the show left part-way (Continue watching) wins; otherwise the weekly show; otherwise nothing.
 * - Hero and background: a slideshow of its own, untouched by which app card has focus. It takes turns between
 *   your streaming services (mostly brand-new series, never Horror, Up Next picks first), each show's text-free
 *   backdrop drifting slowly closer, a new one every [ROTATE_MS], in a fresh order on every Home visit.
 *   Right past the hero's last button goes to the next show, left past its first to the one before.
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

    /** One show in the slideshow, and the app it's on. */
    private class Slide(val pkg: String, val showId: Int)

    private var gen = 0
    private var popGen = 0
    private var slides: List<Slide> = emptyList()
    /** The streaming apps [slides] was made from; a change (an app installed or removed) remakes it. */
    private var slidesFrom: List<String>? = null
    private var slideIndex = 0
    private var currentShow: Show? = null
    private var lastInput = SystemClock.uptimeMillis()
    /** New on every Home visit: the rotation's order. */
    private var visitSeed = 0L

    private val rotate = object : Runnable {
        override fun run() {
            if (!idle() && slides.size > 1) loadSlide(slideIndex + 1, 1)
            main.postDelayed(this, ROTATE_MS)
        }
    }

    init {
        // Stepping through the slideshow by hand, as on Apple TV's top shelf.
        view.hero.secondary.setOnKeyListener { _, key, ev -> key == KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == KeyEvent.ACTION_DOWN && step(1) }
        view.hero.primary.setOnKeyListener { _, key, ev -> key == KeyEvent.KEYCODE_DPAD_LEFT && ev.action == KeyEvent.ACTION_DOWN && step(-1) }
    }

    /** The next (1) or previous (-1) show, and a full [ROTATE_MS] before the slideshow moves on by itself. */
    private fun step(dir: Int): Boolean {
        if (slides.size < 2) return false
        loadSlide(slideIndex + dir, dir)
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, ROTATE_MS)
        return true
    }

    /** Home is back in front: a fresh order of shows, starting over, and the background moving again. */
    fun start() {
        visitSeed = System.nanoTime()
        slidesFrom = null
        slideIndex = 0
        currentShow = null
        view.back.motion = prefs.motion
        view.back.resume()
        refresh()
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, ROTATE_MS)
    }

    fun stop() {
        main.removeCallbacks(rotate)
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
                // The slideshow waits for the app list (it's made from the streaming apps you have).
                if (allApps.isNotEmpty()) {
                    val from = streamingApps()
                    if (from != slidesFrom) {
                        slidesFrom = from
                        slides = makeSlides(from)
                        slideIndex = 0
                        currentShow = null
                    }
                    if (currentShow == null) loadSlide(slideIndex, 1)
                }
            }
        }
    }

    /**
     * The slideshow for this visit: each streaming service's shows (Up Next picks first, the rest in an order that
     * changes every visit), dealt out in turns so it keeps moving between services, starting with a different one
     * each visit. [MAX_SLIDES] at most, so the dots stay readable.
     */
    private fun makeSlides(pkgs: List<String>): List<Slide> {
        val turns = pkgs.mapNotNull { pkg ->
            val provider = Brands.provider(pkg) ?: return@mapNotNull null
            pkg to (prefs.upNextIds(pkg) + provider.shows.shuffled(java.util.Random(visitSeed xor pkg.hashCode().toLong()))).distinct()
        }
        if (turns.isEmpty()) return emptyList()
        val first = ((visitSeed % turns.size).toInt() + turns.size) % turns.size
        val order = turns.drop(first) + turns.take(first)
        val out = ArrayList<Slide>()
        var round = 0
        while (out.size < MAX_SLIDES) {
            var dealt = false
            for ((pkg, ids) in order) {
                if (round < ids.size && out.size < MAX_SLIDES) { out += Slide(pkg, ids[round]); dealt = true }
            }
            if (!dealt) break
            round++
        }
        return out
    }

    /** Shows slide [index] (wrapping), walking on in [dir] past anything tagged Horror. */
    private fun loadSlide(index: Int, dir: Int) {
        val list = slides
        if (list.isEmpty()) return
        val g = ++popGen
        io.execute {
            var i = ((index % list.size) + list.size) % list.size
            var show: Show? = null
            for (attempt in list.indices) {
                val s = Catalog.show(list[i].showId)
                if (s != null && s.genres.none { it.equals("Horror", ignoreCase = true) }) { show = s; break }
                i = ((i + dir) % list.size + list.size) % list.size
            }
            val pkg = list[i].pkg
            val provider = Brands.provider(pkg)
            val url = show?.let(::artUrl)
            val raw = url?.let { Images.load(it, D.screenW) }
            val creditText = when { raw == null -> null; url!!.contains("image.tmdb.org") -> "Art: TMDB"; else -> "Art: TVmaze" }
            val logo = show?.let { Brands.logo(it.id) }?.let { Images.load(it, 1000) }
            val frame = if (show != null) ready(Backdrop.compose(raw)) else null
            val blur = frame?.let(Backdrop::blurOf)
            val picked = i
            main.post {
                if (g != popGen) return@post
                if (show == null || frame == null || provider == null) {
                    // Nothing cached and no network: keep whatever is up, or show the plain scene.
                    if (view.hero.visibility != View.VISIBLE) view.showEmpty()
                    return@post
                }
                slideIndex = picked
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
                view.setDots(list.size, picked)
                view.setArt(frame, creditText)
                Backdrop.apply(blur)
            }
        }
    }

    /** Text-free built-in backdrop when the show is in a lineup, else TVmaze's. */
    private fun artUrl(show: Show): String? = Brands.backdrop(show.id) ?: show.art

    private fun app(pkg: String) = pinnedApps.firstOrNull { it.pkg == pkg } ?: allApps.firstOrNull { it.pkg == pkg }

    /**
     * One app per streaming service you have (pinned cards first; a second Netflix app doesn't count twice).
     * With none installed, HBO Max's picks with a "Get" button.
     */
    private fun streamingApps(): List<String> {
        val seen = HashSet<Provider>()
        val out = (pinnedApps + allApps).map { it.pkg }.distinct().filter { pkg -> Brands.provider(pkg)?.let(seen::add) == true }
        return out.ifEmpty { listOf(Brands.DEFAULT_PROVIDER) }
    }

    /** Uploads the frame to the GPU while still on the worker thread, so the crossfade starts without a hitch. */
    private fun ready(frame: Bitmap): Bitmap = frame.also { it.prepareToDraw() }

    private companion object {
        const val ROTATE_MS = 14_000L
        const val MAX_SLIDES = 10
        const val IDLE_MS = 10 * 60_000L
    }
}
