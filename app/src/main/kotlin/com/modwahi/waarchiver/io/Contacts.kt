package com.modwahi.waarchiver.io

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** أسماء جهات الاتصال في التلفون: {"967771234567": "أحمد"} بنفس توحيد الأرقام في الأداة. */
object PhoneContacts {
    fun granted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** يحوّل الرقم كما هو مكتوب في جهات الاتصال إلى صيغة دولية بالأرقام فقط. */
    fun international(raw: String, countryCode: String): String {
        val t = raw.trim()
        val d = t.filter { Character.isDigit(it) }
        val cc = countryCode.filter { Character.isDigit(it) }
        return when {
            d.isEmpty() -> ""
            t.startsWith("+") -> d
            d.startsWith("00") -> d.substring(2)
            d.startsWith("0") && cc.isNotEmpty() -> cc + d.substring(1)
            d.length <= 9 && cc.isNotEmpty() && !d.startsWith(cc) -> cc + d
            else -> d
        }
    }

    fun read(context: Context, countryCode: String): Map<String, String> {
        if (!granted(context)) return emptyMap()
        val out = HashMap<String, String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val num = international(c.getString(0) ?: continue, countryCode)
                val name = c.getString(1)?.trim().orEmpty()
                if (num.isNotEmpty() && name.isNotEmpty()) out.putIfAbsent(num, name)
            }
        }
        return out
    }
}
