package com.espiritu.tv

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("espiritu", Context.MODE_PRIVATE)

    val dockInitialized: Boolean get() = sp.contains(KEY_DOCK)

    /** Package names in the dock, left to right. */
    var dock: List<String>
        get() = sp.getString(KEY_DOCK, "")!!.split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(KEY_DOCK, v.distinct().joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet(KEY_HIDDEN, HashSet(v)).apply()

    /** Shows saved from the hero as "pkg|showId|name"; they lead that app's hero rotation. */
    var upNext: List<String>
        get() = sp.getString(KEY_UP_NEXT, "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(KEY_UP_NEXT, v.distinct().joinToString("\n")).apply()

    fun upNextKey(pkg: String, show: Show) = "$pkg|${show.id}|${show.name}"

    fun isUpNext(pkg: String, show: Show) = upNext.any { it.startsWith("$pkg|${show.id}|") }

    /** Toggles [show] and returns whether it's now saved. */
    fun toggleUpNext(pkg: String, show: Show): Boolean {
        val prefix = "$pkg|${show.id}|"
        val now = upNext
        return if (now.any { it.startsWith(prefix) }) { upNext = now.filterNot { it.startsWith(prefix) }; false }
        else { upNext = now + upNextKey(pkg, show); true }
    }

    fun upNextIds(pkg: String): List<Int> = upNext.mapNotNull {
        val parts = it.split('|')
        if (parts.size >= 2 && parts[0] == pkg) parts[1].toIntOrNull() else null
    }

    /** Shown as the avatar letter; the brand's own initial until a name is set. */
    var profile: String
        get() = sp.getString(KEY_PROFILE, "")!!.trim().ifEmpty { "Espiritu" }
        set(v) = sp.edit().putString(KEY_PROFILE, v.trim()).apply()

    private companion object {
        const val KEY_DOCK = "dock"
        const val KEY_HIDDEN = "hidden"
        const val KEY_UP_NEXT = "upnext"
        const val KEY_PROFILE = "profile"
    }
}
