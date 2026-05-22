package com.remotephone.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * Управление root-доступом.
 *
 * Логика:
 * 1. Проверяем наличие su в PATH
 * 2. Запрашиваем root через su (появится popup SuperSU/Magisk)
 * 3. Если получили — выдаём себе CAPTURE_AUDIO_OUTPUT через pm grant
 *    (это разрешение позволяет AudioRecord(VOICE_CALL) захватывать обе стороны звонка)
 */
object RootManager {
    private const val TAG = "RootManager"
    private const val ROOT_TIMEOUT_SEC = 30L  // таймаут ожидания диалога Magisk

    enum class RootStatus { UNKNOWN, GRANTED, DENIED, NOT_AVAILABLE }

    // BUG FIX 5: StateFlow вместо @Volatile + delay() опроса —
    // UI получает обновление мгновенно без race condition
    private val _statusFlow = MutableStateFlow(RootStatus.UNKNOWN)
    val statusFlow = _statusFlow.asStateFlow()

    @Volatile var status: RootStatus = RootStatus.UNKNOWN
        private set(value) { field = value; _statusFlow.value = value }

    @Volatile var hasAudioCapture: Boolean = false

    /**
     * Проверяет/запрашивает root. Блокирующий — вызывать из фонового потока.
     * @return true если root получен
     */
    fun requestRoot(packageName: String): Boolean {
        if (status == RootStatus.GRANTED) return true
        if (status == RootStatus.NOT_AVAILABLE) return false

        if (!isSuAvailable()) {
            status = RootStatus.NOT_AVAILABLE
            Log.w(TAG, "su not found")
            return false
        }

        return try {
            val process = Runtime.getRuntime().exec("su")
            val out = DataOutputStream(process.outputStream)

            // BUG FIX 1: дренируем stdout и stderr в фоновых потоках —
            // иначе deadlock: su блокируется на write когда буфер ОС (~64KB) полон,
            // waitFor() ждёт завершения процесса → бесконечное ожидание.
            val stdoutDrain = Thread { runCatching { process.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) } }
            val stderrDrain = Thread { runCatching { process.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) } }
            stdoutDrain.isDaemon = true
            stderrDrain.isDaemon = true
            stdoutDrain.start()
            stderrDrain.start()

            out.writeBytes("pm grant $packageName android.permission.CAPTURE_AUDIO_OUTPUT\n")
            out.writeBytes("exit\n")
            out.flush()
            out.close()

            // BUG FIX 3: таймаут 30 сек — если пользователь игнорирует диалог Magisk,
            // поток не зависает вечно
            val finished = process.waitFor(ROOT_TIMEOUT_SEC, TimeUnit.SECONDS)
            stdoutDrain.join(1000)
            stderrDrain.join(1000)
            // BUG FIX (K1): всегда уничтожаем process чтобы освободить FD
            process.destroy()

            if (finished && process.exitValue() == 0) {
                status = RootStatus.GRANTED
                hasAudioCapture = true
                Log.d(TAG, "Root granted, CAPTURE_AUDIO_OUTPUT issued")
                true
            } else {
                status = if (finished) RootStatus.DENIED else RootStatus.DENIED
                Log.w(TAG, "Root denied (finished=$finished)")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestRoot error: ${e.message}")
            status = RootStatus.DENIED
            false
        }
    }

    /**
     * Выполняет shell-команды с root правами. Блокирующий.
     */
    fun execRoot(vararg commands: String): Boolean {
        if (status != RootStatus.GRANTED) return false
        return try {
            val process = Runtime.getRuntime().exec("su")
            val out = DataOutputStream(process.outputStream)

            // BUG FIX 2: дренируем потоки — та же deadlock проблема что в requestRoot
            val stdoutDrain = Thread { runCatching { process.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) } }
            val stderrDrain = Thread { runCatching { process.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) } }
            stdoutDrain.isDaemon = true
            stderrDrain.isDaemon = true
            stdoutDrain.start()
            stderrDrain.start()

            for (cmd in commands) out.writeBytes("$cmd\n")
            out.writeBytes("exit\n")
            out.flush()
            out.close()

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            stdoutDrain.join(1000)
            stderrDrain.join(1000)
            process.destroy()

            finished && process.exitValue() == 0
        } catch (e: Exception) {
            Log.e(TAG, "execRoot error: ${e.message}")
            false
        }
    }

    private fun isSuAvailable(): Boolean {
        val paths = listOf(
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/su/bin/su", "/magisk/.core/bin/su"
        )
        return paths.any { java.io.File(it).exists() }
    }
}
