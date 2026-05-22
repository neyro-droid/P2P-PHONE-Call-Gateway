package com.remotephone.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.remotephone.MainActivity
import com.remotephone.contacts.ContactsController
import com.remotephone.data.CallStatus
import com.remotephone.data.ConnectionStatus
import com.remotephone.data.GatewayState
import com.remotephone.export.ExportController
import com.remotephone.sms.SmsController
import com.remotephone.telecom.CallLogController
import com.remotephone.telecom.TelecomController
import com.remotephone.util.RootManager
import com.remotephone.websocket.PhoneWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class PhoneGatewayService : Service() {

    companion object {
        const val ACTION_START    = "START_GATEWAY"
        const val ACTION_STOP     = "STOP_GATEWAY"
        const val EXTRA_HOST      = "host"
        const val EXTRA_PORT      = "port"
        const val CHANNEL_ID      = "GatewayChannel"
        const val NOTIF_ID        = 1
        private const val TAG     = "GatewayService"
        const val SAMPLE_RATE     = 16000
        const val CHANNEL_CONFIG  = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE      = 1600   // 100ms @ 16kHz
        // H-3 fix: 4 фрейма = 400ms максимальный буфер (реальное время)
        private const val QUEUE_CAPACITY = 4
    }

    inner class GatewayBinder : Binder() {
        fun service() = this@PhoneGatewayService
    }

    private val binder = GatewayBinder()
    // @Volatile — ws читается из telephony/executor потоков, пишется из main/service потока
    @Volatile private var ws: PhoneWebSocketClient? = null
    private var telecom: TelecomController? = null
    private var callLog: CallLogController? = null
    private var sms: SmsController? = null
    private var contacts: ContactsController? = null
    private var export: ExportController? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // H-4 fix: AtomicBoolean вместо volatile — check-then-act теперь атомарен через CAS
    private val audioStreamingFlag  = AtomicBoolean(false)
    private val speakerStreamingFlag = AtomicBoolean(false)

    // C-4 fix: bounded queue — offer() атомарно отбрасывает новый фрейм когда полон
    private val speakerQueue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    // Bug(v12)2 fix: var вместо val — пересоздаём после shutdown при START_STICKY перезапуске
    private var audioExecutor   = Executors.newSingleThreadExecutor()
    private var speakerExecutor = Executors.newSingleThreadExecutor()
    private val logFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Bug2 fix: пустая строка = не задан (не используем 192.168.1.1 как sentinel)
    var serverHost = ""
    var serverPort = 0

    private val _state = MutableStateFlow(GatewayState())
    val state: StateFlow<GatewayState> = _state

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    // C-3 fix: обрабатываем null intent (START_STICKY перезапуск ОС)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> {
                serverHost = intent.getStringExtra(EXTRA_HOST) ?: serverHost
                serverPort = intent.getIntExtra(EXTRA_PORT, serverPort)
                startForegroundSafe()
                start()
            }
            ACTION_STOP -> stopSelf()
            null -> {
                // Система перезапустила сервис (START_STICKY) — просто восстанавливаем foreground
                addLog("Restarted by OS, reconnecting...")
                startForegroundSafe()
                start()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        // BUG FIX 5: отменяем pending Handler ДО всего остального —
        // иначе через 1 сек он применит IN_COMMUNICATION на уже уничтоженном сервисе
        pendingAudioRunnable?.let { audioHandler.removeCallbacks(it) }
        pendingAudioRunnable = null
        audioModeSet.set(false)
        // 1. Останавливаем аудио флаги — потоки завершатся на следующей итерации
        audioStreamingFlag.set(false)
        speakerStreamingFlag.set(false)
        speakerQueue.clear()
        // 2. Закрываем сокет НЕМЕДЛЕННО — разблокирует readFrame в WS клиенте
        //    disconnect() теперь закрывает socket на вызывающем потоке (не через executor)
        try { ws?.disconnect() } catch (e: Exception) {}
        // 3. Останавливаем telephony listener
        try { telecom?.stopListening() } catch (e: Exception) {}
        // 4. Shutdown executors + restore audio mode — в фоне, не блокируем main thread
        audioExecutor.shutdown()
        speakerExecutor.shutdown()
        Thread {
            try { audioExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
            try { speakerExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
            setCallAudioMode(false)
        }.start()
        try { wakeLock?.release() } catch (e: Exception) {}
        updateStatus(ConnectionStatus.STOPPED, "Stopped")
        super.onDestroy()
    }

    // ── Start ─────────────────────────────────────────────────────────────────
    private fun start() {
        // Bug(v12)2 fix: пересоздаём executor-ы если они были shutdown (START_STICKY перезапуск)
        // Без этого audioExecutor.execute{} бросает RejectedExecutionException
        if (audioExecutor.isShutdown)    audioExecutor   = Executors.newSingleThreadExecutor()
        if (speakerExecutor.isShutdown)  speakerExecutor = Executors.newSingleThreadExecutor()
        // Читаем SharedPreferences ТОЛЬКО как fallback при OS restart (null intent).
        // При ACTION_START — serverHost/serverPort уже установлены из intent ДО вызова start().
        // Bug#1 fix: не перезаписываем intent-значения если они уже установлены.
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        // Bug2 fix: убран sentinel "192.168.1.1" — это реальный валидный адрес
        if (serverHost.isBlank()) {
            val savedHost = prefs.getString("host", "")
            if (!savedHost.isNullOrBlank()) serverHost = savedHost
        }
        if (serverPort == 0) {
            serverPort = prefs.getInt("port_int", 8765)
        }

        addLog("Starting gateway → $serverHost:$serverPort")
        updateStatus(ConnectionStatus.CONNECTING, "Connecting to $serverHost:$serverPort...")
        acquireWake()

        // Bug#5 fix: останавливаем старые listeners перед созданием новых
        telecom?.stopListening()

        callLog  = CallLogController(applicationContext)
        sms      = SmsController(applicationContext)
        contacts = ContactsController(applicationContext)
        export   = ExportController(applicationContext)
        telecom  = TelecomController(applicationContext) { status, number ->
            onCallState(status, number)
        }.also { it.startListening() }

        connectWebSocket()
    }

    private fun connectWebSocket() {
        // H-3 fix: закрываем старое соединение перед открытием нового
        ws?.disconnect()
        ws = PhoneWebSocketClient(
            host = serverHost,
            port = serverPort,
            onMessage = { msg -> onCommand(msg) },
            onBinaryMessage = { data ->
                // Аудио с ПК → телефон: формат байт 0x02 + PCM 16-bit LE
                if (data.isNotEmpty() && data[0] == 0x02.toByte()) {
                    enqueueSpeakerFrame(data.copyOfRange(1, data.size))
                }
            },
            onConnected = {
                addLog("Connected to server!")
                updateStatus(ConnectionStatus.CONNECTED, "Connected to $serverHost:$serverPort ✓")
                ws?.sendJson(JSONObject().apply { put("type", "register"); put("role", "phone") })
                sendState()
            },
            onDisconnected = {
                addLog("Disconnected from server")
                updateStatus(ConnectionStatus.FAILED, "Disconnected — tap Start to reconnect")
            },
            onError = { err ->
                addLog("Connection error: $err")
                updateStatus(ConnectionStatus.FAILED, "Error: $err")
                notify("Connection failed: $err")
            }
        )
        ws?.connect()
    }

    // ── Управление аудио-режимом звонка ──────────────────────────────────────
    private var audioManager: AudioManager? = null
    @Volatile private var savedAudioMode = AudioManager.MODE_NORMAL
    @Volatile private var savedSpeakerOn = false
    private val audioModeSet = AtomicBoolean(false)
    // BUG FIX 1/5/7: Handler + Runnable ref для отмены postDelayed
    private val audioHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingAudioRunnable: Runnable? = null

    private fun setCallAudioMode(enabled: Boolean) {
        try {
            if (audioManager == null) {
                audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            }
            val am = audioManager!!
            if (enabled && audioModeSet.compareAndSet(false, true)) {
                savedAudioMode = am.mode
                // BUG FIX 6: сохраняем громкоговоритель ДО любых изменений от InCallService.
                // GatewayInCallService может уже выставить speakerOn=true к этому моменту,
                // поэтому сохраняем false явно (до звонка громкоговоритель всегда выключен).
                savedSpeakerOn = false
                addLog("Audio mode before: ${modeStr(am.mode)}")

                // BUG FIX 1/5/7: сохраняем Runnable чтобы отменить если звонок сброшен быстро
                // (USSD, короткий OFFHOOK, onDestroy) — иначе Handler применит IN_COMMUNICATION
                // после того как режим уже восстановлен.
                val runnable = Runnable {
                    if (audioModeSet.get()) {  // двойная проверка — страховка от гонки
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        am.isSpeakerphoneOn = true
                        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                        val maxVoice = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0)
                        addLog("Audio: MODE_IN_COMMUNICATION + speaker ON, music=$maxMusic voice=$maxVoice")
                    }
                }
                pendingAudioRunnable = runnable
                audioHandler.postDelayed(runnable, 1000)

            } else if (!enabled && audioModeSet.compareAndSet(true, false)) {
                // Отменяем pending Runnable до того как он успеет применить IN_COMMUNICATION
                pendingAudioRunnable?.let { audioHandler.removeCallbacks(it) }
                pendingAudioRunnable = null
                am.isSpeakerphoneOn = savedSpeakerOn
                am.mode = savedAudioMode
                addLog("Audio: restored mode=${modeStr(am.mode)} speaker=$savedSpeakerOn")
            }
        } catch (e: Exception) {
            addLog("setCallAudioMode error: ${e.message}")
        }
    }

    private fun modeStr(mode: Int) = when(mode) {
        AudioManager.MODE_NORMAL           -> "NORMAL"
        AudioManager.MODE_IN_CALL          -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else                               -> "mode=$mode"
    }

    // ── Микрофон телефона → ПК ────────────────────────────────────────────────
    fun startAudioStream() {
        if (!audioStreamingFlag.compareAndSet(false, true)) return
        audioExecutor.execute {
            var rec: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufSize = maxOf(minBuf, FRAME_SIZE * 4)
                addLog("AudioRecord: minBuf=$minBuf bufSize=$bufSize")

                // Небольшая задержка — даём AudioManager время переключить режим
                // перед тем как открывать AudioRecord
                Thread.sleep(150)

                // ROOT MODE: если есть root и CAPTURE_AUDIO_OUTPUT выдан —
                // VOICE_CALL захватывает обе стороны звонка (и ваш голос, и собеседника).
                // NO-ROOT: UNPROCESSED/VOICE_COMMUNICATION захватывают только микрофон телефона.
                // Акустический мост: звук собеседника из динамика → микрофон → ПК.
                val sources = if (RootManager.hasAudioCapture) {
                    addLog("Audio: ROOT mode — using VOICE_CALL (both sides)")
                    intArrayOf(
                        MediaRecorder.AudioSource.VOICE_CALL,         // обе стороны (root)
                        MediaRecorder.AudioSource.VOICE_DOWNLINK,     // только собеседник (root fallback)
                        MediaRecorder.AudioSource.UNPROCESSED,        // сырой mic
                        MediaRecorder.AudioSource.MIC
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addLog("Audio: NO-ROOT mode — using UNPROCESSED (mic only, acoustic bridge)")
                    intArrayOf(
                        MediaRecorder.AudioSource.UNPROCESSED,
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.MIC
                    )
                } else {
                    intArrayOf(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.MIC
                    )
                }

                for (src in sources) {
                    try {
                        val candidate = AudioRecord(src, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            rec = candidate
                            addLog("AudioRecord OK: source=${srcStr(src)}")
                            break
                        } else {
                            candidate.release()
                            addLog("AudioRecord source ${srcStr(src)} → state=${candidate.state}, trying next")
                        }
                    } catch (e: Exception) {
                        addLog("AudioRecord ${srcStr(src)} exception: ${e.message}")
                    }
                }

                if (rec == null) {
                    addLog("AudioRecord: ALL sources failed — check RECORD_AUDIO permission!")
                    audioStreamingFlag.set(false)
                    return@execute
                }

                // BUG FIX 2: AcousticEchoCanceler/NoiseSuppressor держат нативный JNI ресурс.
                // Без .release() — native leak. Используем use{} (AutoCloseable с API 16).
                val sessionId = rec.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        AcousticEchoCanceler.create(sessionId)?.let { aec ->
                            aec.enabled = false
                            aec.release()
                            addLog("AEC: disabled + released")
                        }
                    } catch (e: Exception) { addLog("AEC disable failed: ${e.message}") }
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        NoiseSuppressor.create(sessionId)?.let { ns ->
                            ns.enabled = false
                            ns.release()
                            addLog("NS: disabled + released")
                        }
                    } catch (e: Exception) { addLog("NS disable failed: ${e.message}") }
                }

                rec.startRecording()
                addLog("Audio streaming started → sending to server")
                val buf = ShortArray(FRAME_SIZE)
                var frameCount = 0
                while (audioStreamingFlag.get()) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read <= 0) {
                        addLog("AudioRecord.read returned $read — stopping")
                        break
                    }
                    if (ws?.isConnected() == true) {
                        val bytes = ByteArray(read * 2)
                        for (i in 0 until read) {
                            bytes[i * 2]     = (buf[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((buf[i].toInt() shr 8) and 0xFF).toByte()
                        }
                        val frame = ByteArray(1 + bytes.size)
                        frame[0] = 0x01
                        bytes.copyInto(frame, 1)
                        ws?.sendBinary(frame)
                        frameCount++
                        if (frameCount == 1 || frameCount % 50 == 0) {
                            addLog("Audio TX: frame #$frameCount size=${frame.size}")
                        }
                    }
                }
            } catch (e: Exception) {
                addLog("Audio error: ${e.message}")
            } finally {
                audioStreamingFlag.set(false)
                try { rec?.stop() }    catch (e: Exception) {}
                try { rec?.release() } catch (e: Exception) {}
                addLog("Audio streaming stopped")
            }
        }
    }

    private fun srcStr(src: Int) = when(src) {
        MediaRecorder.AudioSource.VOICE_CALL          -> "VOICE_CALL"
        MediaRecorder.AudioSource.VOICE_DOWNLINK      -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.MIC                 -> "MIC"
        MediaRecorder.AudioSource.UNPROCESSED         -> "UNPROCESSED"
        else                                          -> "src=$src"
    }

    fun stopAudioStream() {
        audioStreamingFlag.set(false)
    }

    // ── ПК → динамик телефона ─────────────────────────────────────────────────
    fun enqueueSpeakerFrame(pcm: ByteArray) {
        synchronized(speakerQueue) {
            while (speakerQueue.size >= QUEUE_CAPACITY) {
                speakerQueue.poll()
            }
            speakerQueue.offer(pcm)
        }
    }

    fun startSpeakerStream() {
        if (!speakerStreamingFlag.compareAndSet(false, true)) return
        speakerQueue.clear()
        speakerExecutor.execute {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT)
                val bufSize = maxOf(minBuf, FRAME_SIZE * 4)
                addLog("AudioTrack: minBuf=$minBuf bufSize=$bufSize")

                // GEMINI FIX: USAGE_VOICE_COMMUNICATION глушится telephony-стеком в MODE_IN_CALL.
                // USAGE_MEDIA (STREAM_MUSIC) работает в MODE_NORMAL — обходит блокировку.
                // Мы переключаем AudioManager в MODE_NORMAL в setCallAudioMode(true).
                track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(AUDIO_FORMAT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AUDIO_FORMAT, bufSize, AudioTrack.MODE_STREAM
                    )
                }

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    addLog("AudioTrack: failed to initialize — state=${track.state}")
                    speakerStreamingFlag.set(false)
                    return@execute
                }

                track.setVolume(AudioTrack.getMaxVolume())
                track.play()
                addLog("Speaker stream started, waiting for frames...")

                var frameCount = 0
                while (speakerStreamingFlag.get()) {
                    val frame = speakerQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: continue
                    track.write(frame, 0, frame.size)
                    frameCount++
                    if (frameCount == 1 || frameCount % 50 == 0) {
                        addLog("Audio RX: frame #$frameCount size=${frame.size}")
                    }
                }
            } catch (e: Exception) {
                addLog("Speaker error: ${e.message}")
            } finally {
                speakerStreamingFlag.set(false)
                try { track?.stop() }    catch (e: Exception) {}
                try { track?.release() } catch (e: Exception) {}
                speakerQueue.clear()
                addLog("Speaker stream stopped")
            }
        }
    }

    fun stopSpeakerStream() {
        speakerStreamingFlag.set(false)
        speakerQueue.clear()
    }

    // ── Команды ───────────────────────────────────────────────────────────────
    private fun onCommand(msg: JSONObject) {
        try {
            when (msg.optString("type")) {
                "dial" -> {
                    val n   = msg.getString("number")
                    val sub = msg.optInt("subscriptionId", -1).takeIf { it >= 0 }
                    val ok  = try { telecom?.dial(n, sub) ?: false } catch (e: Exception) { false }
                    addLog("Dial $n → $ok")
                    reply(JSONObject().apply { put("type","dial_result"); put("success",ok); put("number",n) })
                }
                "hangup" -> {
                    try { telecom?.hangup() } catch (e: Exception) {}
                    stopAudioStream()
                    stopSpeakerStream()
                    setCallAudioMode(false)
                    // FIX: явно шлём IDLE чтобы браузер не зависал если telephony колбэк запоздал
                    _state.update { it.copy(callStatus = CallStatus.IDLE, callNumber = "") }
                    reply(JSONObject().apply { put("type","hangup_result"); put("success",true) })
                    reply(JSONObject().apply { put("type","call_state"); put("status","IDLE"); put("number","") })
                }
                "accept_call" -> {
                    val ok = try { telecom?.acceptCall() ?: false } catch (e: Exception) { false }
                    reply(JSONObject().apply { put("type","accept_result"); put("success",ok) })
                }
                "start_audio" -> { setCallAudioMode(true); startAudioStream(); startSpeakerStream() }
                "stop_audio"  -> { stopAudioStream(); stopSpeakerStream(); setCallAudioMode(false) }
                "get_call_history" -> reply(JSONObject().apply {
                    put("type","call_history")
                    put("data", try { callLog?.getCallHistory() } catch (e: Exception) { org.json.JSONArray() } ?: org.json.JSONArray())
                })
                "get_contacts" -> reply(JSONObject().apply {
                    put("type","contacts")
                    put("data", try { contacts?.getContacts() } catch (e: Exception) { org.json.JSONArray() } ?: org.json.JSONArray())
                })
                "get_sms" -> reply(JSONObject().apply {
                    put("type","sms_history")
                    put("data", try { sms?.getSmsHistory() } catch (e: Exception) { org.json.JSONArray() } ?: org.json.JSONArray())
                })
                "send_sms" -> {
                    val ok = try {
                        sms?.sendSms(
                            msg.getString("to"), msg.getString("body"),
                            msg.optInt("subscriptionId", -1).takeIf { it >= 0 }
                        ) ?: false
                    } catch (e: Exception) { false }
                    reply(JSONObject().apply { put("type","send_sms_result"); put("success",ok) })
                }
                "get_state" -> sendState()
                "ping" -> {
                    reply(JSONObject().apply {
                        put("type", "pong")
                        put("ts", msg.optLong("ts", 0L))
                    })
                }
                // ── Экспорт данных ───────────────────────────────────────────
                "export_contacts" -> {
                    val fmt = msg.optString("format", "json")
                    if (fmt == "csv") {
                        reply(JSONObject().apply {
                            put("type","export_result"); put("kind","contacts"); put("format","csv")
                            put("data", export?.exportContactsCsv() ?: "")
                        })
                    } else {
                        reply(JSONObject().apply {
                            put("type","export_result"); put("kind","contacts"); put("format","json")
                            put("data", export?.exportContactsJson() ?: org.json.JSONArray())
                        })
                    }
                }
                "export_sms" -> {
                    val fmt = msg.optString("format", "json")
                    if (fmt == "csv") {
                        reply(JSONObject().apply {
                            put("type","export_result"); put("kind","sms"); put("format","csv")
                            put("data", export?.exportSmsCsv() ?: "")
                        })
                    } else {
                        reply(JSONObject().apply {
                            put("type","export_result"); put("kind","sms"); put("format","json")
                            put("data", export?.exportSmsJson() ?: org.json.JSONArray())
                        })
                    }
                }
                "export_call_log" -> {
                    val fmt = msg.optString("format", "json")
                    if (fmt == "csv") {
                        reply(JSONObject().apply {
                            put("type","export_result"); put("kind","call_log"); put("format","csv")
                            put("data", export?.exportCallLogCsv() ?: "")
                        })
                    } else {
                        reply(JSONObject().apply {
                            put("type","export_result"); put("kind","call_log"); put("format","json")
                            put("data", export?.exportCallLogJson() ?: org.json.JSONArray())
                        })
                    }
                }
                "export_all" -> {
                    reply(JSONObject().apply {
                        put("type","export_result"); put("kind","all"); put("format","json")
                        put("data", export?.exportAll() ?: JSONObject())
                    })
                }
                // ── Root статус ──────────────────────────────────────────────
                "get_root_status" -> {
                    reply(JSONObject().apply {
                        put("type","root_status")
                        put("status", RootManager.status.name)
                        put("hasAudioCapture", RootManager.hasAudioCapture)
                    })
                }
            }
        } catch (e: Exception) {
            addLog("Command error [${e.javaClass.simpleName}]: ${e.message}")
        }
    }

    private fun onCallState(status: CallStatus, number: String?) {
        try {
            when (status) {
                CallStatus.ACTIVE  -> {
                    setCallAudioMode(true)   // громкоговоритель ON перед стартом AudioRecord
                    startAudioStream()
                    startSpeakerStream()
                }
                CallStatus.DIALING -> { /* аудио стартует при переходе в ACTIVE */ }
                CallStatus.IDLE    -> {
                    stopAudioStream()
                    stopSpeakerStream()
                    setCallAudioMode(false)  // восстанавливаем режим
                }
                else -> {}
            }
            // Bug3 fix: update{} — atomic CAS, не перетирает поля от concurrent addLog
            _state.update { it.copy(callStatus = status, callNumber = number ?: "") }
            addLog("Call: $status ${number ?: ""}")
            reply(JSONObject().apply {
                put("type","call_state"); put("status",status.name); put("number",number ?: "")
            })
        } catch (e: Exception) {}
    }

    private fun sendState() {
        val s = _state.value
        val sims = try { telecom?.getSimList() ?: emptyList() } catch (e: Exception) { emptyList() }
        reply(JSONObject().apply {
            put("type","state")
            put("callStatus", s.callStatus.name)
            put("callNumber", s.callNumber)
            put("rootStatus", RootManager.status.name)
            put("hasAudioCapture", RootManager.hasAudioCapture)
            put("sims", org.json.JSONArray().also { arr ->
                sims.forEach { sim ->
                    arr.put(JSONObject().apply {
                        put("slotIndex",      sim.slotIndex)
                        put("subscriptionId", sim.subscriptionId)
                        put("displayName",    sim.displayName)
                        put("number",         sim.number ?: "")
                    })
                }
            })
        })
    }

    private fun reply(json: JSONObject) { ws?.sendJson(json) }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun addLog(msg: String) {
        Log.d(TAG, msg)
        val line = "${logFmt.format(Date())}  $msg"
        // Bug#2 fix: update{} использует CAS — потокобезопасно без явных локов
        _state.update { state ->
            val logs = if (state.logs.size >= 200) state.logs.drop(1) else state.logs
            state.copy(logs = logs + line)
        }
    }

    private fun updateStatus(status: ConnectionStatus, text: String) {
        // Bug4 fix: update{} — atomic CAS
        _state.update { it.copy(connectionStatus = status, statusText = text) }
        notify(text)
    }

    private fun startForegroundSafe() {
        try {
            val notif = buildNotif("Starting...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            try { startForeground(NOTIF_ID, buildNotif("Starting...")) } catch (e2: Exception) {}
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "P2P Gateway", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("P2P Phone Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .setOngoing(true).setSilent(true).build()
    }

    private fun notify(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotif(text))
        } catch (e: Exception) {}
    }

    private fun acquireWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // SCREEN FIX: PARTIAL_WAKE_LOCK держит только CPU — экран всё равно гаснет.
            // SCREEN_BRIGHT_WAKE_LOCK + ON_AFTER_RELEASE + ACQUIRE_CAUSES_WAKEUP
            // держат экран включённым пока сервис работает.
            @Suppress("DEPRECATION")
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE
            wakeLock = pm.newWakeLock(flags, "P2PGateway:screenWake")
            wakeLock?.acquire(10 * 60 * 60 * 1000L)  // 10 часов максимум
        } catch (e: Exception) {
            // Fallback на PARTIAL если SCREEN_BRIGHT недоступен
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "P2PGateway:wake")
                wakeLock?.acquire(10 * 60 * 60 * 1000L)
            } catch (e2: Exception) {}
        }
    }
}
