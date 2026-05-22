package com.remotephone

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.remotephone.data.ConnectionStatus
import com.remotephone.data.GatewayState
import com.remotephone.service.PhoneGatewayService

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private var svc: PhoneGatewayService? = null
    private var bound by mutableStateOf(false)  // mutableStateOf чтобы LaunchedEffect реагировал

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            svc = (b as PhoneGatewayService.GatewayBinder).service()
            bound = true
        }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null; bound = false }
    }

    // BUG-02 fix: проверяем результат разрешений
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            startGateway()
        } else {
            Log.w(TAG, "Permissions denied: $denied")
            // Запускаем всё равно — некоторые разрешения (CALL_LOG, SMS) могут быть не нужны
            // если пользователь отказал только в необязательных
            startGateway()
        }
    }
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkPermsAndStart()
    }

    private val prefs by lazy { getSharedPreferences("gateway", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SCREEN FIX: не гасить экран пока приложение на переднем плане
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            var state by remember { mutableStateOf(GatewayState()) }
            var activeTab by remember { mutableStateOf(0) }
            var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
            var port by remember { mutableStateOf(prefs.getString("port", "8765") ?: "8765") }
            val clip = LocalClipboardManager.current

            // BUG-10 fix: running выводится из фактического состояния сервиса
            val running by remember(state.connectionStatus) {
                derivedStateOf {
                    state.connectionStatus == ConnectionStatus.CONNECTED ||
                    state.connectionStatus == ConnectionStatus.CONNECTING
                }
            }

            LaunchedEffect(bound) {
                if (bound) svc?.state?.collect { state = it }
            }

            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Color(0xFF060610))) {
                    // Tabs
                    Row(Modifier.fillMaxWidth().background(Color(0xFF0F1525))) {
                        listOf("📡 Gateway", "📋 Logs").forEachIndexed { i, label ->
                            TextButton(onClick = { activeTab = i }, modifier = Modifier.weight(1f)) {
                                Text(label,
                                    color = if (activeTab == i) Color(0xFF64FFDA) else Color(0xFF5A6A8A),
                                    fontWeight = if (activeTab == i) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp)
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF1E2035))

                    if (activeTab == 0) {
                        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(12.dp))
                            Text("🔒 P2P Gateway", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("Direct WebSocket · No STUN/TURN/MQTT", color = Color(0xFF4A5A7A), fontSize = 11.sp)
                            Spacer(Modifier.height(20.dp))

                            // Адрес сервера (показываем только когда не запущен)
                            if (!running) {
                                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1525))) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text("PC Server address", color = Color(0xFF64FFDA), fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(8.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = host,
                                                onValueChange = { host = it; prefs.edit().putString("host", it).apply() },
                                                modifier = Modifier.weight(1f),
                                                label = { Text("IP address", fontSize = 11.sp) },
                                                placeholder = { Text("192.168.1.x", color = Color(0xFF3A4A3A)) },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF64FFDA),
                                                    unfocusedBorderColor = Color(0xFF1E2035),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White,
                                                    cursorColor = Color(0xFF64FFDA),
                                                    focusedLabelColor = Color(0xFF64FFDA),
                                                    unfocusedLabelColor = Color(0xFF5A6A8A)
                                                )
                                            )
                                            OutlinedTextField(
                                                value = port,
                                                onValueChange = { port = it; prefs.edit().putString("port", it).putInt("port_int", it.toIntOrNull() ?: 8765).apply() },
                                                modifier = Modifier.width(90.dp),
                                                label = { Text("Port", fontSize = 11.sp) },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF64FFDA),
                                                    unfocusedBorderColor = Color(0xFF1E2035),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White,
                                                    cursorColor = Color(0xFF64FFDA),
                                                    focusedLabelColor = Color(0xFF64FFDA),
                                                    unfocusedLabelColor = Color(0xFF5A6A8A)
                                                )
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text("IP твоего ПК (запусти server.py — он покажет IP)", color = Color(0xFF3A5A3A), fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }

                            // Статус
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = statusBg(state.connectionStatus))) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(statusEmoji(state.connectionStatus), fontSize = 22.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(state.statusText, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        if (state.sims.isNotEmpty())
                                            Text("📶 ${state.sims.joinToString(" | ") { it.displayName }}",
                                                color = Color(0xFF5A6A8A), fontSize = 11.sp)
                                    }
                                }
                            }

                            // Статус звонка
                            if (state.callStatus.name != "IDLE") {
                                Spacer(Modifier.height(10.dp))
                                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A0A))) {
                                    Text("📞 ${state.callStatus.name} ${state.callNumber}",
                                        Modifier.padding(12.dp), color = Color(0xFFFFAB40), fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // BUG-09 fix: кнопка Start не меняет running — это делает сервис
                            Button(
                                onClick = {
                                    if (!running) {
                                        if (host.isBlank()) return@Button
                                        requestNotifPermIfNeeded(host, port.toIntOrNull() ?: 8765)
                                        // running будет true когда сервис реально запустится
                                    } else {
                                        stop()
                                    }
                                },
                                Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                                enabled = running || host.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!running) Color(0xFF64FFDA) else Color(0xFFE53935)
                                )
                            ) {
                                Text(if (!running) "▶  Start" else "■  Stop",
                                    color = if (!running) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }

                            if (host.isBlank() && !running) {
                                Spacer(Modifier.height(4.dp))
                                Text("Введи IP сервера выше", color = Color(0xFFE53935), fontSize = 11.sp)
                            }

                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .apply { data = Uri.parse("package:$packageName") }) } catch (e: Exception) {}
                            }) { Text("⚡ Disable battery optimization", color = Color(0xFF3A5A3A), fontSize = 12.sp) }
                        }
                    } else {
                        // Logs
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Connection logs", color = Color(0xFF64FFDA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = {
                                    clip.setText(AnnotatedString(state.logs.joinToString("\n")))
                                }) { Text("📋 Copy all", color = Color(0xFF5A6A8A), fontSize = 12.sp) }
                            }
                            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                items(state.logs) { line ->
                                    Text(line, color = logColor(line), fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun statusBg(s: ConnectionStatus) = when(s) {
        ConnectionStatus.CONNECTED  -> Color(0xFF0A2A0A)
        ConnectionStatus.FAILED     -> Color(0xFF2A0A0A)
        ConnectionStatus.CONNECTING -> Color(0xFF1A1A0A)
        else                        -> Color(0xFF0F1525)
    }
    private fun statusEmoji(s: ConnectionStatus) = when(s) {
        ConnectionStatus.CONNECTED  -> "🟢"
        ConnectionStatus.FAILED     -> "🔴"
        ConnectionStatus.CONNECTING -> "🔄"
        ConnectionStatus.STOPPED    -> "⚫"
    }
    private fun logColor(line: String) = when {
        line.contains("error", true) || line.contains("fail", true) -> Color(0xFFFF6B6B)
        line.contains("connected", true) || line.contains("started", true) -> Color(0xFF64FFDA)
        else -> Color(0xFF7A8AAA)
    }

    private var pendingHost = ""
    private var pendingPort = 8765

    private fun requestNotifPermIfNeeded(host: String, port: Int) {
        pendingHost = host; pendingPort = port
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); return
            }
        }
        checkPermsAndStart()
    }

    private fun checkPermsAndStart() {
        val needed = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            needed.add(Manifest.permission.ANSWER_PHONE_CALLS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startGateway() else perms.launch(missing.toTypedArray())
    }

    private fun startGateway() {
        try {
            val i = Intent(this, PhoneGatewayService::class.java).apply {
                action = PhoneGatewayService.ACTION_START
                putExtra(PhoneGatewayService.EXTRA_HOST, pendingHost)
                putExtra(PhoneGatewayService.EXTRA_PORT, pendingPort)
            }
            ContextCompat.startForegroundService(this, i)
            bindService(i, conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.e(TAG, "startGateway: ${e.message}", e) }
    }

    private fun stop() {
        // Сначала unbind — иначе система может перезапустить сервис после stopService
        try { if (bound) { unbindService(conn); bound = false } } catch (e: Exception) {}
        // ACTION_STOP → сервис вызывает stopSelf() изнутри (правильный путь для foreground service)
        try {
            startService(Intent(this, PhoneGatewayService::class.java).apply {
                action = PhoneGatewayService.ACTION_STOP
            })
        } catch (e: Exception) {}
        // Fallback: прямая остановка на случай если сервис не запущен как foreground
        try { stopService(Intent(this, PhoneGatewayService::class.java)) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        if (bound) { try { unbindService(conn) } catch (e: Exception) {}; bound = false }
        super.onDestroy()
    }
}
