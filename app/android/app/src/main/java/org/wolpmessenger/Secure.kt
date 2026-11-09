package org.wolpmessenger

import android.app.Activity
import android.view.WindowManager

/** S1 §9: FLAG_SECURE on every window in release (no screenshots, blank in recents); off in debug. */
fun Activity.secureWindow() {
    if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
}
