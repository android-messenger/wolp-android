package org.wolpmessenger.ui

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

/** Native screens of S1 §3 (texts from the spec). */
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
    Title("Устройство заблокировано")
    Body(
        if (reason == BlockReason.REPLACED) "Доступ перенесён на другое устройство. Это устройство отключено."
        else "Устройство заблокировано по соображениям безопасности. Обратитесь к администратору."
    )
    // hint for admin-side blocks only; none for RASP (S1 §3)
    if (reason == BlockReason.REVOKED || reason == BlockReason.REPLACED || reason == BlockReason.IDENTITY_REVOKED) {
        Body("Чтобы зарегистрировать телефон заново, переустановите приложение или очистите его данные и получите пароль у администратора.")
    }
    Button(onClick = onClose) { Text("Закрыть") }
}

@Composable
fun UnsupportedScreen(reasons: List<String>, onClose: () -> Unit) = Page {
    Title("Устройство не поддерживается")
    reasons.forEach {
        Body(
            when (it) {
                "SOFTWARE_KEYSTORE" -> "Нет аппаратного хранилища ключей."
                "PATCH_TOO_OLD" -> "Слишком старое обновление безопасности Android. Обновите систему."
                else -> it
            }
        )
    }
    Button(onClick = onClose) { Text("Закрыть") }
}

@Composable
fun UpdateScreen(kind: UpdateKind?, onRetry: () -> Unit) = Page {
    Title("Нужно обновление")
    Body(
        if (kind == UpdateKind.OS) "Обновите систему: патч безопасности слишком старый."
        else "Эта версия приложения больше не поддерживается. Установите новую версию."
    )
    OutlinedButton(onClick = onRetry) { Text("Проверить снова") }
}

@Composable
fun OfflineScreen(serverUnverified: Boolean, onRetry: () -> Unit) = Page {
    Title(if (serverUnverified) "Не удалось проверить сервер" else "Нет связи с сервером")
    if (serverUnverified) Body("Соединение не прошло проверку подлинности сервера. Попробуйте позже или в другой сети.")
    Button(onClick = onRetry) { Text("Повторить") }
}

@Composable
fun WebViewOutdatedScreen(onRetry: () -> Unit) = Page {
    Title("Обновите Android System WebView")
    Body("Для работы приложения нужна более новая версия компонента Android System WebView.")
    OutlinedButton(onClick = onRetry) { Text("Проверить снова") }
}

@Composable
fun LoginScreen(onLogin: () -> Unit) = Page {
    Title("Вход")
    Body("Войдите под своей учётной записью мессенджера.")
    Button(onClick = onLogin) { Text("Войти") }
}

/** S1 §3.2. No keyboard suggestions; autofill excluded and FLAG_SECURE set on the window by SplashActivity. */
@Composable
fun OnboardingScreen(reEnroll: Boolean, busy: Boolean, message: String?, onSubmit: (CharArray) -> Unit) = Page {
    var text by remember { mutableStateOf("") }
    Title("Регистрация устройства")
    if (reEnroll) Body("Доступ истёк, нужен новый пароль. Получите его у администратора.")
    Body("Введите пароль онбординга, выданный администратором.")
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Пароль онбординга") },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text.isNotEmpty()) onSubmit(text.toCharArray()) }),
        modifier = Modifier.fillMaxWidth(), // autofill is excluded for the whole window (SplashActivity)
    )
    if (message != null) Body(message)
    if (busy) CircularProgressIndicator()
    else Button(onClick = { onSubmit(text.toCharArray()) }, enabled = text.isNotEmpty()) { Text("Зарегистрировать") }
}
