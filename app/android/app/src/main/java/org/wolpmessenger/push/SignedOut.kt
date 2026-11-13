package org.wolpmessenger.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wolpmessenger.WolpApp
import java.io.File

/**
 * The mark "the user signed out" (docs F5, part 1). A sign-out without the network cannot tell the server
 * anything: the push token stays bound to the account there, and the server keeps sending. So:
 *
 * - while the mark is there, [WolpMessagingService] drops every push — the next person holding the phone
 *   must not see that the signed-out user is being written to;
 * - the mark also says whether the FCM token is still to be deleted ([cleanupPending]); that is tried again
 *   on every start, on every return to the screen and when the network comes back ([SignedOut.cleanUp]).
 *   It needs no session. Once the token is gone, the server drops its record on the next send.
 *
 * A successful push registration after the next sign-in removes the mark. The file lives in
 * noBackupFilesDir and survives a restart; it holds no user data.
 */
class SignedOutMark(private val file: File) {
    /** The user signed out and has not registered for push since. */
    val signedOut: Boolean get() = file.exists()

    /** The FCM token of the signed-out user is still to be deleted. */
    val cleanupPending: Boolean get() = runCatching { file.readText() }.getOrNull() == PENDING

    /** Sign-out. [tokenDeleted] — the FCM token is already gone, nothing is left to clean up. */
    fun set(tokenDeleted: Boolean) = write(if (tokenDeleted) CLEAN else PENDING)

    /** The pending clean-up is done; the user is still signed out. */
    fun cleaned() { if (signedOut) write(CLEAN) }

    /** Signed in and registered again: pushes are shown, nothing is left to clean up. */
    fun clear() { file.delete() }

    private fun write(state: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(state)
        if (!tmp.renameTo(file)) file.writeText(state)
    }

    private companion object {
        const val PENDING = "pending"
        const val CLEAN = "clean"
    }
}

object SignedOut {
    fun mark(context: Context) = SignedOutMark(File(context.noBackupFilesDir, "push-signed-out"))

    /**
     * Deletes the FCM token a sign-out could not delete. Runs under the registrar's lock and looks at the
     * mark inside it: a sign-in that registered meanwhile has removed the mark, and its new token stays.
     */
    suspend fun cleanUp(context: Context) = PushRegistrar.exclusive {
        val mark = mark(context)
        if (!mark.cleanupPending) return@exclusive
        val deleted = withContext(Dispatchers.IO) { Fcm.deleteToken() }
        if (deleted) mark.cleaned()
        Log.i(WolpApp.TAG, "signout: push token clean-up ${if (deleted) "done" else "still pending"}")
    }
}
