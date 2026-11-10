package org.wolpmessenger.launch

import android.content.Context
import android.content.SharedPreferences
import org.wolpmessenger.BuildConfig

/**
 * The minimum versionCode the gateway announced last (`X-Device-Min-App-Version` on `ping`, S3 §5.8a;
 * set by `DEVICES_MIN_APP_VERSION_CODE`). Stand-in for the update manifest of S1 §8 until S1-8:
 * an outdated app shows «update» at start and on the next ping instead of waiting for its renewal to
 * be refused. The last value is kept, not the maximum, so lowering the minimum on the server unblocks.
 */
object MinVersion {
    private const val PREFS = "wolp.update"
    private const val KEY = "minVersionCode"
    const val HEADER = "X-Device-Min-App-Version"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(c: Context) {
        prefs = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** A successful ping: no header = the gateway sets no minimum. A malformed value is ignored. */
    fun onPing(header: String?) {
        val v = parse(header) ?: return
        val p = prefs ?: return
        if (p.getLong(KEY, 0) != v) p.edit().putLong(KEY, v).apply()
    }

    fun required(): Long = prefs?.getLong(KEY, 0) ?: 0

    fun outdated(own: Long = BuildConfig.VERSION_CODE.toLong()): Boolean = required() > own

    /** null header → 0; non-numeric or negative → null (keep what we had). */
    internal fun parse(header: String?): Long? {
        val h = header?.trim() ?: return 0
        return h.toLongOrNull()?.takeIf { it >= 0 }
    }
}
