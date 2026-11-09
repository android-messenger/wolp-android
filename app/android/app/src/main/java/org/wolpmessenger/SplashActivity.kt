package org.wolpmessenger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.wolpmessenger.devicetrust.DeviceTrust
import org.wolpmessenger.devicetrust.api.TrustState

/**
 * Launcher. Skeleton (S1-1): waits for the S2 reconciliation and opens the messenger when the
 * device holds a valid certificate; the full S1 §3 order and its screens come with S1-2.
 */
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        setContent {
            val state by DeviceTrust.state.collectAsStateWithLifecycle()
            LaunchedEffect(state) {
                if (state is TrustState.Active || state is TrustState.RenewalDue) {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java).setData(intent.data))
                    finish()
                }
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(state.toString(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
