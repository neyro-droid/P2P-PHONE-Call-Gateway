package com.remotephone.telecom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.remotephone.data.CallStatus
import com.remotephone.data.SimInfo
import java.util.concurrent.Executors

class TelecomController(
    private val context: Context,
    private val onCallState: (CallStatus, String?) -> Unit
) {
    companion object { private const val TAG = "TelecomController" }

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val telecomManager   = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    // Bug5: @Volatile — пишется с main thread (dial), читается с telephony callback thread
    @Volatile var currentNumber: String? = null
    @Volatile private var isOutgoing = false

    // ── API 31+: TelephonyCallback ────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.S)
    private val telephonyCallback = object : TelephonyCallback(),
        TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            // Bug4: API 31+ не передаёт номер в этом callback.
            // Для входящих — берём из GatewayInCallService.incomingNumber (установлен в onCallAdded).
            // Для исходящих — currentNumber уже выставлен в dial().
            val number = when (state) {
                TelephonyManager.CALL_STATE_RINGING ->
                    GatewayInCallService.incomingNumber ?: currentNumber
                else -> currentNumber
            }
            handleStateChange(state, number)
        }
    }

    // ── API 29–30: PhoneStateListener ─────────────────────────────────────────
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleStateChange(state, phoneNumber)
        }
    }

    private fun handleStateChange(state: Int, phoneNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isOutgoing = false
                currentNumber = phoneNumber
                onCallState(CallStatus.INCOMING, phoneNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // AUDIO FIX: Android даёт OFFHOOK при:
                //  1. Исходящий начат (трубка поднята, набираем) → DIALING
                //  2. Входящий принят → ACTIVE
                //  3. Исходящий ПРИНЯТ собеседником → тоже OFFHOOK, нет отдельного события!
                //
                // Проблема: мы ставим DIALING при исходящем и НЕ запускаем аудио.
                // Потом ACTIVE никогда не приходит — аудио не стартует никогда.
                //
                // Решение: всегда сообщаем ACTIVE при OFFHOOK.
                // DIALING не нужен для аудио — браузер покажет "In Call" сразу.
                onCallState(CallStatus.ACTIVE, currentNumber)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val prev = currentNumber
                currentNumber = null
                isOutgoing = false
                onCallState(CallStatus.IDLE, prev)
            }
        }
    }

    fun startListening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val executor = ContextCompat.getMainExecutor(context)
                telephonyManager.registerTelephonyCallback(executor, telephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) { Log.e(TAG, "startListening: ${e.message}") }
    }

    fun stopListening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback)
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) { Log.e(TAG, "stopListening: ${e.message}") }
    }

    fun dial(number: String, subscriptionId: Int? = null): Boolean {
        return try {
            // USSD FIX: Uri.parse("tel:*100#") обрезает '#' как URI fragment.
            // Uri.fromParts("tel", number, null) передаёт номер без URI-encode:
            // схема="tel", path=number (ssp), fragment=null — '#' остаётся как есть.
            val telUri = android.net.Uri.fromParts("tel", number, null)
            val intent = Intent(Intent.ACTION_CALL, telUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // Выбираем PhoneAccount для конкретной SIM
                if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val handle = telecomManager.callCapablePhoneAccounts.firstOrNull { account ->
                        try {
                            val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                                    as SubscriptionManager
                            subMgr.getActiveSubscriptionInfoList()?.any { info ->
                                info.subscriptionId == subscriptionId &&
                                info.simSlotIndex.toString() == account.id
                            } ?: false
                        } catch (e: Exception) { false }
                    }
                    if (handle != null) {
                        putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    }
                }
            }
            currentNumber = number
            isOutgoing = true
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "dial: ${e.message}")
            false
        }
    }

    fun hangup(): Boolean {
        return try {
            // Сначала пробуем через InCallService
            if (GatewayInCallService.reject()) return true
            // Fallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager.endCall()
            } else {
                @Suppress("DEPRECATION")
                val m = telephonyManager.javaClass.getMethod("endCall")
                m.invoke(telephonyManager) as? Boolean ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "hangup: ${e.message}")
            false
        }
    }

    fun acceptCall(): Boolean {
        // Пробуем через InCallService (правильный способ)
        if (GatewayInCallService.answer()) return true
        // Fallback для старых версий
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "acceptCall: ${e.message}")
            false
        }
    }

    fun getSimList(): List<SimInfo> {
        val result = mutableListOf<SimInfo>()
        try {
            val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            subMgr.activeSubscriptionInfoList?.forEach { info ->
                result.add(SimInfo(
                    slotIndex     = info.simSlotIndex,
                    subscriptionId = info.subscriptionId,
                    displayName   = info.displayName?.toString()
                        ?: "SIM ${info.simSlotIndex + 1}",
                    number        = try { info.number?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "getSimList: ${e.message}") }
        return result
    }
}
