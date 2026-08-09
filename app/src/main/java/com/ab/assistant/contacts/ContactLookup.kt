package com.ab.assistant.contacts

import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer
import java.util.Locale

data class ContactMatch(val displayName: String, val phoneNumber: String)

class ContactLookup(private val context: Context) {
    fun find(name: String, limit: Int = 5): List<ContactMatch> {
        val query = name.trim()
        if (query.isBlank()) return emptyList()
        val matches = linkedSetOf<ContactMatch>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val likeQuery = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'",
            arrayOf("%$likeQuery%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && matches.size < limit) {
                val displayName = cursor.getString(nameIndex).orEmpty()
                if (normalize(displayName).contains(normalize(query))) {
                    matches += ContactMatch(displayName, cursor.getString(phoneIndex).orEmpty())
                }
            }
        }
        return matches.toList()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace('đ', 'd')
}
