package com.espiritu.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.concurrent.Executors

/**
 * Watches CNAW2 for a new article and shows it as the hanging banner over whatever is on screen,
 * a playing show included, for five seconds before it slides away. A small foreground service keeps the
 * watcher alive while other apps are in front (the TV kills plain background apps); the banner itself is
 * an overlay window, which needs "draw over other apps" (install.sh grants it over adb).
 */
class NewsService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var overlay: NewsOverlay? = null
    private val poll = object : Runnable {
        override fun run() {
            check(test = false)
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        D.init(this)
        Fonts.init(this)
        Net.init(this)
        Images.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "CNAW2 news watcher", NotificationManager.IMPORTANCE_MIN))
        }
        @Suppress("DEPRECATION")
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_info).setContentTitle("EspirituTV").setContentText("Watching CNAW2 for new articles").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        main.removeCallbacks(poll)
        main.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) check(test = true)
        return START_STICKY
    }

    /** Looks once. The first look only remembers where the feed stands, so an old article never pops up on install. */
    private fun check(test: Boolean) {
        val prefs = Prefs(this)
        if (!prefs.newsAlerts && !test) return
        io.execute {
            val a = News.latest() ?: return@execute
            if (!test) {
                val last = prefs.lastNewsId
                if (last == 0L) { prefs.lastNewsId = a.id; return@execute }
                if (a.id <= last) return@execute
                prefs.lastNewsId = a.id
            }
            val thumb = a.image?.let { Images.load(it, px(192 * 1.5f)) }
            main.post { show(a, thumb) }
        }
    }

    private fun show(a: Article, thumb: Bitmap?) {
        if (!Settings.canDrawOverlays(this)) { Log.w(TAG, "news banner skipped: no 'draw over other apps' permission"); return }
        (overlay ?: NewsOverlay(this).also { overlay = it }).show(a, thumb)
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        io.shutdownNow()
        overlay?.dismissNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_TEST = "com.espiritu.tv.NEWS_TEST"
        private const val TAG = "EspirituTV"
        private const val CHANNEL = "news"
        private const val ID = 7
        private const val POLL_MS = 60_000L

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, NewsService::class.java).apply { this.action = action }
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (t: Throwable) { Log.w(TAG, "news watcher: $t") }
        }

        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, NewsService::class.java)) } catch (t: Throwable) {} }
    }
}

/** Starts the watcher at boot, so it runs even before Home is first shown. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(ctx).newsAlerts) NewsService.start(ctx)
    }
}

/** The banner as an overlay window at the banner's usual spot: slides down from the top edge, holds, slides back up. */
class NewsOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val view = NewsBannerView(ctx)
    private var attached = false
    private val hide = Runnable { slideOut() }

    fun show(a: Article, thumb: Bitmap?) {
        view.set(a, thumb)
        main.removeCallbacks(hide)
        view.animate().cancel()
        if (!attached) {
            try { wm.addView(view, params()) } catch (t: Throwable) { Log.w("EspirituTV", "news banner: $t"); return }
            attached = true
            view.translationY = -pxf(300)
            view.alpha = 1f
        }
        view.animate().translationY(0f).alpha(1f).setDuration(450).setInterpolator(DecelerateInterpolator(1.6f)).start()
        main.postDelayed(hide, HOLD_MS + 450)
    }

    private fun slideOut() {
        view.animate().translationY(-(view.height + px(20)).toFloat()).alpha(0f).setDuration(400)
            .setInterpolator(AccelerateInterpolator(1.4f)).withEndAction { dismissNow() }.start()
    }

    fun dismissNow() {
        main.removeCallbacks(hide)
        if (attached) {
            try { wm.removeView(view) } catch (t: Throwable) {}
            attached = false
        }
    }

    @Suppress("DEPRECATION")
    private fun params() = WindowManager.LayoutParams(
        px(460), WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = px(1249); y = 0 }

    private companion object { const val HOLD_MS = 5_000L }
}

/** The hanging banner, news edition: a red dot and "New on CNAW2", the picture, the headline, the site. */
class NewsBannerView(ctx: Context) : LinearLayout(ctx) {
    private val thumb = ImageView(ctx)
    private val title = ctx.label(25, Fonts.semibold)
    private val line = ctx.label(18, Fonts.regular, C.DIM)

    init {
        orientation = VERTICAL
        setPadding(px(24), px(26), px(24), px(16))
        // Over another app there is no art to frost, so this glass is plain and dark.
        background = Glass(this, radius = pxf(36), bottomOnly = true, plain = true, top = 0.22f, bottom = 0.12f)
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(ctx).apply { background = pill(0xFFFF3B30.toInt(), pxf(6)) }, LayoutParams(px(12), px(12)).apply { marginEnd = px(12) })
        head.addView(ctx.label(19, Fonts.medium, C.white(0.85f), "New on CNAW2"))
        addView(head)
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = pill(C.white(0.1f), pxf(16), C.white(0.14f))
        row.addView(FrameLayout(ctx).apply { addView(thumb, FrameLayout.LayoutParams(px(192), px(108))) }, LayoutParams(px(192), px(108)))
        val text = LinearLayout(ctx).apply { orientation = VERTICAL }
        title.setLineSpacing(0f, 1.15f)
        title.maxLines = 3
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        text.addView(title)
        text.addView(line, LayoutParams(WRAP, WRAP).apply { topMargin = px(6) })
        row.addView(text, LayoutParams(0, WRAP, 1f).apply { marginStart = px(20) })
        addView(row, LayoutParams(MATCH, WRAP).apply { topMargin = px(16) })
        addView(View(ctx).apply { background = pill(C.white(0.4f), pxf(3)) },
            LayoutParams(px(64), px(6)).apply { topMargin = px(16); gravity = Gravity.CENTER_HORIZONTAL })
    }

    fun set(a: Article, art: Bitmap?) {
        title.text = a.title
        line.text = News.SITE
        thumb.setImageBitmap(art?.let { AppLoaderArt.roundedCrop(it, px(192), px(108), pxf(16)) })
    }
}
