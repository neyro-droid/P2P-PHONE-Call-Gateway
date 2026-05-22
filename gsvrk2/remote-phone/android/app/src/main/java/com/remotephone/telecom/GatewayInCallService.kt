package com.remotephone.telecom

import android.content.Context
import android.media.AudioManager
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * InCallService для управления звонками без системных привилегий.
 * Работает когда приложение назначено Default Dialer.
 */
class GatewayInCallService : InCallService() {

    companion object {
        private const val TAG = "GatewayInCallSvc"
        private val activeCalls = CopyOnWriteArrayList<Call>()
        @Volatile var incomingNumber: String? = null

        fun answer(): Boolean {
            val call = activeCalls.firstOrNull {
                it.state == Call.STATE_RINGING || it.state == Call.STATE_NEW
            } ?: return false
            return try { call.answer(0); true } catch (e: Exception) {
                Log.e(TAG, "answer: ${e.message}"); false
            }
        }

        fun reject(): Boolean {
            val call = activeCalls.firstOrNull {
                it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING
            } ?: return false
            return try { call.disconnect(); true } catch (e: Exception) {
                Log.e(TAG, "reject: ${e.message}"); false
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING) return
        activeCalls.add(call)
        val number = call.details?.handle?.schemeSpecificPart
        if (call.state == Call.STATE_RINGING || call.state == Call.STATE_NEW) {
            incomingNumber = number
        }
        Log.d(TAG, "Call added: state=${call.state} number=$number total=${activeCalls.size}")

        // AUDIO FIX: устанавливаем максимальную громкость VOICE_CALL сразу при добавлении звонка
        // InCallService владеет аудио-фокусом — здесь это самое надёжное место
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
            // Включаем громкоговоритель
            am.isSpeakerphoneOn = true
            Log.d(TAG, "Audio: vol=$maxVol, speaker=ON")
        } catch (e: Exception) {
            Log.e(TAG, "setVolume: ${e.message}")
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        activeCalls.remove(call)
        if (activeCalls.isEmpty()) {
            incomingNumber = null
            // Выключаем громкоговоритель когда все звонки завершены
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.isSpeakerphoneOn = false
            } catch (e: Exception) {}
        }
        Log.d(TAG, "Call removed, remaining=${activeCalls.size}")
    }
}
