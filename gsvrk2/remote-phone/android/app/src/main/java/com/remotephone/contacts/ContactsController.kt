package com.remotephone.contacts

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

class ContactsController(private val context: Context) {

    fun getContacts(): JSONArray {
        val result = JSONArray()
        val map = LinkedHashMap<Long, JSONObject>()
        val cursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
        } catch (e: Exception) { null }

        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: ""
                val number = c.getString(2) ?: ""
                if (!map.containsKey(id)) {
                    map[id] = JSONObject().apply {
                        put("id", id)
                        put("name", name)
                        put("numbers", JSONArray())
                    }
                }
                map[id]!!.getJSONArray("numbers").put(number)
            }
        }
        map.values.forEach { result.put(it) }
        return result
    }
}
