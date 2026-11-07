package com.expensetracker.app.sources.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsReader @Inject constructor(@ApplicationContext private val context: Context) {

    data class Sms(val id: Long, val address: String, val body: String, val date: Long)

    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasReceivePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun count(since: Long): Int {
        if (!hasReadPermission()) return 0
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID), "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), null,
        )?.use { return it.count }
        return 0
    }

    /** Streams inbox messages newer than [since] in chronological order. */
    suspend fun forEachSince(since: Long, block: suspend (Sms) -> Unit) {
        if (!hasReadPermission()) return
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
        ) ?: return
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressCol = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val body = c.getString(bodyCol) ?: continue
                block(Sms(c.getLong(idCol), c.getString(addressCol).orEmpty(), body, c.getLong(dateCol)))
            }
        }
    }
}
