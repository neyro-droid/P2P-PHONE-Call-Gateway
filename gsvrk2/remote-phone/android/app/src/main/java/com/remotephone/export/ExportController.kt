package com.remotephone.export

import android.content.Context
import com.remotephone.contacts.ContactsController
import com.remotephone.sms.SmsController
import com.remotephone.telecom.CallLogController
import org.json.JSONArray
import org.json.JSONObject

/**
 * Экспорт контактов, SMS и истории звонков в JSON или CSV.
 * Вызывается через WebSocket команды: export_contacts, export_sms, export_call_log, export_all
 */
class ExportController(private val context: Context) {

    private val contacts = ContactsController(context)
    private val sms      = SmsController(context)
    private val callLog  = CallLogController(context)

    // ── JSON export ───────────────────────────────────────────────────────────

    fun exportContactsJson(): JSONArray = try { contacts.getContacts() } catch (e: Exception) { JSONArray() }

    fun exportSmsJson(): JSONArray = try { sms.getSmsHistory() } catch (e: Exception) { JSONArray() }

    fun exportCallLogJson(): JSONArray = try { callLog.getCallHistory() } catch (e: Exception) { JSONArray() }

    fun exportAll(): JSONObject = JSONObject().apply {
        put("contacts", exportContactsJson())
        put("sms",      exportSmsJson())
        put("call_log", exportCallLogJson())
        put("exported_at", System.currentTimeMillis())
    }

    // ── CSV export ────────────────────────────────────────────────────────────

    fun exportContactsCsv(): String {
        val sb = StringBuilder("id,name,numbers\n")
        val arr = exportContactsJson()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val nums = obj.getJSONArray("numbers")
            val numStr = (0 until nums.length()).joinToString(";") { nums.getString(it) }
            sb.append("${obj.optLong("id")},${csvEsc(obj.optString("name"))},${csvEsc(numStr)}\n")
        }
        return sb.toString()
    }

    fun exportSmsCsv(): String {
        val sb = StringBuilder("address,date,type,body\n")
        val arr = exportSmsJson()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            sb.append("${csvEsc(obj.optString("address"))},")
            sb.append("${obj.optLong("date")},")
            sb.append("${csvEsc(obj.optString("type"))},")
            sb.append("${csvEsc(obj.optString("body"))}\n")
        }
        return sb.toString()
    }

    fun exportCallLogCsv(): String {
        val sb = StringBuilder("number,name,date,duration,type\n")
        val arr = exportCallLogJson()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            sb.append("${csvEsc(obj.optString("number"))},")
            sb.append("${csvEsc(obj.optString("name"))},")
            sb.append("${obj.optLong("date")},")
            sb.append("${obj.optLong("duration")},")
            sb.append("${csvEsc(obj.optString("type"))}\n")
        }
        return sb.toString()
    }

    // BUG FIX 4: добавлен \r — RFC 4180: bare \r трактуется как разрыв строки
    private fun csvEsc(s: String): String {
        if (!s.contains(',') && !s.contains('"') && !s.contains('\n') && !s.contains('\r')) return s
        return "\"${s.replace("\"", "\"\"").replace("\r", "").replace("\n", " ")}\""
    }
}
