package com.opencode.ai.viewmodel

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.ai.data.AiModel
import com.opencode.ai.data.AttachedFile
import com.opencode.ai.data.ChatMessage
import com.opencode.ai.data.ChatSession
import com.opencode.ai.data.SampleData
import com.opencode.ai.engine.Engine
import com.opencode.ai.engine.EngineService
import kotlinx.coroutines.sync.withLock
import com.opencode.ai.data.SmartMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.isActive
import android.util.Base64

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val providerPrefs = application.getSharedPreferences("providers", android.content.Context.MODE_PRIVATE)

    // Stable UI model IDs are routed to real Pollinations catalog aliases.
    // These aliases are supplied by Pollinations; the live catalog may change.
    private val pollinationsModels = mapOf(
        "hy4-flash-free" to "gemini-3.5-flash",
        "iceman-free" to "mistral-4",
        "big-pickle" to "grok",
        "longcat-2.5-free" to "claude-fast",
        "flint-40b-free" to "llama",
        "nemotron-3-ultra-free" to "kimi",
        "deepseek-v4.1-flash-free" to "deepseek",
        "nemotron-3.5-super-vl-free" to "gemini",
        "beam-free" to "perplexity-fast",
        "mimo-v2.6-flash-free" to "minimax",
        "glm-5.3-flash-free" to "glm",
        "nemotron-3.5-lightning-free" to "qwen-coder"
    )

    var currentModel by mutableStateOf(SampleData.freeModels.first())
        private set

    var currentMode by mutableStateOf(SampleData.smartModes.first())
        private set

    private val modeModelIndex = mutableMapOf<String, Int>()
    private val openCodeModels = mutableStateListOf<AiModel>()
    val openCodeProviders = mutableStateListOf<com.opencode.ai.data.ProviderItem>()
    val openCodeOAuthMethods = mutableStateMapOf<String, List<Pair<String, String>>>()
    var openCodeCatalogError by mutableStateOf<String?>(null)
        private set

    // ---- Встроенный OpenCode: бесплатные модели без ключей и без компьютера ----
    private val engine = Engine(application)

    /** "", "starting", "ready", "error", "missing" (нет libopencode.so), "external" (свой сервер). */
    var engineState by mutableStateOf("")
        private set
    var engineError by mutableStateOf<String?>(null)
        private set

    /** true, если движком управляет само приложение (нет внешнего сервера и есть бинарник). */
    private var engineManaged = false
    private val engineLock = kotlinx.coroutines.sync.Mutex()

    private var generationJob: Job? = null
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activeAbort: (() -> Unit)? = null
    private val sessionsFile = File(application.filesDir, "chats.json")
    private val persistLock = kotlinx.coroutines.sync.Mutex()
    private var chatsLoaded = false

    init {
        startEmbeddedEngine()
    }

    /**
     * У каждой установки свой движок: свой процесс, свой пароль и свои данные в закрытой папке приложения.
     * Ничего не общее между людьми, и никакого центрального сервера нет.
     */
    private fun startEmbeddedEngine() {
        if (!engine.available) { engineState = "missing"; return }
        val saved = providerPrefs.getString("base_opencode-server", "").orEmpty()
        // Если пользователь подключил свой внешний сервер, его не трогаем.
        if (saved.isNotBlank() && !saved.contains("127.0.0.1")) { engineState = "external"; return }
        engineManaged = true
        viewModelScope.launch { runEngine() }
    }

    /** Запускает движок, если он не работает (первый старт и перезапуск после того, как система его убила). */
    private suspend fun runEngine() {
        engineLock.withLock {
            if (engine.running) return
            engineState = "starting"
            runCatching { engine.start() }
                .onSuccess {
                    providerPrefs.edit()
                        .putString("base_opencode-server", "http://127.0.0.1:${engine.port}")
                        .putString("key_opencode-server", engine.password)
                        .putString("active_provider", "opencode-server")
                        .apply()
                    engineState = "ready"
                    engineError = null
                    refreshOpenCodeModels()
                }
                .onFailure {
                    engineState = "error"
                    engineError = it.message
                }
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        engine.stop()
        EngineService.stop(getApplication<Application>())
        super.onCleared()
    }

    /** Loads the live model catalogue from OpenCode's /api/model. */
    fun refreshOpenCodeModels() {
        val endpoint = providerPrefs.getString("base_opencode-server", "").orEmpty().trim()
        val password = providerPrefs.getString("key_opencode-server", "").orEmpty().trim()
        if (endpoint.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val root = endpoint.trimEnd('/')
                    require(root.startsWith("https://") || root.startsWith("http://")) {
                        "Адрес OpenCode Server должен начинаться с http:// или https://"
                    }
                    val connection = (URL("$root/api/model").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 30000
                        setRequestProperty("Accept", "application/json")
                        if (password.isNotBlank()) {
                            val raw = "opencode:$password"
                            val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            setRequestProperty("Authorization", "Basic $encoded")
                        }
                    }
                    try {
                        val status = connection.responseCode
                        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        require(status in 200..299) { "OpenCode Server HTTP $status: ${body.take(250)}" }
                        val integrationConnection = (URL("$root/api/integration").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 12000; readTimeout = 30000
                        setRequestProperty("Accept", "application/json")
                        if (password.isNotBlank()) {
                            val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                            setRequestProperty("Authorization", "Basic $encoded")
                        }
                    }
                    val providerRows = try {
                        val status = integrationConnection.responseCode
                        val stream = if (status in 200..299) integrationConnection.inputStream else integrationConnection.errorStream
                        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        require(status in 200..299) { "OpenCode integrations HTTP $status: ${raw.take(200)}" }
                        JSONObject(raw).optJSONArray("data") ?: JSONArray()
                    } finally { integrationConnection.disconnect() }
                    val oauthMethods = mutableMapOf<String, List<Pair<String, String>>>()
                    val providers = buildList {
                        for (i in 0 until providerRows.length()) {
                            val item = providerRows.optJSONObject(i) ?: continue
                            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                            val name = item.optString("name").ifBlank { id }
                            val methods = item.optJSONArray("methods") ?: JSONArray()
                            val connections = item.optJSONArray("connections") ?: JSONArray()
                            val methodNames = buildList {
                                for (j in 0 until methods.length()) {
                                    val method = methods.optJSONObject(j) ?: continue
                                    add(method.optString("label").ifBlank { method.optString("type") })
                                }
                            }
                            oauthMethods[id] = buildList {
                                for (j in 0 until methods.length()) {
                                    val method = methods.optJSONObject(j) ?: continue
                                    if (method.optString("type") == "oauth") {
                                        val methodId = method.optString("id")
                                        if (methodId.isNotBlank()) add(methodId to method.optString("label").ifBlank { "Войти" })
                                    }
                                }
                            }
                            add(com.opencode.ai.data.ProviderItem(id, name,
                                "${methodNames.distinct().joinToString(" · ").ifBlank { "OpenCode provider" }} · подключений: ${connections.length()}",
                                "opencode-live", false))
                        }
                    }
                    openCodeProviders.clear(); openCodeProviders.addAll(providers)
                    openCodeOAuthMethods.clear(); openCodeOAuthMethods.putAll(oauthMethods)
                    val json = JSONObject(body)
                        val rows = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
                        val loaded = buildList {
                            for (i in 0 until rows.length()) {
                                val item = rows.optJSONObject(i) ?: continue
                                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                                val providerId = item.optString("providerID")
                                    .ifBlank { item.optJSONObject("provider")?.optString("id").orEmpty() }
                                if (providerId.isBlank()) continue
                                val providerName = item.optJSONObject("provider")?.optString("name")
                                    ?.takeIf { it.isNotBlank() } ?: providerId
                                val limit = item.optJSONObject("limit")
                                val context = limit?.optLong("context")?.takeIf { it > 0 }?.let {
                                    if (it >= 1000) "${it / 1000}k" else it.toString()
                                } ?: "OpenCode"
                                add(AiModel(
                                    id = id,
                                    name = item.optString("name").ifBlank { id },
                                    contextSize = context,
                                    isFree = providerId == "opencode" && (id.endsWith("-free") || id == "big-pickle"),
                                    provider = providerName,
                                    description = "Провайдер: $providerName",
                                    remoteProviderId = providerId
                                ))
                            }
                        }.distinctBy { "${it.remoteProviderId}/${it.id}" }
                        openCodeModels.clear()
                        openCodeModels.addAll(loaded)
                    } finally { connection.disconnect() }
                }
            }.onFailure {
                openCodeCatalogError = it.message ?: "Не удалось загрузить каталог OpenCode"
                // Existing local catalogue remains usable when server is unavailable.
            }.onSuccess {
                openCodeCatalogError = null
                if (currentModel.remoteProviderId == null &&
                    providerPrefs.getString("active_provider", "") == "opencode-server"
                ) {
                    openCodeModels.firstOrNull { it.isFree }?.let { selectModel(it) }
                }
            }
        }
    }

    /** Stores a provider credential in the connected OpenCode server. */
    fun connectOpenCodeProvider(integrationId: String, apiKey: String, onResult: (String?) -> Unit = {}) {
        val endpoint = providerPrefs.getString("base_opencode-server", "").orEmpty().trim().trimEnd('/')
        val password = providerPrefs.getString("key_opencode-server", "").orEmpty().trim()
        viewModelScope.launch {
            val error = runCatching { withContext(Dispatchers.IO) {
                require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) { "Сначала подключи OpenCode Server" }
                val connection = (URL("$endpoint/api/integration/${java.net.URLEncoder.encode(integrationId, "UTF-8")}/connect/key").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 30000; doOutput = true
                    setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
                    if (password.isNotBlank()) {
                        val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        setRequestProperty("Authorization", "Basic $encoded")
                    }
                }
                try {
                    connection.outputStream.use { it.write(JSONObject().put("key", apiKey.trim()).toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    require(status in 200..299) { "HTTP $status: ${raw.take(300)}" }
                } finally { connection.disconnect() }
            } }.exceptionOrNull()?.message
            if (error == null) refreshOpenCodeModels()
            onResult(error)
        }
    }

    /** Starts the OpenCode OAuth flow for a provider. */
    fun startOpenCodeOAuth(integrationId: String, methodId: String, onResult: (JSONObject?, String?) -> Unit) {
        openCodeRequest("POST", "/api/integration/${enc(integrationId)}/connect/oauth", JSONObject().put("methodID", methodId)) { json, error ->
            onResult(json?.optJSONObject("data") ?: json, error)
        }
    }

    fun checkOpenCodeOAuth(integrationId: String, attemptId: String, onResult: (String) -> Unit) {
        openCodeRequest("GET", "/api/integration/${enc(integrationId)}/connect/oauth/${enc(attemptId)}") { json, error ->
            onResult(if (error == null) json?.optJSONObject("data")?.optString("status", "pending") ?: "pending" else "pending")
        }
    }

    fun completeOpenCodeOAuth(integrationId: String, attemptId: String, code: String, onResult: (String?) -> Unit) {
        openCodeRequest("POST", "/api/integration/${enc(integrationId)}/connect/oauth/${enc(attemptId)}/complete", JSONObject().put("code", code.trim())) { _, error ->
            if (error == null) refreshOpenCodeModels()
            onResult(error)
        }
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun openCodeRequest(method: String, path: String, body: JSONObject? = null, onResult: (JSONObject?, String?) -> Unit) {
        val endpoint = providerPrefs.getString("base_opencode-server", "").orEmpty().trim().trimEnd('/')
        val password = providerPrefs.getString("key_opencode-server", "").orEmpty().trim()
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) { "Сначала подключи OpenCode Server" }
                val c = (URL(endpoint + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method; connectTimeout = 12000; readTimeout = 30000
                    setRequestProperty("Accept", "application/json")
                    if (password.isNotBlank()) setRequestProperty("Authorization", "Basic " + Base64.encodeToString("opencode:$password".toByteArray(), Base64.NO_WRAP))
                    if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
                }
                try {
                    if (body != null) c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val status = c.responseCode
                    val raw = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    require(status in 200..299) { "OpenCode HTTP $status: ${raw.take(250)}" }
                    if (raw.isBlank()) JSONObject() else JSONObject(raw)
                } finally { c.disconnect() }
            } }
            onResult(result.getOrNull(), result.exceptionOrNull()?.message)
        }
    }

    var messages = mutableStateListOf<ChatMessage>()
        private set

    var isGenerating by mutableStateOf(false)
        private set

    var generationStatus by mutableStateOf("Подготавливаю запрос")
        private set

    /** Реальные события инструментов OpenCode (не скрытые рассуждения модели). */
    val generationActivities = mutableStateListOf<String>()

    var searchQuery by mutableStateOf("")
        private set

    var sessions = mutableStateListOf<ChatSession>()
        private set

    private var currentSessionId: String? = null

    init { loadSessions() }

    // Pending attachments before send
    var pendingAttachments = mutableStateListOf<AttachedFile>()
        private set

    var workingFolderUri by mutableStateOf<android.net.Uri?>(null)
        private set
    var workingFolderName by mutableStateOf<String?>(null)
        private set
    var workingFolderFiles = mutableStateListOf<String>()
        private set

    val filteredModels: List<AiModel>
        get() {
            val all = SampleData.freeModels + openCodeModels
            return if (searchQuery.isBlank()) all else all.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.provider.contains(searchQuery, ignoreCase = true) ||
                    it.id.contains(searchQuery, ignoreCase = true)
            }
        }

    fun updateSearch(query: String) {
        searchQuery = query
    }

    fun selectMode(mode: SmartMode) {
        currentMode = mode
    }

    fun selectModel(model: AiModel) {
        currentModel = model
        when {
            model.remoteProviderId != null -> providerPrefs.edit().putString("active_provider", "opencode-server").apply()
            model.provider == "Pollinations" -> providerPrefs.edit().putString("active_provider", "pollinations").apply()
        }
    }

    fun addAttachment(file: AttachedFile) {
        if (pendingAttachments.none { it.id == file.id }) {
            pendingAttachments.add(file)
        }
    }

    fun removePendingAttachment(id: String) {
        pendingAttachments.removeAll { it.id == id }
    }

    fun clearPendingAttachments() {
        pendingAttachments.clear()
    }

    fun setWorkingFolder(uri: android.net.Uri?, name: String?, files: List<String> = emptyList()) {
        workingFolderUri = uri
        workingFolderName = name
        workingFolderFiles.clear()
        workingFolderFiles.addAll(files)
    }

    fun clearWorkingFolder() {
        workingFolderUri = null
        workingFolderName = null
        workingFolderFiles.clear()
    }

    fun sendMessage(text: String) {
        if ((text.isBlank() && pendingAttachments.isEmpty()) || isGenerating) return

        val attachmentsCopy = pendingAttachments.toList()
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text.trim().ifBlank { "📎 Файл(ы) прикреплены" },
            isUser = true,
            attachments = attachmentsCopy
        )
        messages.add(userMsg)
        pendingAttachments.clear()

        if (currentSessionId == null) {
            val title = text.trim().ifBlank { "Новый чат" }.lines().first().take(30)
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                title = title,
                lastMessage = text.trim(),
                timestamp = System.currentTimeMillis(),
                messages = messages.toList()
            )
            sessions.add(0, session)
            currentSessionId = session.id
        }
        val sessionId = currentSessionId
        saveSession(sessionId)
        persistSessions()

        isGenerating = true
        generationActivities.clear()
        generationStatus = "Подключаюсь к модели"
        EngineService.start(getApplication<Application>())
        generationJob = viewModelScope.launch {
            // Если система убила встроенный движок в фоне, поднимаем его заново.
            if (engineManaged && !engine.running) runEngine()
            val providerId = providerPrefs.getString("active_provider", "").orEmpty()
            val endpoint = providerPrefs.getString("base_${providerId}", "").orEmpty().trim()
            val apiKey = providerPrefs.getString("key_${providerId}", "").orEmpty().trim()
            val remoteModel = providerPrefs.getString("model_${providerId}", "").orEmpty().trim()
            val requestModel = if (providerId == "pollinations") pollinationsModels[currentModel.id].orEmpty() else remoteModel
            val protocol = providerPrefs.getString("protocol_${providerId}", "openai").orEmpty()
            generationStatus = if (providerId == "opencode-server") "Подключаюсь к OpenCode" else "Отправляю запрос провайдеру"
            val reply = if (providerId == "opencode-server" && endpoint.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val history = messages.dropLast(1).takeLast(8).joinToString("\n") {
                            (if (it.isUser) "Пользователь: " else "Ассистент: ") + it.content
                        }
                        val fullPrompt = buildString {
                            append("Системные инструкции: ").append(currentMode.systemHint)
                            if (history.isNotBlank()) append("\nИстория диалога:\n").append(history)
                            append("\nПользователь: ").append(text.trim())
                            attachmentsCopy.forEach { f -> append("\n\n[Файл: ").append(f.name).append("]\n").append(f.contentPreview.take(12000)) }
                        }
                        callOpenCodeServer(endpoint, apiKey, currentModel.id, fullPrompt, currentModel.remoteProviderId) { stage ->
                            viewModelScope.launch(Dispatchers.Main) { generationStatus = stage }
                        }
                    }
                }.fold(
                    onSuccess = { it to emptyList<AttachedFile>() },
                    onFailure = {
                        val reason = it.message ?: "не удалось выполнить запрос"
                        val isRealRateLimit = reason.contains("HTTP 429", ignoreCase = true) ||
                            reason.contains("rate limit", ignoreCase = true) ||
                            reason.contains("too many requests", ignoreCase = true) ||
                            reason.contains("quota exceeded", ignoreCase = true)
                        if (isRealRateLimit) {
                            "Провайдер действительно отклонил запрос из-за ограничения частоты или квоты. Подробности: $reason" to emptyList()
                        } else {
                            "Ошибка OpenCode (это не обязательно лимит): $reason" to emptyList()
                        }
                    }
                )
            } else if (endpoint.isNotBlank() && requestModel.isNotBlank() && apiKey.isNotBlank()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (protocol == "anthropic") callAnthropic(endpoint, apiKey, requestModel, text, attachmentsCopy, currentMode.systemHint)
                        else callOpenAiCompatible(endpoint, apiKey, requestModel, text, attachmentsCopy, currentMode.systemHint)
                    }
                }.fold(
                    onSuccess = { it to emptyList<AttachedFile>() },
                    onFailure = { "Ошибка облачного ИИ: ${it.message ?: "не удалось выполнить запрос"}. Проверь Base URL, API-ключ, доступность модели и баланс Pollen." to emptyList() }
                )
            } else {
                ("ИИ не подключён. Открой «Провайдеры» и подключи OpenCode Server или укажи Base URL, API-ключ и ID модели для другого провайдера. Демонстрационные ответы отключены.") to emptyList()
            }
            val shownModel = if (providerId == "pollinations" || providerId == "opencode-server") currentModel.name else if (remoteModel.isNotBlank()) remoteModel else currentModel.name
            if (!isActive) {
                // Пользователь нажал «стоп»: текст ошибки из оборванного запроса не показываем.
                deliverReply(sessionId, ChatMessage(
                    id = UUID.randomUUID().toString(), content = "Генерация остановлена.", isUser = false, modelName = shownModel
                ))
                return@launch
            }
            generationStatus = "Получен ответ · оформляю результат"
            deliverReply(sessionId, ChatMessage(
                id = UUID.randomUUID().toString(), content = reply.first, isUser = false,
                modelName = shownModel, attachments = reply.second
            ))
        }
        generationJob?.invokeOnCompletion {
            // Выполняется при любом исходе (успех, ошибка, отмена): флаг и уведомление не зависают.
            isGenerating = false
            generationStatus = "Готово"
            EngineService.stop(getApplication<Application>())
        }
    }

    /** Останавливает текущую генерацию: рвёт соединение и просит OpenCode прервать сессию. */
    fun stopGeneration() {
        if (!isGenerating) return
        generationJob?.cancel()
        val connection = activeConnection
        val abort = activeAbort
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { abort?.invoke() }
            runCatching { connection?.disconnect() }
        }
    }

    /** Кладёт ответ в тот чат, из которого был запрос, даже если пользователь успел открыть другой. */
    private fun deliverReply(sessionId: String?, msg: ChatMessage) {
        if (sessionId == null || sessionId == currentSessionId) {
            messages.add(msg)
            saveSession(sessionId)
        } else {
            val idx = sessions.indexOfFirst { it.id == sessionId }
            if (idx >= 0) {
                val s = sessions[idx]
                sessions[idx] = s.copy(lastMessage = msg.content, messages = s.messages + msg)
            }
        }
        persistSessions()
    }

    /** Выход из аккаунта: стираем локальные чаты и настройки провайдеров, чтобы их не увидел следующий пользователь. */
    fun wipeLocalData() {
        generationJob?.cancel()
        chatsLoaded = false
        sessions.clear()
        messages.clear()
        currentSessionId = null
        runCatching { sessionsFile.delete() }
        providerPrefs.edit().clear().apply()
    }

    // ---- Хранение чатов на диске ----

    private fun messageToJson(m: ChatMessage): JSONObject = JSONObject()
        .put("id", m.id).put("content", m.content).put("isUser", m.isUser).put("ts", m.timestamp)
        .put("model", m.modelName ?: JSONObject.NULL)
        .put("attachments", JSONArray().also { arr ->
            m.attachments.forEach { a ->
                arr.put(JSONObject().put("id", a.id).put("name", a.name).put("uri", a.uri?.toString() ?: "")
                    .put("mime", a.mimeType).put("size", a.sizeBytes).put("preview", a.contentPreview).put("saved", a.isSavedByAi))
            }
        })

    private fun messageFromJson(o: JSONObject): ChatMessage {
        val atts = o.optJSONArray("attachments") ?: JSONArray()
        return ChatMessage(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            content = o.optString("content"),
            isUser = o.optBoolean("isUser"),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
            modelName = if (o.isNull("model")) null else o.optString("model"),
            attachments = (0 until atts.length()).mapNotNull { i ->
                atts.optJSONObject(i)?.let { a ->
                    AttachedFile(
                        id = a.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = a.optString("name"),
                        uri = a.optString("uri").takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
                        mimeType = a.optString("mime", "text/plain"),
                        sizeBytes = a.optLong("size"),
                        contentPreview = a.optString("preview"),
                        isSavedByAi = a.optBoolean("saved")
                    )
                }
            }
        )
    }

    private fun persistSessions() {
        if (!chatsLoaded) return
        val snapshot = sessions.toList().take(100)
        viewModelScope.launch(Dispatchers.IO) {
            persistLock.withLock {
                runCatching {
                    val arr = JSONArray()
                    snapshot.forEach { s ->
                        arr.put(JSONObject().put("id", s.id).put("title", s.title).put("last", s.lastMessage).put("ts", s.timestamp)
                            .put("messages", JSONArray().also { ma -> s.messages.forEach { ma.put(messageToJson(it)) } }))
                    }
                    val tmp = File(sessionsFile.path + ".tmp")
                    tmp.writeText(arr.toString())
                    if (!tmp.renameTo(sessionsFile)) { sessionsFile.delete(); tmp.renameTo(sessionsFile) }
                }
            }
        }
    }

    private fun loadSessions() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!sessionsFile.exists()) emptyList<ChatSession>() else {
                        val arr = JSONArray(sessionsFile.readText())
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            val ms = o.optJSONArray("messages") ?: JSONArray()
                            ChatSession(
                                id = o.optString("id").ifBlank { return@mapNotNull null },
                                title = o.optString("title"),
                                lastMessage = o.optString("last"),
                                timestamp = o.optLong("ts"),
                                messages = (0 until ms.length()).mapNotNull { j -> ms.optJSONObject(j)?.let { messageFromJson(it) } }
                            )
                        }
                    }
                }.getOrDefault(emptyList<ChatSession>())
            }
            val known = sessions.map { it.id }.toSet()
            sessions.addAll(loaded.filter { it.id !in known })
            chatsLoaded = true
            if (sessions.isNotEmpty()) persistSessions()
        }
    }

    fun newChat() {
        messages.clear()
        pendingAttachments.clear()
        currentSessionId = null
    }

    fun loadSession(session: ChatSession) {
        messages.clear()
        pendingAttachments.clear()
        currentSessionId = session.id
        messages.addAll(session.messages)
    }

    private fun saveSession(sessionId: String?) {
        if (sessionId == null) return
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) return
        sessions[idx] = sessions[idx].copy(
            lastMessage = messages.lastOrNull()?.content ?: sessions[idx].lastMessage,
            messages = messages.toList()
        )
    }


    /**
     * Routes free OpenCode models through the user's own official OpenCode Server.
     * Direct calls to OpenCode's free-tier inference endpoints from third-party apps
     * are rejected by OpenCode; the official server is the supported bridge.
     */
    private fun callOpenCodeServer(baseUrl: String, password: String, modelId: String, prompt: String, selectedProviderId: String? = null, onStage: (String) -> Unit = {}): String {
        val root = baseUrl.trim().trimEnd('/')
        require(root.startsWith("https://") || root.startsWith("http://")) {
            "Адрес сервера должен начинаться с http:// или https://"
        }

        fun request(path: String, body: JSONObject? = null, method: String? = null, readTimeoutMs: Int = 180000): JSONObject {
            val connection = (URL("$root/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method ?: if (body == null) "GET" else "POST"
                connectTimeout = 15000
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                if (password.isNotBlank()) {
                    val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $encoded")
                }
            }
            activeConnection = connection
            try {
                if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = runCatching {
                        val json = JSONObject(raw)
                        json.optString("message").ifBlank {
                            json.optJSONObject("error")?.optString("message").orEmpty()
                        }.ifBlank { json.optString("name") }
                    }.getOrNull().orEmpty()
                    throw IllegalStateException("HTTP $status${if (detail.isNotBlank()) ": $detail" else ": ${raw.take(600)}"}")
                }
                return if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection.disconnect()
            }
        }

        // Resolve providerID against the live catalogue: model IDs are not globally unique.
        onStage("Проверяю каталог моделей")
        val catalog = request("api/model")
        val models = catalog.optJSONArray("data") ?: catalog.optJSONArray("models") ?: JSONArray()
        var providerId = selectedProviderId?.takeIf { it.isNotBlank() }
        if (providerId == null) {
            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue
                if (item.optString("id") == modelId) {
                    providerId = item.optString("providerID").takeIf { it.isNotBlank() }
                        ?: item.optJSONObject("provider")?.optString("id")?.takeIf { it.isNotBlank() }
                    break
                }
            }
        }
        require(!providerId.isNullOrBlank()) {
            "Модель $modelId не найдена в каталоге OpenCode. Обнови список моделей и выбери доступную модель."
        }

        // Use the documented session/message API instead of the legacy experimental generate/text route.
        onStage("Создаю сессию")
        val session = request("api/session", JSONObject().put("title", "YouCode Android"))
        val sessionId = session.optString("id").ifBlank {
            session.optJSONObject("data")?.optString("id").orEmpty()
        }
        require(sessionId.isNotBlank()) { "OpenCode создал сессию без ID: ${session.toString().take(400)}" }

        // Subscribe to OpenCode's real event stream before sending the prompt.
        // This surfaces actual tool calls (shell/read/edit/etc.), not fabricated "thinking" text.
        activeAbort = { runCatching { request("api/session/${enc(sessionId)}/abort", JSONObject(), readTimeoutMs = 10000) } }
        val eventConnection = AtomicReference<HttpURLConnection?>(null)
        val eventJob = listenOpenCodeEvents(root, password, sessionId, eventConnection)
        try {
        val messageBody = JSONObject()
            .put("model", JSONObject().put("providerID", providerId).put("id", modelId))
            .put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
        onStage("Модель обрабатывает запрос")
        // Агентные задачи могут идти долго: ждём ответ до 30 минут, а не 3.
        val response = request("api/session/${enc(sessionId)}/message", messageBody, readTimeoutMs = 30 * 60 * 1000)
        onStage("Получаю результат модели")
        val parts = response.optJSONArray("parts")
            ?: response.optJSONObject("data")?.optJSONArray("parts")
            ?: JSONArray()
        val answer = buildList {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    part.optString("text").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.joinToString("\n").trim()
        return answer.ifBlank {
            throw IllegalStateException("OpenCode не вернул текст ответа. Ответ API: ${response.toString().take(600)}")
        }
        } finally {
            eventJob.cancel()
            runCatching { eventConnection.get()?.disconnect() }
            activeAbort = null
            // Каждый запрос создаёт отдельную сессию OpenCode: удаляем её, чтобы они не копились в базе.
            runCatching { request("api/session/${enc(sessionId)}", method = "DELETE", readTimeoutMs = 15000) }
        }
    }

    private fun listenOpenCodeEvents(root: String, password: String, sessionId: String, connectionRef: AtomicReference<HttpURLConnection?>): Job = viewModelScope.launch(Dispatchers.IO) {
        val connection = (URL("$root/event").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 120000
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            if (password.isNotBlank()) {
                val encoded = Base64.encodeToString("opencode:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $encoded")
            }
        }
        connectionRef.set(connection)
        if (!isActive) { connection.disconnect(); return@launch }
        try {
            if (connection.responseCode !in 200..299) return@launch
            val listenerJob = currentCoroutineContext()[Job]
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                var data = StringBuilder()
                lines.forEach { line ->
                    if (listenerJob?.isActive != true) return@useLines
                    if (line.startsWith("data:")) data.append(line.removePrefix("data:").trim()).append(' ')
                    if (line.isBlank() && data.isNotEmpty()) {
                        val raw = data.toString().trim(); data = StringBuilder()
                        runCatching {
                            val envelope = JSONObject(raw)
                            val event = envelope.optJSONObject("payload") ?: envelope
                            val type = event.optString("type")
                            val props = event.optJSONObject("properties") ?: JSONObject()
                            val part = props.optJSONObject("part")
                            val eventSession = props.optString("sessionID")
                                .ifBlank { props.optString("sessionId") }
                                .ifBlank { part?.optString("sessionID").orEmpty() }
                            if (eventSession.isNotBlank() && eventSession != sessionId) return@runCatching
                            if (type.contains("part.updated") && part?.optString("type") == "tool") {
                                val tool = part.optString("tool").ifBlank { "Инструмент" }
                                val state = part.optJSONObject("state") ?: JSONObject()
                                val status = state.optString("status")
                                val title = state.optString("title").ifBlank { state.optString("description") }
                                val input = state.optJSONObject("input")
                                val command = input?.optString("command")?.takeIf { it.isNotBlank() }
                                val label = buildString {
                                    append(tool)
                                    if (status.isNotBlank()) append(" · ").append(when(status) { "running" -> "выполняется"; "completed" -> "завершено"; "error" -> "ошибка"; else -> status })
                                    (title.takeIf { it.isNotBlank() } ?: command)?.let { append(" · ").append(it.take(180)) }
                                }
                                viewModelScope.launch(Dispatchers.Main) {
                                    if (generationActivities.lastOrNull() != label) generationActivities.add(label)
                                    if (generationActivities.size > 30) generationActivities.removeAt(0)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Event streaming is optional; the ordinary message response remains authoritative.
        } finally { connection.disconnect() }
    }

    private fun callAnthropic(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<AttachedFile>,
        systemHint: String
    ): String {
        require(apiKey.isNotBlank()) { "Для Claude API нужен API-ключ Anthropic" }
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "Адрес должен начинаться с https:// или http://"
        }
        val endpoint = when {
            normalized.endsWith("/v1/messages") -> normalized
            normalized.endsWith("/v1") -> "$normalized/messages"
            else -> "$normalized/v1/messages"
        }
        val userContent = buildString {
            append(prompt)
            attachments.forEach { file ->
                append("\n\n[Файл: ${file.name}]\n")
                append(file.contentPreview.takeIf { it.isNotBlank() }?.take(20000) ?: "Содержимое файла недоступно для текстового API.")
            }
            if (workingFolderName != null) append("\n\nРабочая папка: $workingFolderName. Файлы: ${workingFolderFiles.take(30).joinToString()}")
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", systemHint)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userContent)))
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $status${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ": ${response.take(500)}"}")
            }
            val blocks = JSONObject(response).optJSONArray("content") ?: JSONArray()
            val answer = (0 until blocks.length()).mapNotNull { i ->
                blocks.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
            }.filter { it.isNotBlank() }.joinToString("\n")
            return answer.ifBlank { throw IllegalStateException("Claude API вернул пустой ответ") }
        } finally {
            connection.disconnect()
        }
    }

    private fun callOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<AttachedFile>,
        systemHint: String
    ): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
            "Адрес должен начинаться с https:// или http://"
        }
        val endpoint = when {
            normalized.endsWith("/chat/completions") -> normalized
            normalized.endsWith("/v1") -> "$normalized/chat/completions"
            else -> "$normalized/v1/chat/completions"
        }
        val userContent = buildString {
            append(prompt)
            attachments.forEach { file ->
                append("\n\n[Файл: ${file.name}]\n")
                if (file.contentPreview.isNotBlank()) append(file.contentPreview.take(20000))
                else append("Содержимое файла недоступно для текстового API.")
            }
            if (workingFolderName != null) {
                append("\n\nРабочая папка: $workingFolderName. Файлы: ${workingFolderFiles.take(30).joinToString()}")
            }
        }
        val messagesJson = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemHint))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject().put("model", model).put("messages", messagesJson).put("stream", false)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "YouCode-Android/1.0")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP $status${detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ": ${response.take(500)}"}")
            }
            val json = JSONObject(response)
            val content = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.opt("content")
            val answer = when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { i ->
                    content.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString("\n")
                else -> ""
            }
            return answer.ifBlank { throw IllegalStateException("Провайдер вернул пустой ответ") }
        } finally {
            connection.disconnect()
        }
    }

}
