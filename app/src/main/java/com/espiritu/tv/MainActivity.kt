package com.espiritu.tv

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity(), AppGrid.Host, HomeController.Actions {

    private lateinit var prefs: Prefs
    private lateinit var home: HomeView
    private lateinit var controller: HomeController
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var apps: List<AppEntry> = emptyList()
    private var inputs: List<Pair<String, Uri>> = emptyList()
    private val loadGeneration = AtomicInteger()
    private var openSheet: Sheet? = null
    private var firstRender = true

    private val reloadRunnable = Runnable { reload() }
    private val refreshRunnable = Runnable { controller.refresh() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            main.removeCallbacks(reloadRunnable)
            main.postDelayed(reloadRunnable, 400)
        }
    }

    private val tvInputs by lazy { getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = loadInputs()
        override fun onInputRemoved(inputId: String) = loadInputs()
        override fun onInputUpdated(inputId: String) = loadInputs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        D.init(this)
        Fonts.init(this)
        Net.init(this)
        Images.init(this)
        History.init(this)
        prefs = Prefs(this)

        home = HomeView(this, this)
        setContentView(home)
        // Home always covers the window, so nothing should be painted underneath it. The window is see-through
        // where the background art's own surface sits behind it.
        window.setBackgroundDrawable(null)
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        // The loading screen covers Home until the first art is on screen (cold start only).
        home.showSplash()
        // The Inputs and Settings cards show on the first frame; apps fill in as soon as they're read.
        home.grid.setApps(emptyList(), emptyList())
        home.avatar.setName(prefs.profile)
        home.avatar.setOnClickListener { showSettings() }
        home.update.setOnClickListener { Updater.available?.let { showUpdate(it) } }
        controller = HomeController(this, home, prefs, io, main, this)
        home.banner.button.setOnLongClickListener { controller.continuePkg?.let { askWatching(it) } != null }
        home.banner.button.setOnKeyListener { _, key, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_MENU) { controller.continuePkg?.let { askWatching(it) }; true } else false
        }

        Updater.onChange = { home.updateAvailable = Updater.available != null }
        Updater.cleanUp(this)

        if (prefs.newsAlerts) NewsService.start(this)

        // The media watcher runs in this process, so history changes reach Home directly.
        History.onChange = {
            main.removeCallbacks(refreshRunnable)
            main.postDelayed(refreshRunnable, 1500)
        }

        loadInputs()
        reload()
        try { tvInputs?.registerCallback(inputCallback, main) } catch (t: Throwable) {}

        register(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        register(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        })
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
    }

    /** The TV kills the launcher while apps play, so Home reads the media sessions itself whenever it's in front. */
    private val scanRunnable = object : Runnable {
        override fun run() {
            io.execute { MediaWatcher.scan(this@MainActivity) }
            main.postDelayed(this, 30_000)
        }
    }

    override fun onResume() {
        super.onResume()
        loadInputs()
        main.removeCallbacks(scanRunnable)
        scanRunnable.run()
        controller.start()
        Updater.checkDaily(this)
        // Back from the "Install unknown apps" switch with a verified APK waiting: carry on.
        Updater.pending?.let { apk -> if (Updater.canInstall(this)) { Updater.pending = null; startInstall(apk) } }
    }

    override fun onPause() {
        main.removeCallbacks(scanRunnable)
        controller.stop()
        super.onPause()
    }

    /** Hold OK on the Continue-watching banner: name what an app that sends no title is playing. */
    /** Hold OK on the Continue-watching banner: the show's name and episode, for an app that doesn't say (Hulu). */
    private fun askWatching(pkg: String) {
        val label = apps.firstOrNull { it.pkg == pkg }?.label ?: Brands.provider(pkg)?.name ?: "this app"
        val current = History.all().lastOrNull { it.pkg == pkg }
        val input = EditText(this).apply {
            setText(prefs.watching(pkg).orEmpty())
            hint = "Show name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val episode = EditText(this).apply {
            if (current != null && current.episode > 0) setText("S${current.season}E${current.episode}")
            hint = "Season and episode, like S3E7"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
        }
        val form = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(40), px(10), px(40), 0)
            addView(input)
            addView(episode)
        }
        AlertDialog.Builder(this)
            .setTitle("What are you watching on $label?")
            .setMessage("After this, the TV moves to the next episode by itself whenever one starts.")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                val old = prefs.watching(pkg)
                prefs.setWatching(pkg, name)
                if (name.isNotEmpty()) History.relabel(this, pkg, name, old)
                val text = episode.text.toString().trim()
                val se = History.seasonEpisode(text) ?: Regex("""^\s*(\d{1,2})\D+(\d{1,3})\s*$""").find(text)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                if (se != null) History.setEpisode(this, pkg, se.first, se.second)
                controller.refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        History.onChange = null
        Updater.onChange = null
        try { tvInputs?.unregisterCallback(inputCallback) } catch (t: Throwable) {}
        unregisterReceiver(packageReceiver)
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    /** Pressing Home while already home: close panels and go back to the first card. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSheet?.dismiss()
        home.grid.finishMove()
        home.grid.firstCard?.requestFocus()
    }

    @Deprecated("Back on the home screen returns to the first card.")
    override fun onBackPressed() {
        home.grid.finishMove()
        home.grid.firstCard?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) controller.poke()
        return super.dispatchKeyEvent(event)
    }

    // ---- Data ----

    private fun reload() {
        if (io.isShutdown) return
        val gen = loadGeneration.incrementAndGet()
        val ctx = applicationContext
        io.execute {
            val loaded = AppLoader.load(ctx) { gen != loadGeneration.get() } ?: return@execute
            main.post {
                if (gen != loadGeneration.get() || isFinishing || isDestroyed) return@post
                apps = loaded
                render()
            }
        }
    }

    private fun loadInputs() {
        if (io.isShutdown) return
        io.execute {
            val found = try {
                tvInputs?.tvInputList.orEmpty()
                    .filter { it.isPassthroughInput && !(Build.VERSION.SDK_INT >= 24 && it.isHidden(this)) }
                    .mapNotNull { info ->
                        try {
                            val custom = if (Build.VERSION.SDK_INT >= 24) info.loadCustomLabel(this) else null
                            val label = custom?.toString()?.takeIf { it.isNotBlank() }
                                ?: info.loadLabel(this)?.toString()?.takeIf { it.isNotBlank() }
                                ?: info.id
                            label to TvContract.buildChannelUriForPassthroughInput(info.id)
                        } catch (t: Throwable) {
                            null
                        }
                    }
            } catch (t: Throwable) {
                emptyList()
            }
            main.post { inputs = found }
        }
    }

    private fun render() {
        if (!prefs.dockInitialized && apps.isNotEmpty()) prefs.dock = AppLoader.defaultDock(apps)
        val byPkg = apps.associateBy { it.pkg }
        val hidden = prefs.hidden
        val pinned = prefs.dock.mapNotNull { byPkg[it] }.filter { it.pkg !in hidden }
        val pinnedPkgs = pinned.mapTo(HashSet()) { it.pkg }
        controller.pinnedApps = pinned
        controller.allApps = apps.filter { it.pkg !in hidden }
        // Pinned apps lead, then everything else alphabetically.
        home.grid.setApps(pinned, apps.filter { it.pkg !in hidden && it.pkg !in pinnedPkgs })
        if (firstRender) {
            firstRender = false
            if (openSheet == null) home.grid.firstCard?.requestFocus()
            controller.refresh()
        }
    }

    // ---- Cards ----

    override fun openApp(app: AppEntry) {
        val category = if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (launch(intent)) return
        if (!openPackageOrNull(app.pkg)) toast("Couldn't open ${app.label}")
    }

    /**
     * Back into the show where it stopped. Apps keep their media session, paused, after you leave; its
     * "now playing" screen reopens the player on that episode, and play() continues from the same second.
     * Without a session (the app was fully closed) the app opens normally.
     */
    override fun resumePlayback(pkg: String) {
        val session = MediaWatcher.session(this, pkg)
        if (session == null) { openPackage(pkg); return }
        val opened = session.sessionActivity?.let { try { it.send(); true } catch (e: PendingIntent.CanceledException) { false } } ?: false
        if (!opened) openPackage(pkg)
        main.postDelayed({ try { session.transportControls.play() } catch (t: Throwable) {} }, 900)
    }

    /** Opens the app, or its Play Store page when it isn't installed (the hero's "Get …" button). */
    override fun openPackage(pkg: String) {
        apps.firstOrNull { it.pkg == pkg }?.let { openApp(it); return }
        if (openPackageOrNull(pkg)) return
        if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            toast("That app isn't installed")
    }

    private fun openPackageOrNull(pkg: String): Boolean {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun showAppOptions(app: AppEntry) {
        val pinned = prefs.dock
        val isPinned = app.pkg in pinned
        show(Sheet(this, app.label, if (isPinned) "Pinned to the front" else null, app.tile).apply {
            option(R.drawable.ic_play, "Open") { openApp(app) }
            if (isPinned) {
                option(R.drawable.ic_star, "Unpin") { prefs.dock = prefs.dock - app.pkg; render() }
                if (pinned.size > 1) option(R.drawable.ic_swap, "Move") { startMove(app) }
            } else {
                option(R.drawable.ic_star_outline, "Pin to the front") { prefs.dock = prefs.dock + app.pkg; render() }
            }
            option(R.drawable.ic_hide, "Hide from Home") {
                prefs.hidden = prefs.hidden + app.pkg
                prefs.dock = prefs.dock - app.pkg
                render()
                toast("${app.label} hidden. Restore it from Settings.")
            }
            option(R.drawable.ic_info, "App info") {
                launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (!app.system) option(R.drawable.ic_delete, "Uninstall") {
                launch(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
    }

    private fun startMove(app: AppEntry) {
        if (home.grid.startMove(app.pkg)) toast("Use ◀ ▶ to move, then press OK")
    }

    override fun appsReordered(pkgs: List<String>) {
        prefs.dock = pkgs
    }

    override fun rowFocused(row: Int) = home.scrollToRow(row)

    override fun showInputs() {
        show(Sheet(this, "Inputs", "Switch to a connected device").apply {
            if (inputs.isEmpty()) empty("No inputs found")
            inputs.forEach { (label, uri) ->
                option(R.drawable.ic_tv, label) {
                    if (!launch(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) toast("Couldn't switch to $label")
                }
            }
        })
    }

    override fun showSettings() {
        val hiddenCount = apps.count { it.pkg in prefs.hidden }
        val upNext = prefs.upNext.size
        show(Sheet(this, "Settings", "EspirituTV ${versionName()}").apply {
            section("You")
            option(R.drawable.ic_visibility, "Name: ${prefs.profile}") { askName() }
            section("Home")
            option(R.drawable.ic_play, if (prefs.motion) "Background motion: on" else "Background motion: off") {
                prefs.motion = !prefs.motion
                controller.motionChanged()
                toast(if (prefs.motion) "Background motion on" else "Background motion off")
            }
            val overlayOk = Settings.canDrawOverlays(this@MainActivity)
            option(R.drawable.ic_info, when {
                !prefs.newsAlerts -> "CNAW2 news banners: off"
                !overlayOk -> "CNAW2 news banners: need 'draw over apps' (install.sh)"
                else -> "CNAW2 news banners: on"
            }) {
                prefs.newsAlerts = !prefs.newsAlerts
                if (prefs.newsAlerts) NewsService.start(this@MainActivity) else NewsService.stop(this@MainActivity)
                toast(if (prefs.newsAlerts) "News banners on" else "News banners off")
            }
            if (prefs.newsAlerts && overlayOk) option(R.drawable.ic_play, "Show a test news banner") { NewsService.start(this@MainActivity, NewsService.ACTION_TEST) }
            option(R.drawable.ic_hide, "Hidden apps ($hiddenCount)") { showHidden() }
            option(R.drawable.ic_star, "Clear Up Next ($upNext)") { prefs.upNext = emptyList(); controller.refresh(); toast("Up Next cleared") }
            option(R.drawable.ic_delete, "Forget watch history") { History.clear(this@MainActivity); toast("Watch history cleared") }
            section("System")
            option(R.drawable.ic_tv, "Watch-history access") {
                if (!launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                    toast("Turn it on with install.sh, or in Settings → Apps → Special access → Notification access")
            }
            option(R.drawable.ic_settings, "Android settings") { openSettings() }
            val release = Updater.available
            if (release != null) option(R.drawable.ic_download, "Update to ${release.versionName}") { showUpdate(release) }
            else option(R.drawable.ic_download, "Check for updates") { checkForUpdates() }
            section("Art and show details: TVmaze.com (CC BY-SA)")
        })
    }

    // ---- Updates ----

    private fun checkForUpdates() {
        toast("Checking for updates…")
        Updater.check(this, force = true) { result ->
            if (isFinishing || isDestroyed) return@check
            when (result) {
                is Updater.Result.Available -> showUpdate(result.release)
                Updater.Result.UpToDate -> toast("EspirituTV ${versionName()} is up to date")
                Updater.Result.Failed -> toast("Couldn't reach the update server")
            }
        }
    }

    /** What's new and a Download row that shows progress in place, then hands the verified APK to the installer. */
    private fun showUpdate(release: Updater.Release) {
        show(Sheet(this, "Update to ${release.versionName}", "EspirituTV ${versionName()} → ${release.versionName}").apply {
            if (release.notes.isNotBlank()) text(release.notes)
            var busy = false
            option(getDrawable(R.drawable.ic_download), "Download and install", dismiss = false) { row ->
                if (busy) return@option
                busy = true
                val label = row.findViewById<TextView>(R.id.rowText)
                Updater.download(this@MainActivity, release, { label.text = "Downloading… $it%" }) { apk, error ->
                    if (apk == null) {
                        busy = false
                        label.text = "Download and install"
                        toast(error ?: "Couldn't download the update")
                        return@download
                    }
                    dismiss()
                    startInstall(apk)
                }
            }
            option(null, "Not now") {}
        })
    }

    /** The TV must allow installs from EspirituTV first (one switch, once); onResume finishes the install after that. */
    private fun startInstall(apk: File) {
        if (!Updater.canInstall(this)) {
            Updater.pending = apk
            if (launch(Updater.allowIntent(this))) { toast("Allow EspirituTV to install updates, then press Back"); return }
            Updater.pending = null // No such screen on this TV: let the installer explain.
        }
        toast("Installing EspirituTV ${Updater.available?.versionName ?: "update"}…")
        Updater.install(this, apk) { error -> if (error != null) toast(error) }
    }

    private fun askName() {
        val input = EditText(this).apply {
            setText(if (prefs.profile == "Espiritu") "" else prefs.profile)
            hint = "Your name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("What should we call you?")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> prefs.profile = input.text.toString(); home.avatar.setName(prefs.profile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHidden() {
        val hidden = prefs.hidden
        val hiddenApps = apps.filter { it.pkg in hidden }
        show(Sheet(this, "Hidden apps", "Select an app to show it again").apply {
            if (hiddenApps.isEmpty()) empty("Nothing hidden")
            hiddenApps.forEach { a ->
                option(a.tile?.let { BitmapDrawable(resources, it) }, a.label, tint = false) {
                    prefs.hidden = prefs.hidden - a.pkg
                    render()
                }
            }
        })
    }

    override fun showInfo(show: Show?, title: String, pkg: String) {
        val appLabel = apps.firstOrNull { it.pkg == pkg }?.label ?: "the app"
        show(Sheet(this, title, listOfNotNull(show?.genres?.take(3)?.joinToString(" · ")?.ifEmpty { null }, show?.year, show?.network)
            .joinToString(" · ")).apply {
            if (show != null && show.summary.isNotBlank()) text(show.summary)
            option(R.drawable.ic_play, "Open $appLabel") { openPackage(pkg) }
        })
    }

    private fun openSettings() {
        launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Helpers ----

    private fun show(sheet: Sheet) {
        openSheet?.dismiss()
        openSheet = sheet
        sheet.setOnDismiss { if (openSheet === sheet) openSheet = null }
        sheet.show()
    }

    private fun versionName() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (t: Throwable) { "" }

    private fun launch(intent: Intent): Boolean = try {
        startActivity(intent); true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
