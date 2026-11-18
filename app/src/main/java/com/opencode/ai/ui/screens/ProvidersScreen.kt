package com.opencode.ai.ui.screens

import com.opencode.ai.ui.theme.aurora
import com.opencode.ai.ui.theme.entrance
import com.opencode.ai.ui.theme.pressScale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay
import org.json.JSONObject
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opencode.ai.data.ProviderItem
import com.opencode.ai.data.SampleData
import com.opencode.ai.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    onBack: () -> Unit,
    onSettingsClick: () -> Unit = {},
    viewModel: ChatViewModel? = null
) {
    var search by remember { mutableStateOf("") }
    var catalogMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) { viewModel?.refreshOpenCodeModels() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("providers", android.content.Context.MODE_PRIVATE) }
    // id -> saved key (blank = not connected)
    val savedKeys = remember {
        mutableStateMapOf<String, String>().apply {
            SampleData.providers.forEach { put(it.id, prefs.getString("key_${it.id}", "") ?: "") }
            SampleData.providers.filter { it.id in setOf("custom-cloud", "pollinations", "opencode-go", "opencode-zen", "claude", "opencode-server") }.forEach { provider ->
                val hasBase = prefs.getString("base_${provider.id}", "").orEmpty().isNotBlank()
                val configured = if (provider.id == "opencode-server") hasBase else
                    hasBase && prefs.getString("model_${provider.id}", "").orEmpty().isNotBlank() &&
                        prefs.getString("key_${provider.id}", "").orEmpty().isNotBlank()
                if (configured) put(provider.id, "configured")
            }
        }
    }
    viewModel?.openCodeProviders?.forEach { provider ->
        if (provider.id !in savedKeys) savedKeys[provider.id] = ""
    }
    var dialogItem by remember { mutableStateOf<ProviderItem?>(null) }
    var oauthAttempt by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var oauthCode by remember { mutableStateOf("") }
    var oauthStatus by remember { mutableStateOf("pending") }
    var oauthMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(oauthAttempt) {
        val active = oauthAttempt ?: return@LaunchedEffect
        val (providerId, attempt) = active
        if (attempt.optString("mode") == "code") return@LaunchedEffect
        val attemptId = attempt.optString("attemptID")
        if (attemptId.isBlank()) return@LaunchedEffect
        while (true) {
            oauthStatus = "pending"
            viewModel?.checkOpenCodeOAuth(providerId, attemptId) { oauthStatus = it }
            delay(1500)
            when (oauthStatus) {
                "complete" -> { savedKeys[providerId] = "connected"; oauthAttempt = null; oauthMessage = "Вход выполнен"; viewModel?.refreshOpenCodeModels(); return@LaunchedEffect }
                "failed", "expired" -> { oauthAttempt = null; oauthMessage = "Вход не выполнен: $oauthStatus"; return@LaunchedEffect }
            }
        }
    }

    val allProviders = SampleData.providers + (viewModel?.openCodeProviders ?: emptyList())
    val filtered = if (search.isBlank()) allProviders
    else allProviders.filter {
        it.name.contains(search, true) || it.description.contains(search, true) || it.id.contains(search, true)
    }

    val featured = filtered.filter { it.category == "opencode" || it.category == "opencode-server" }
    val liveProviders = filtered.filter { it.category == "opencode-live" }
    val keyProviders = filtered.filter { it.category == "key" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Провайдеры") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "opencode",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 10.dp)
            )

            featured.forEachIndexed { index, item ->
                ProviderCard(
                    item = item,
                    connected = !savedKeys[item.id].isNullOrBlank(),
                    onClick = { dialogItem = item },
                    modifier = Modifier.entrance(index)
                )
                Spacer(Modifier.height(10.dp))
            }

            if (liveProviders.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Провайдеры OpenCode", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text("Загружено с подключённого OpenCode Server. Нажми на провайдера, чтобы добавить API-ключ.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp))
                liveProviders.forEachIndexed { index, item ->
                    ProviderCard(item, !savedKeys[item.id].isNullOrBlank(), onClick = { dialogItem = item },
                        modifier = Modifier.entrance(index))
                    Spacer(Modifier.height(8.dp))
                }
            }
            viewModel?.openCodeCatalogError?.let { error ->
                Text("Каталог OpenCode: $error", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Все провайдеры",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = RoundedCornerShape(32.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                singleLine = true
            )

            Spacer(Modifier.height(14.dp))

            keyProviders.forEach { item ->
                val connected = !savedKeys[item.id].isNullOrBlank()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dialogItem = item }
                        .padding(vertical = 12.dp)
                ) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (connected) "подключён" else "ключ",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            catalogMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Ключ")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(32.dp))
        }
    }

    dialogItem?.let { item ->
        KeyDialog(
            item = item,
            currentKey = prefs.getString("key_${item.id}", "") ?: "",
            custom = item.category == "key" || item.id in setOf("pollinations", "opencode-go", "opencode-zen", "claude", "opencode-server"),
            currentUrl = prefs.getString("base_${item.id}", when (item.id) {
                "opencode-go" -> "https://opencode.ai/zen/go/v1"
                "opencode-zen" -> "https://opencode.ai/zen/v1"
                "claude" -> "https://api.anthropic.com/v1"
                "opencode-server" -> ""
                "pollinations" -> "https://gen.pollinations.ai/v1"
                else -> ""
            }) ?: "",
            currentModel = prefs.getString("model_${item.id}", if (item.id == "opencode-server") "server-default" else if (item.id == "pollinations") "openai" else "") ?: "",
            currentProtocol = prefs.getString("protocol_${item.id}", if (item.id == "claude") "anthropic" else "openai") ?: "openai",
            onSaveCustom = { url, key, model, protocol ->
                prefs.edit().putString("base_${item.id}", url)
                    .putString("key_${item.id}", key)
                    .putString("model_${item.id}", model)
                    .putString("protocol_${item.id}", protocol)
                    .putString("active_provider", item.id).apply()
                savedKeys[item.id] = if (url.isNotBlank() && (item.id == "opencode-server" || (model.isNotBlank() && key.isNotBlank()))) "configured" else ""
                dialogItem = null
                if (item.id == "opencode-server") viewModel?.refreshOpenCodeModels()
            },
            onSave = { key ->
                if (item.category == "opencode-live") {
                    viewModel?.connectOpenCodeProvider(item.id, key) { error ->
                        catalogMessage = error ?: "Ключ провайдера сохранён в OpenCode"
                        if (error == null) savedKeys[item.id] = "connected"
                    }
                } else {
                    savedKeys[item.id] = key
                    prefs.edit().putString("key_${item.id}", key).apply()
                }
                dialogItem = null
            },
            oauthMethods = viewModel?.openCodeOAuthMethods?.get(item.id).orEmpty(),
            onOAuth = { methodId ->
                viewModel?.startOpenCodeOAuth(item.id, methodId) { response, error ->
                    if (error != null) oauthMessage = error
                    else if (response != null) {
                        oauthAttempt = item.id to response
                        oauthCode = ""
                        oauthStatus = "pending"
                        response.optString("url").takeIf { it.startsWith("http") }?.let { url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    }
                }
            },
            onDismiss = { dialogItem = null }
        )
    }

    oauthMessage?.let { message ->
        AlertDialog(onDismissRequest = { oauthMessage = null }, title = { Text("OpenCode OAuth") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { oauthMessage = null }) { Text("OK") } })
    }
    oauthAttempt?.let { (providerId, attempt) ->
        AlertDialog(onDismissRequest = { oauthAttempt = null },
            title = { Text("Вход через браузер") },
            text = {
                Column {
                    val instructions = attempt.optString("instructions")
                    if (instructions.isNotBlank()) Text(instructions, style = MaterialTheme.typography.bodyMedium)
                    val url = attempt.optString("url")
                    if (url.isNotBlank()) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) { Text("Открыть страницу входа") }
                    if (attempt.optString("mode") == "code") {
                        OutlinedTextField(value = oauthCode, onValueChange = { oauthCode = it }, label = { Text("Код со страницы") }, singleLine = true)
                    } else Text("Ожидаю завершения авторизации…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                if (attempt.optString("mode") == "code") TextButton(onClick = {
                    viewModel?.completeOpenCodeOAuth(providerId, attempt.optString("attemptID"), oauthCode) { error ->
                        oauthAttempt = null
                        if (error == null) savedKeys[providerId] = "connected"
                        oauthMessage = error ?: "Вход выполнен"
                    }
                }, enabled = oauthCode.isNotBlank()) { Text("Подтвердить") }
            },
            dismissButton = { TextButton(onClick = { oauthAttempt = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun KeyDialog(
    item: ProviderItem,
    currentKey: String,
    custom: Boolean = false,
    currentUrl: String = "",
    currentModel: String = "",
    currentProtocol: String = "openai",
    onSaveCustom: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onSave: (String) -> Unit,
    oauthMethods: List<Pair<String, String>> = emptyList(),
    onOAuth: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentKey) }
    var url by remember { mutableStateOf(currentUrl) }
    var model by remember { mutableStateOf(currentModel) }
    var protocol by remember { mutableStateOf(currentProtocol) }
    val isClaude = item.id == "claude"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column {
                Text(
                    if (item.id == "opencode-server") "Укажи адрес запущенного OpenCode Server (например, http://192.168.1.10:4096). Пароль — необязательно, если сервер защищён паролем. Бесплатные модели должны быть настроены в самом OpenCode Server; прямой API-доступ к бесплатному тарифу OpenCode запрещён."
                    else if (item.category == "opencode-live") "Ключ будет передан подключённому OpenCode Server и сохранён там для этого провайдера. Сам ключ не сохраняется в настройках YouCode."
                    else if (item.id == "pollinations") "Вставь секретный API-ключ Pollinations (sk_...). Base URL уже заполнен. Модель выбирается автоматически из 12 вариантов в списке YouCode. Запросы используют Pollen-кредиты аккаунта."
                    else if (custom) "Укажи Base URL, точный ID модели и API-ключ провайдера. OpenCode Go/Zen используют API-ключ из консоли OpenCode; Claude — ключ Anthropic API."
                    else if (isClaude) "Вставь токен Claude Code (Pro или Max)."
                    else "Вставь API-ключ провайдера.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (custom) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (item.id == "opencode-server") "Адрес OpenCode Server" else "Домен / Base URL") }, placeholder = { Text(if (item.id == "opencode-server") "http://192.168.1.10:4096" else "https://api.example.com/v1") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (item.id == "opencode-server") "Модель (не используется — выбери на сервере)" else if (item.id == "pollinations") "Модель (автовыбор из списка)" else "Модель") }, placeholder = { Text(if (item.id == "opencode-server") "server-default" else "точный ID модели из каталога провайдера") }, singleLine = true)
                    if (item.id != "opencode-server") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { protocol = "openai" }) { Text(if (protocol == "openai") "✓ OpenAI API" else "OpenAI API") }
                            TextButton(onClick = { protocol = "anthropic" }) { Text(if (protocol == "anthropic") "✓ Claude API" else "Claude API") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(if (item.id == "opencode-server") "Пароль OpenCode Server" else if (isClaude) "Токен" else if (item.id == "pollinations") "Pollinations API key (sk_...)" else "API-ключ провайдера") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (oauthMethods.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Вход через аккаунт", style = MaterialTheme.typography.labelLarge)
                    oauthMethods.forEach { (methodId, label) ->
                        TextButton(onClick = { onOAuth(methodId) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (custom) onSaveCustom(url.trim(), text.trim(), model.trim(), protocol)
                    else onSave(text.trim())
                },
                enabled = if (item.id == "opencode-server") url.isNotBlank() else if (custom) url.isNotBlank() && model.isNotBlank() && text.isNotBlank() else text.isNotBlank()
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (currentKey.isNotBlank() || (custom && currentUrl.isNotBlank())) {
                    TextButton(onClick = { if (custom) onSaveCustom("", "", "", "openai") else onSave("") }) { Text("Отключить") }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )
}

@Composable
private fun ProviderCard(item: ProviderItem, connected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().pressScale(0.97f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(item.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connected) {
                    Spacer(Modifier.height(6.dp))
                    Text("подключён", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
