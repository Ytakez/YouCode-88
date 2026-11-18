package com.opencode.ai.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L

@Composable
fun AuthScreen(onAuthenticated: (AuthSession) -> Unit, auth: AuthManager) {
    var busy by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(76.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("Y", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Spacer(Modifier.height(22.dp))
        Text("YouCode", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (waiting) "Откройте Telegram и подтвердите вход в боте. Потом вернитесь в приложение." else "Войдите через Telegram",
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center); Spacer(Modifier.height(12.dp)) }
        Button(onClick = {
            message = null
            busy = true
            loginJob = scope.launch {
                try {
                    val login = auth.startTelegramLogin()
                    waiting = true
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                    var session: AuthSession? = null
                    while (session == null && System.currentTimeMillis() < deadline) {
                        delay(2000)
                        session = auth.pollTelegramLogin(login.token)
                    }
                    if (session != null) onAuthenticated(session) else message = "Время ожидания вышло. Попробуйте ещё раз."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.message ?: "Не удалось выполнить запрос"
                } finally {
                    busy = false
                    waiting = false
                }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Войти через Telegram")
        }
        if (busy) {
            TextButton(onClick = { loginJob?.cancel(); busy = false; waiting = false }) { Text("Отмена") }
        }
    }
}
