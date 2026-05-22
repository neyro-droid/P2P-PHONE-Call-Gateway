package com.remotephone.websocket

import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Минимальный WebSocket клиент без внешних зависимостей (RFC 6455).
 */
class PhoneWebSocketClient(
    private val host: String,
    private val port: Int,
    private val onMessage: (JSONObject) -> Unit,
    private val onBinaryMessage: ((ByteArray) -> Unit)? = null,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "PhoneWSClient"
        private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        // BUG FIX V1+V2: защита от NegativeArraySizeException (length > Int.MAX_VALUE)
        // и OOM (аномально большой фрейм из-за бага в сервере).
        // Максимальный реальный фрейм: экспорт всех контактов ~1MB. 16MB — с запасом.
        private const val MAX_FRAME_BYTES = 16L * 1024 * 1024  // 16 MB
    }

    // Bug1: @Volatile — socket пишется на executor thread, читается на main thread в disconnect()
    @Volatile private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val connected = AtomicBoolean(false)

    // connect loop lives on one dedicated thread; keeps frame ordering
    private val connectExecutor = Executors.newSingleThreadExecutor()
    // Bug3: single-thread send queue — задачи выстраиваются в очередь, не плодят потоки
    // CachedThreadPool + synchronized(out) = thread explosion при TCP backpressure
    private val sendExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(512),
        ThreadPoolExecutor.DiscardOldestPolicy()  // отбрасываем старые кадры если очередь полна
    )

    /** Self-signed cert trust-all — safe for local LAN gateway */
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })

    fun connect() {
        connectExecutor.execute {
            try {
                Log.d(TAG, "Connecting to $host:$port")
                // Bug2: закрываем sslSocket в catch — иначе FD утечка при fallback
                val sock: Socket = run {
                    var sslSock: SSLSocket? = null
                    try {
                        val ctx = SSLContext.getInstance("TLS")
                        ctx.init(null, trustAllCerts, java.security.SecureRandom())
                        sslSock = ctx.socketFactory.createSocket(host, port) as SSLSocket
                        sslSock.soTimeout = 10_000
                        sslSock.startHandshake()
                        sslSock.soTimeout = 0
                        sslSock
                    } catch (sslEx: Exception) {
                        try { sslSock?.close() } catch (_: Exception) {}
                        Log.w(TAG, "TLS failed (${sslEx.message}), trying plain TCP")
                        Socket(host, port)
                    }
                }
                sock.tcpNoDelay = true
                sock.soTimeout = 0
                socket = sock
                outputStream = sock.getOutputStream()
                val input = sock.getInputStream()

                // WebSocket handshake
                val key = Base64.getEncoder().encodeToString(Random.nextBytes(16))
                val handshake = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: $host:$port\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
                outputStream!!.write(handshake.toByteArray())
                outputStream!!.flush()

                // Читаем HTTP ответ
                val response = StringBuilder()
                val buf = ByteArray(1)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) throw Exception("Connection closed during handshake")
                    response.append(buf[0].toInt().toChar())
                    if (response.endsWith("\r\n\r\n")) break
                }

                val responseStr = response.toString()
                if (!responseStr.contains("101")) {
                    throw Exception("Handshake failed: ${responseStr.take(100)}")
                }

                connected.set(true)
                Log.d(TAG, "WebSocket connected to $host:$port")
                onConnected()

                // Читаем фреймы
                while (connected.get()) {
                    val frame = readFrame(input) ?: break
                    val (opcode, data) = frame
                    when (opcode) {
                        0x01 -> { // text
                            try { onMessage(JSONObject(String(data))) } catch (e: Exception) {}
                        }
                        0x02 -> { // binary
                            onBinaryMessage?.invoke(data)
                        }
                        0x08 -> break // close
                        0x09 -> sendFrame(0x0A, data) // pong
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WS error: ${e.message}")
                onError(e.message ?: "Connection error")
            } finally {
                connected.set(false)
                try { socket?.close() } catch (e: Exception) {}
                socket = null
                onDisconnected()
            }
        }
    }

    fun sendJson(msg: JSONObject) {
        if (!connected.get()) return
        // Bug3: sendExecutor — очередь задач вместо новых потоков
        sendExecutor.execute { sendFrame(0x01, msg.toString().toByteArray()) }
    }

    fun sendBinary(data: ByteArray) {
        if (!connected.get()) return
        sendExecutor.execute { sendFrame(0x02, data) }
    }

    fun disconnect() {
        connected.set(false)
        // Bug1: socket @Volatile — закрываем немедленно на вызывающем потоке
        try { socket?.close() } catch (_: Exception) {}
        // best-effort close frame, затем shutdown executor
        // BUG FIX 10: sendExecutor.shutdown() — иначе при каждом reconnect
        // создаётся новый PhoneWebSocketClient, старый sendExecutor не закрывается,
        // потоки накапливаются (thread leak при нестабильном соединении)
        try {
            sendExecutor.execute {
                try { sendFrame(0x08, ByteArray(0)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        sendExecutor.shutdown()
        // connectExecutor тоже закрываем — он завершится когда readLoop выйдет
        connectExecutor.shutdown()
    }

    fun isConnected() = connected.get()

    private fun readFrame(input: java.io.InputStream): Pair<Int, ByteArray>? {
        try {
            val h0 = input.read().takeIf { it >= 0 } ?: return null
            val h1 = input.read().takeIf { it >= 0 } ?: return null
            val opcode = h0 and 0x0F
            val masked = (h1 and 0x80) != 0
            var length = (h1 and 0x7F).toLong()

            if (length == 126L) {
                val b = ByteArray(2)
                input.readFully(b)
                length = ((b[0].toInt() and 0xFF) shl 8 or (b[1].toInt() and 0xFF)).toLong()
            } else if (length == 127L) {
                val b = ByteArray(8)
                input.readFully(b)
                length = 0
                for (i in 0..7) length = (length shl 8) or (b[i].toLong() and 0xFF)
            }

            val maskKey = if (masked) { val m = ByteArray(4); input.readFully(m); m } else null
            // BUG FIX V1: length > Int.MAX_VALUE → toInt() отрицательный → ByteArray(-N) → crash
            // BUG FIX V2: огромный фрейм → OOM. Лимит 16MB покрывает все реальные данные.
            if (length < 0 || length > MAX_FRAME_BYTES) {
                throw java.io.IOException("Frame size $length exceeds limit ${MAX_FRAME_BYTES}B — closing")
            }
            val data = ByteArray(length.toInt())
            input.readFully(data)
            if (maskKey != null) {
                for (i in data.indices) data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
            return Pair(opcode, data)
        } catch (e: Exception) {
            return null
        }
    }

    private fun sendFrame(opcode: Int, data: ByteArray) {
        try {
            val out = outputStream ?: return
            val mask = ByteArray(4).also { Random.nextBytes(it) }
            val length = data.size
            val header = mutableListOf<Byte>()
            header.add((0x80 or opcode).toByte())
            when {
                length < 126 -> header.add((0x80 or length).toByte())
                length < 65536 -> {
                    header.add((0x80 or 126).toByte())
                    header.add((length shr 8).toByte())
                    header.add((length and 0xFF).toByte())
                }
                else -> {
                    header.add((0x80 or 127).toByte())
                    for (i in 7 downTo 0) header.add(((length shr (i * 8)) and 0xFF).toByte())
                }
            }
            header.addAll(mask.toList())
            val masked = ByteArray(length) { i -> (data[i].toInt() xor mask[i % 4].toInt()).toByte() }
            synchronized(out) {
                out.write(header.toByteArray())
                out.write(masked)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendFrame error: ${e.message}")
            connected.set(false)
        }
    }

    private fun java.io.InputStream.readFully(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = read(buf, offset, buf.size - offset)
            if (n < 0) throw Exception("Stream closed")
            offset += n
        }
    }
}
