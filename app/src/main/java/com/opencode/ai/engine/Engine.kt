package com.opencode.ai.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.Base64

/**
 * Встроенный OpenCode: приложение запускает `opencode serve` на localhost
 * и общается с ним по обычному HTTP API.
 *
 * Бинарник (engine/build-engine.sh) лежит в jniLibs под именем libopencode.so:
 * только из папки библиотек Android позволяет запускать файлы.
 */
class Engine(private val context: Context) {

    private var process: Process? = null

    /** Порт выбирается свободный при каждом запуске. */
    var port: Int = 0
        private set

    /** Случайный пароль на сессию; снаружи приложения сервер недоступен. */
    val password: String = run {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private val binary: File get() = File(context.applicationInfo.nativeLibraryDir, "libopencode.so")
    private val dirs = EngineDirs(context)

    val available: Boolean get() = binary.exists()
    val running: Boolean get() = process?.isAlive == true

    suspend fun start() = withContext(Dispatchers.IO) {
        if (running) return@withContext
        check(available) { "opencode не найден в приложении: $binary" }

        dirs.prepare()
        port = ServerSocket(0).use { it.localPort }

        val builder = ProcessBuilder(
            binary.absolutePath, "serve",
            "--hostname", "127.0.0.1",
            "--port", port.toString()
        )
            .directory(dirs.workspace)
            .redirectErrorStream(true)
            .redirectOutput(dirs.log)
        builder.environment().apply {
            putAll(dirs.environment())
            put("OPENCODE_PASSWORD", password)
        }
        process = builder.start()

        try {
            awaitReady()
        } catch (t: Throwable) {
            // Не оставляем «полузапущенный» процесс: иначе следующий start() решит, что движок уже работает.
            stop()
            throw t
        }
    }

    /** Первый запуск создаёт базу данных, поэтому ждём до минуты. */
    private suspend fun awaitReady() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            check(running) { "opencode остановился: ${dirs.logTail()}" }
            if (answersPing()) return
            delay(250)
        }
        error("opencode не запустился за минуту: ${dirs.logTail()}")
    }

    private fun answersPing(): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:$port/api/info").openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        connection.readTimeout = 2000
        connection.setRequestProperty("Authorization", basicAuth())
        try {
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun basicAuth(): String =
        "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())

    fun stop() {
        process?.destroy()
        process = null
    }

    private companion object {
        const val READY_TIMEOUT_MS = 60_000L
    }
}

/** Папки, в которых opencode хранит конфиг, данные и рабочие файлы. */
private class EngineDirs(context: Context) {
    private val files = context.filesDir
    private val cache = context.cacheDir

    val home = File(files, "home")
    val workspace = File(files, "workspace")
    val log = File(files, "opencode.log")
    private val tmp = File(cache, "tmp")

    fun prepare() {
        listOf(home, workspace, tmp, File(home, ".config/opencode")).forEach { it.mkdirs() }
    }

    fun environment(): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        "XDG_CONFIG_HOME" to File(home, ".config").absolutePath,
        "XDG_DATA_HOME" to File(home, ".local/share").absolutePath,
        "XDG_STATE_HOME" to File(home, ".local/state").absolutePath,
        "XDG_CACHE_HOME" to File(home, ".cache").absolutePath,
        "TMPDIR" to tmp.absolutePath,
        "SHELL" to "/system/bin/sh",
        "PATH" to (System.getenv("PATH") ?: "/system/bin")
    )

    fun logTail(): String = runCatching { log.readText().takeLast(1500) }.getOrDefault("")
}
