package com.espiritu.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    /** Squircle icon (options panel header, the hero's source mark for apps without a brand mark). */
    val tile: Bitmap?,
    /** The Home card (5:3): brand colour + logo for known apps, else the app's TV banner, else its icon on its colour. */
    val card: Bitmap?,
    val leanback: Boolean,
    val system: Boolean,
)

object AppLoader {
    private const val DEFAULT_ACCENT = 0xFF2A3145.toInt()
    const val TILE = 96            // icon, canvas px
    const val CARD_W = 296         // Home card, canvas px: five to a row across 1680 with 50 px gaps,
    const val CARD_H = 178         // in tvOS's 5:3 icon shape
    /** Corner radius of a card, canvas px (Apple's continuous corner, as on tvOS icons). */
    const val CARD_RADIUS = 38f

    fun defaultDock(apps: List<AppEntry>): List<String> {
        val have = apps.mapTo(HashSet()) { it.pkg }
        val picked = Brands.PREFERRED.filter { it in have }.take(10)
        return picked.ifEmpty { apps.filter { it.leanback }.take(6).map { it.pkg } }
    }

    private class Art(val version: Long, val tile: Bitmap?, val card: Bitmap?)

    /** Rendered art per package, reused until the app updates. Survives reloads; the disk copy survives restarts. */
    private val memCache = HashMap<String, Art>()

    /**
     * Loads every launchable app and pre-renders its art once, so the cards never decode while moving.
     * [isStale] lets a newer reload cancel this one early.
     */
    fun load(ctx: Context, isStale: () -> Boolean = { false }): List<AppEntry>? {
        val pm = ctx.packageManager
        val tilePx = px(TILE * 1.4f)
        val cardW = px(CARD_W * 1.1f)
        val cardH = cardW * CARD_H / CARD_W
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        val diskDir = File(ctx.cacheDir, "art").apply { mkdirs() }
        var stale = false

        fun collect(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val found = try { pm.queryIntentActivities(intent, 0) } catch (e: Exception) { emptyList() }
            for (ri in found) {
                if (isStale()) { stale = true; return }
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName || !seen.add(ai.packageName)) continue
                val cn = ComponentName(ai.packageName, ai.name)
                val label = (ri.loadLabel(pm)?.toString() ?: "").trim().ifEmpty { ai.packageName }
                val version = safe { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime } ?: 0L
                val key = artKey(ai.packageName, ai.name, tilePx)
                val art = memCache[key]?.takeIf { it.version == version }
                    ?: readDisk(diskDir, key, version)
                    ?: run {
                        val icon = safe { ri.loadIcon(pm) }
                        val banner = safe { pm.getActivityBanner(cn) } ?: safe { pm.getApplicationBanner(ai.applicationInfo) }
                        val accent = icon?.let { render(it, 48, 48) }?.let(::accentOf) ?: DEFAULT_ACCENT
                        Art(version, tile(ctx, ai.packageName, icon, accent, tilePx), card(ctx, ai.packageName, banner, icon, accent, cardW, cardH))
                            .also { writeDisk(diskDir, key, it) }
                    }
                memCache[key] = art
                out += AppEntry(
                    pkg = ai.packageName,
                    component = cn,
                    label = label,
                    tile = art.tile,
                    card = art.card,
                    leanback = leanback,
                    system = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
        }
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        if (!stale) collect(Intent.CATEGORY_LAUNCHER, false)
        if (stale) return null
        memCache.keys.retainAll(out.mapTo(HashSet()) { artKey(it.pkg, it.component.className, tilePx) })
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return out
    }

    /** The canvas dock tile: brand colour + logo for known apps, otherwise the app's own icon, cut to a squircle. */
    fun tile(ctx: Context, pkg: String, icon: Drawable?, accent: Int, size: Int): Bitmap? = safe {
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(content)
        val brand = Brands.of(pkg)
        val mark = brand?.let { safe { ctx.getDrawable(it.mark) } }
        if (brand != null && mark != null) {
            c.drawColor(brand.bg)
            val aspect = mark.intrinsicWidth.toFloat() / mark.intrinsicHeight.coerceAtLeast(1)
            val w: Float; val h: Float
            if (brand.wordmark) { w = size * (if (aspect > 1.1f) 0.55f else 0.62f); h = w / aspect }
            else { h = size * 0.46f; w = h * aspect }
            mark.setBounds(((size - w) / 2).toInt(), ((size - h) / 2).toInt(), ((size + w) / 2).toInt(), ((size + h) / 2).toInt())
            mark.draw(c)
        } else if (icon != null && Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
            // Adaptive layers are 108 units with the visible 72 in the middle: scale them so that part fills the tile.
            val o = size / 4
            icon.background?.apply { setBounds(-o, -o, size + o, size + o); draw(c) }
            icon.foreground?.apply { setBounds(-o, -o, size + o, size + o); draw(c) }
        } else {
            c.drawColor(accent)
            if (icon != null) {
                val inset = (size * 0.14f).toInt()
                icon.setBounds(inset, inset, size - inset, size - inset)
                icon.draw(c)
            }
        }
        masked(content, size)
    }

    private fun masked(content: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawPath(Squircle.path(size.toFloat(), size.toFloat()), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(content, 0f, 0f, p)
        content.recycle()
        return out
    }

    /** The card art, already cut to the card's rounded shape so Home never has to clip it. */
    private fun card(ctx: Context, pkg: String, banner: Drawable?, icon: Drawable?, accent: Int, w: Int, h: Int): Bitmap? =
        cardArt(ctx, pkg, banner, icon, accent, w, h)?.let { b -> safe { Continuous.clip(b, w * CARD_RADIUS / CARD_W) }.also { b.recycle() } }

    /**
     * A card that reads as one horizontal app icon, as on tvOS, never a small square icon parked on a colour:
     * 1. the app's own TV banner, its official horizontal logo (Netflix, YouTube, Disney+… all ship one), filling the card;
     * 2. a known brand without a banner: the brand colour with its logo;
     * 3. an adaptive icon: its background layer becomes the whole card and its foreground the logo;
     * 4. a filled square icon: the card takes the colour of the icon's edge and the icon's rim fades into it,
 *    so only the logo stands out;
 * 5. anything else (a round badge, a bare glyph): the logo on a deep tone of its own colour.
     */
    private fun cardArt(ctx: Context, pkg: String, banner: Drawable?, icon: Drawable?, accent: Int, w: Int, h: Int): Bitmap? = safe {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val brand = Brands.of(pkg)
        if (banner != null) {
            // TV banners are 16:9 and the card is a little taller: fill the height and trim the sides (a few %).
            c.drawColor(brand?.bg ?: accent)
            val bw = maxOf(w, h * 16 / 9)
            val bh = bw * 9 / 16
            banner.setBounds((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
            banner.draw(c)
            return@safe b
        }
        val mark = brand?.let { safe { ctx.getDrawable(it.mark) } }
        if (brand != null && mark != null) {
            // The canvas card: brand colour, logo centred (wordmarks by width, symbols by height).
            c.drawColor(brand.bg)
            val aspect = mark.intrinsicWidth.toFloat() / mark.intrinsicHeight.coerceAtLeast(1)
            val mw: Float; val mh: Float
            if (brand.wordmark) { mw = w * (if (aspect > 1.1f) 0.5f else 0.56f); mh = mw / aspect }
            else { mh = h * 0.5f; mw = mh * aspect }
            mark.setBounds(((w - mw) / 2).toInt(), ((h - mh) / 2).toInt(), ((w + mw) / 2).toInt(), ((h + mh) / 2).toInt())
            mark.draw(c)
            return@safe b
        }
        if (icon != null && Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
            // The background layer (a colour, gradient or pattern) stretched over the card; the foreground's visible
            // 72 of 108 units about 60% of the card's height.
            icon.background?.apply { setBounds(0, (h - w) / 2, w, (h + w) / 2); draw(c) }
            val l = (h * 0.92f).toInt()
            icon.foreground?.apply { setBounds((w - l) / 2, (h - l) / 2, (w + l) / 2, (h + l) / 2); draw(c) }
            return@safe b
        }
        val full = icon?.let { render(it, 192, 192) }
        val edge = full?.let(::edgeColor)
        if (full != null && edge != null) {
            // The icon's rounded corners, outline and any long shadow melt away in a soft fade near its rim.
            val n = full.width.toFloat()
            val soft = Bitmap.createBitmap(full.width, full.height, Bitmap.Config.ARGB_8888)
            val sc = Canvas(soft)
            sc.drawRoundRect(RectF(n * 0.07f, n * 0.07f, n * 0.93f, n * 0.93f), n * 0.24f, n * 0.24f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(n * 0.05f, BlurMaskFilter.Blur.NORMAL) })
            sc.drawBitmap(full, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
            c.drawColor(edge)
            val sz = h * 0.84f
            c.drawBitmap(soft, null, RectF((w - sz) / 2, (h - sz) / 2, (w + sz) / 2, (h + sz) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            soft.recycle(); full.recycle()
            return@safe b
        }
        full?.recycle()
        c.drawColor(accent)
        if (icon != null) {
            val s = (h * 0.58f).toInt()
            icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
            icon.draw(c)
        }
        b
    }

    /**
     * For a filled square icon, the colour along the inside of its edge (clear of rounded corners); null when it
     * isn't opaque there (a circle, a bare glyph) or the edge is more than roughly one colour.
     */
    private fun edgeColor(icon: Bitmap): Int? {
        val n = icon.width
        val inset = n / 12
        val pts = ArrayList<Int>()
        for (i in 0 until 9) {
            val t = n * 3 / 10 + (n * 4 / 10) * i / 8
            pts += icon.getPixel(t, inset); pts += icon.getPixel(t, n - 1 - inset)
            pts += icon.getPixel(inset, t); pts += icon.getPixel(n - 1 - inset, t)
        }
        if (pts.any { Color.alpha(it) < 235 }) return null
        val r = pts.sumOf { Color.red(it) } / pts.size
        val g = pts.sumOf { Color.green(it) } / pts.size
        val bl = pts.sumOf { Color.blue(it) } / pts.size
        val spread = pts.maxOf { maxOf(kotlin.math.abs(Color.red(it) - r), kotlin.math.abs(Color.green(it) - g), kotlin.math.abs(Color.blue(it) - bl)) }
        return if (spread <= 48) Color.rgb(r, g, bl) else null
    }

    private fun artKey(pkg: String, cls: String, size: Int) =
        "v7-$pkg-$cls-$size-$CARD_W".replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun diskFile(dir: File, key: String, version: Long, kind: String) = File(dir, "$key~$version~$kind.png")

    private fun readDisk(dir: File, key: String, version: Long): Art? {
        val t = diskFile(dir, key, version, "t")
        val k = diskFile(dir, key, version, "c")
        if (!t.exists() || !k.exists()) return null
        val tile = safe { BitmapFactory.decodeFile(t.path) } ?: return null
        val card = safe { BitmapFactory.decodeFile(k.path) } ?: return null
        return Art(version, tile, card)
    }

    private fun writeDisk(dir: File, key: String, art: Art) {
        val prefix = "$key~"
        dir.listFiles()?.forEach { if (it.name.startsWith(prefix)) it.delete() }
        fun save(bmp: Bitmap?, kind: String) = bmp?.let {
            safe { FileOutputStream(diskFile(dir, key, art.version, kind)).use { o -> it.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        }
        save(art.tile, "t")
        save(art.card, "c")
    }

    private inline fun <T> safe(block: () -> T?): T? = try { block() } catch (t: Throwable) { null }

    private fun render(d: Drawable, w: Int, h: Int): Bitmap? = safe {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(b))
        b
    }

    /** A deep, saturated tone pulled from the icon, used behind apps that have no brand tile or TV banner. */
    private fun accentOf(icon: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(icon, 16, 16, true)
        val hsv = FloatArray(3)
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        for (x in 0 until 16) for (y in 0 until 16) {
            val p = s.getPixel(x, y)
            val a = Color.alpha(p) / 255.0
            if (a < 0.5) continue
            Color.colorToHSV(p, hsv)
            val wt = a * (0.1 + hsv[1]) * (0.2 + hsv[2])
            r += Color.red(p) * wt; g += Color.green(p) * wt; b += Color.blue(p) * wt; w += wt
        }
        if (s != icon) s.recycle()
        if (w == 0.0) return DEFAULT_ACCENT
        Color.colorToHSV(Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt()), hsv)
        hsv[1] = min(hsv[1], 0.7f)
        hsv[2] = 0.42f
        return Color.HSVToColor(hsv)
    }
}
