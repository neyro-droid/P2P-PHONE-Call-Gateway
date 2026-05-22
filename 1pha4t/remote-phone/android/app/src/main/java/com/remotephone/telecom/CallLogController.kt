package com.remotephone.telecom

import android.content.Context
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject

class CallLogController(private val context: Context) {

    fun getCallHistory(limit: Int = 50): JSONArray {
        val result = JSONArray()
        val cursor = try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null, null,
                // MINOR-13 fix: убрать LIMIT из sortOrder — он не документирован
                "${CallLog.Calls.DATE} DESC"
            )
        } catch (e: Exception) { null }

        cursor?.use { c ->
            var count = 0
            while (c.moveToNext() && count < limit) {
                result.put(JSONObject().apply {
                    put("id", c.getLong(0))
                    put("number", c.getString(1) ?: "")
                    put("name", c.getString(2) ?: "")
                    put("type", c.getInt(3))
                    put("date", c.getLong(4))
                    put("duration", c.getLong(5))
                })
                count++
            }
        }
        return result
    }

    /** Zero-Trace: удаляет запись о звонке сразу после завершения */
    fun deleteLastCallRecord(number: String) {
        try {
            context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.NUMBER} = ? AND ${CallLog.Calls.DATE} > ?",
                arrayOf(number, (System.currentTimeMillis() - 60_000L).toString())
            )
        } catch (e: Exception) { /* ignore */ }
    }
}
