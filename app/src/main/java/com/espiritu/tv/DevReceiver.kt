package com.espiritu.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * adb-only hooks (the receiver needs android.permission.DUMP, which apps can't hold):
 *   adb shell am broadcast -a com.espiritu.tv.DEMO    sample watch history, to see the banner and Continue watching
 *   adb shell am broadcast -a com.espiritu.tv.CLEAR   forget all watch history
 */
class DevReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "com.espiritu.tv.DEMO" -> History.seedDemo(ctx)
            "com.espiritu.tv.CLEAR" -> History.clear(ctx)
        }
    }
}
