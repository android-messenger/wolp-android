package org.wolpmessenger.ui

import org.wolpmessenger.R
import androidx.compose.ui.res.stringResource
import org.wolpmessenger.BuildConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.wolpmessenger.devicetrust.api.BlockReason
import org.wolpmessenger.devicetrust.api.UpdateKind

/** Native screens of S1 §3 (texts: res/values(-es)/strings.xml, language of the web UI — S1 §5.5). */
@Composable
fun Page(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
fun WaitScreen(text: String? = null) = Page {
    CircularProgressIndicator()
    if (text != null) Body(text)
}

@Composable
fun BlockedScreen(reason: BlockReason?, onClose: () -> Unit) = Page {
    Title(stringResource(R.string.blocked_title))
    Body(
        if (reason == BlockReason.REPLACED) stringResource(R.string.blocked_replaced)
        else stringResource(R.string.blocked_security)
    )
    // hint for admin-side blocks only; none for RASP (S1 §3)
    if (reason == BlockReason.REVOKED || reason == BlockReason.REPLACED || reason == BlockReason.IDENTITY_REVOKED) {
        Body(stringResource(R.string.blocked_reenroll_hint))
    }
    Button(onClick = onClose) { Text(stringResource(R.string.action_close)) }
}

@Composable
fun UnsupportedScreen(reasons: List<String>, onClose: () -> Unit) = Page {
    Title(stringResource(R.string.unsupported_title))
    reasons.forEach {
        Body(
            when (it) {
                "SOFTWARE_KEYSTORE" -> stringResource(R.string.unsupported_software_keystore)
                "PATCH_TOO_OLD" -> stringResource(R.string.unsupported_patch_too_old)
                else -> it
            }
        )
    }
    Button(onClick = onClose) { Text(stringResource(R.string.action_close)) }
}

@Composable
fun UpdateScreen(kind: UpdateKind?, onRetry: () -> Unit) = Page {
    Title(stringResource(R.string.update_title))
    Body(
        if (kind == UpdateKind.OS) stringResource(R.string.update_os)
        else stringResource(R.string.update_app, BuildConfig.VERSION_NAME)
    )
    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_check_again)) }
}

@Composable
fun OfflineScreen(serverUnverified: Boolean, onRetry: () -> Unit) = Page {
    Title(if (serverUnverified) stringResource(R.string.offline_unverified_title) else stringResource(R.string.offline_title))
    if (serverUnverified) Body(stringResource(R.string.offline_unverified_body))
    Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
}

/** S1 §9 «Смена пользователя на устройстве»: no unenroll offered (D15). */
@Composable
fun WrongUserScreen(onLogin: () -> Unit, onClose: () -> Unit) = Page {
    Title(stringResource(R.string.wrong_user_title))
    Body(stringResource(R.string.wrong_user_body))
    Button(onClick = onLogin) { Text(stringResource(R.string.action_login_again)) }
    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
}

@Composable
fun WebViewOutdatedScreen(onRetry: () -> Unit) = Page {
    Title(stringResource(R.string.webview_title))
    Body(stringResource(R.string.webview_body))
    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_check_again)) }
}

@Composable
fun LoginScreen(onLogin: () -> Unit) = Page {
    Title(stringResource(R.string.login_title))
    Body(stringResource(R.string.login_body))
    Button(onClick = onLogin) { Text(stringResource(R.string.action_login)) }
}

/** S1 §3.2. No keyboard suggestions; autofill excluded and FLAG_SECURE set on the window by SplashActivity. */
@Composable
fun OnboardingScreen(reEnroll: Boolean, busy: Boolean, message: String?, onSubmit: (CharArray) -> Unit) = Page {
    var text by remember { mutableStateOf("") }
    Title(stringResource(R.string.onboarding_title))
    if (reEnroll) Body(stringResource(R.string.onboarding_expired))
    Body(stringResource(R.string.onboarding_body))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.onboarding_label)) },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isNotEmpty()) onSubmit(text.toCharArray()) }),
        modifier = Modifier.fillMaxWidth(), // autofill is excluded for the whole window (SplashActivity)
    )
    if (message != null) Body(message)
    if (busy) CircularProgressIndicator()
    else Button(onClick = { onSubmit(text.toCharArray()) }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.action_register)) }
}
