package com.remotephone.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.remotephone.service.PhoneGatewayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Читаем сохранённый адрес сервера
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""
        val port = prefs.getInt("port_int", 8765)
        // Не запускаем автоматически если сервер не настроен
        if (host.isBlank()) return
        val i = Intent(context, PhoneGatewayService::class.java).apply {
            action = PhoneGatewayService.ACTION_START
            putExtra(PhoneGatewayService.EXTRA_HOST, host)
            putExtra(PhoneGatewayService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(context, i)
    }
}
