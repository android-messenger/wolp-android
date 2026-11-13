package org.wolpmessenger.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wolpmessenger.Gateway
import org.wolpmessenger.WolpApp
import org.wolpmessenger.devicetrust.DeviceTrust
import java.io.File

/**
 * The mark "the user signed out" (docs F5). A sign-out without the network cannot tell the server
 * anything: the push token stays bound to the account there, the key directory keeps the record of the
 * erased key, and the server keeps sending. So:
 *
 * - while the mark is there, [WolpMessagingService] drops every push — the next person holding the phone
 *   must not see that the signed-out user is being written to;
 * - the mark also says what is still owed: deleting the FCM token ([tokenPending]) and telling the gateway
 *   ([noticePending]). Both are tried again on every start, on every return to the screen and when the
 *   network comes back ([SignedOut.cleanUp]). Neither needs a session: the token is deleted at Firebase,
 *   the gateway takes a message signed by the device key and has the push service and the key directory
 *   forget this device (F5, part 2).
 *
 * A successful push registration after the next sign-in removes the mark. The file lives in
 * noBackupFilesDir and survives a restart; it holds no user data.
 */
class SignedOutMark(private val file: File) {
    /** The user signed out and has not registered for push since. */
    val signedOut: Boolean get() = file.exists()

    /** The FCM token of the signed-out user is still to be deleted. */
    val tokenPending: Boolean get() = owed().any { it == TOKEN || it == LEGACY_PENDING }

    /** The server did not hear about the sign-out: the gateway is still to be told. */
    val noticePending: Boolean get() = NOTICE in owed()

    /** Something is still owed. */
    val cleanupPending: Boolean get() = tokenPending || noticePending

    /**
     * Sign-out. [tokenDeleted] — the FCM token is already gone; [serverTold] — the push service has
     * dropped this device's token itself, so the gateway need not be asked.
     */
    fun set(tokenDeleted: Boolean, serverTold: Boolean) = write(listOfNotNull(TOKEN.takeUnless { tokenDeleted }, NOTICE.takeUnless { serverTold }))

    /** The FCM token is deleted; the user is still signed out. */
    fun tokenDeleted() = done(TOKEN, LEGACY_PENDING)

    /** The server knows, or there is nobody to tell; the user is still signed out. */
    fun serverTold() = done(NOTICE)

    /** Signed in and registered again: pushes are shown, nothing is left to clean up. */
    fun clear() { file.delete() }

    private fun owed(): List<String> = runCatching { file.readText() }.getOrNull()?.split(' ')?.filter { it.isNotEmpty() && it != CLEAN } ?: emptyList()

    private fun done(vararg what: String) { if (signedOut) write(owed() - what.toSet()) }

    private fun write(owed: List<String>) {
        val state = owed.joinToString(" ").ifEmpty { CLEAN }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(state)
        if (!tmp.renameTo(file)) file.writeText(state)
    }

    private companion object {
        const val TOKEN = "token"
        const val NOTICE = "notice"
        const val CLEAN = "clean"
        const val LEGACY_PENDING = "pending" // a mark written before the gateway notice existed: the token is owed
    }
}

object SignedOut {
    fun mark(context: Context) = SignedOutMark(File(context.noBackupFilesDir, "push-signed-out"))

    /**
     * Does what a sign-out could not: deletes the FCM token and tells the gateway. Runs under the
     * registrar's lock and looks at the mark inside it: a sign-in that registered meanwhile has removed
     * the mark, and its new token and key record stay.
     */
    suspend fun cleanUp(context: Context) = PushRegistrar.exclusive {
        val mark = mark(context)
        if (mark.tokenPending) {
            val deleted = withContext(Dispatchers.IO) { Fcm.deleteToken() }
            if (deleted) mark.tokenDeleted()
            Log.i(WolpApp.TAG, "signout: push token clean-up ${if (deleted) "done" else "still pending"}")
        }
        if (mark.noticePending) {
            // somebody is signed in again and is about to register: that replaces the old records, and a
            // notice now would remove the new ones (F5, Т3)
            if (Gateway.hasSession()) return@exclusive
            val notice = DeviceTrust.reportSignedOut()
            if (notice.done) mark.serverTold()
            Log.i(WolpApp.TAG, "signout: gateway notice ${notice.name.lowercase()}")
        }
    }
}
