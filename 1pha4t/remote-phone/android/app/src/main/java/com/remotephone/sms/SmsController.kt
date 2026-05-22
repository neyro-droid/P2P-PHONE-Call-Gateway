package com.remotephone.sms

import android.content.Context
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

class SmsController(private val context: Context) {

    fun getSmsHistory(limit: Int = 100): JSONArray {
        val result = JSONArray()
        queryBox("content://sms/inbox", limit, result, 1)
        queryBox("content://sms/sent",  limit, result, 2)
        return result
    }

    private fun queryBox(uri: String, limit: Int, out: JSONArray, type: Int) {
        val cursor = try {
            // MINOR-13 аналог: убираем LIMIT из sortOrder
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf("_id", "address", "body", "date"),
                null, null, "date DESC"
            )
        } catch (e: Exception) { null }
        cursor?.use { c ->
            var count = 0
            while (c.moveToNext() && count < limit) {
                out.put(JSONObject().apply {
                    put("id",      c.getLong(0))
                    put("address", c.getString(1) ?: "")
                    put("body",    c.getString(2) ?: "")
                    put("date",    c.getLong(3))
                    put("type",    type)
                })
                count++
            }
        }
    }

    fun sendSms(to: String, body: String, subscriptionId: Int? = null): Boolean {
        return try {
            val manager = getSmsManager(subscriptionId)
            val parts = manager.divideMessage(body)
            if (parts.size == 1)
                manager.sendTextMessage(to, null, body, null, null)
            else
                manager.sendMultipartTextMessage(to, null, parts, null, null)
            true
        } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(subscriptionId: Int?): SmsManager {
        // MEDIUM-7 fix: правильный API для каждой версии Android
        if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            return SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
    }
}
