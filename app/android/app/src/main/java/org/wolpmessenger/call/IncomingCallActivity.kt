package org.wolpmessenger.call

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.wolpmessenger.WolpApp
import org.wolpmessenger.secureWindow
import org.wolpmessenger.ui.Page

/**
 * S1 §7.3 full-screen incoming call. The only activity with `showWhenLocked`/`turnScreenOn`
 * (manifest). Locked: «Входящий звонок» without the name (D16), shown after unlocking. Accept on a
 * locked device asks for the unlock first (`requestDismissKeyguard`): the messenger never opens over
 * the lock screen. Closes itself when the ring ends (cancel, timeout, decline from the notification).
 */
class IncomingCallActivity : ComponentActivity() {
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val locked = mutableStateOf(true)
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { locked.value = keyguard.isKeyguardLocked }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        val call = CallState.ringing.value ?: return finish()
        locked.value = keyguard.isKeyguardLocked
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch { CallState.ringing.filter { it?.conversationId != call.conversationId }.first(); finishAndRemoveTask() }

        setContent {
            val isLocked by locked
            val current by CallState.ringing.collectAsState()
            val c = current ?: call
            Page {
                Text(INCOMING, style = MaterialTheme.typography.headlineSmall)
                if (!isLocked && c.callerName != null) Text(c.callerName, style = MaterialTheme.typography.headlineMedium)
                Text(if (c.media == "video") "Видеозвонок" else "Аудиозвонок", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(48.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Button(
                        onClick = ::decline,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    ) { Text("Отклонить") }
                    Button(
                        onClick = { accept() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    ) { Text("Принять") }
                }
            }
        }
        if (intent?.action == ACTION_ACCEPT) accept()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_ACCEPT) accept()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        super.onDestroy()
    }

    private fun decline() {
        RingService.decline(this)
        finishAndRemoveTask()
    }

    private fun accept() {
        val call = CallState.ringing.value ?: return finishAndRemoveTask()
        if (!keyguard.isKeyguardLocked) return openMessenger(call)
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { openMessenger(call) }
            override fun onDismissCancelled() { Log.i(WolpApp.TAG, "call: unlock cancelled, still ringing") }
            override fun onDismissError() { Log.w(WolpApp.TAG, "call: unlock request failed") }
        })
    }

    /** Stops the ring and opens the messenger through the S1 §3 order with the call deep link. */
    private fun openMessenger(call: IncomingCall) {
        RingService.accept(this)
        startActivity(RingService.acceptIntent(this, call))
        finishAndRemoveTask()
    }

    companion object {
        const val ACTION_ACCEPT = "org.wolpmessenger.call.ACCEPT_UI"
        private const val INCOMING = "Входящий звонок"
    }
}
