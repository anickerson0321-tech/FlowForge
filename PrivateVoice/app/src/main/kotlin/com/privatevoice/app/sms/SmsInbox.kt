package com.privatevoice.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.privatevoice.app.model.SenderAssignment
import com.privatevoice.app.model.SenderRef
import com.privatevoice.app.voices.PhoneNumbers
import com.privatevoice.app.voices.VoiceSelectionResolver

/** One SMS from the inbox, held in memory only while it is on screen or being read. */
class SmsItem(
    val id: Long,
    val address: String,
    body: String?,
    val dateMillis: Long,
    val read: Boolean,
) {
    @Volatile var body: String? = body
        private set

    fun wipe() { body = null }

    override fun toString() = "SmsItem(id=$id)" // never includes content
}

/** Names and voices for SMS senders, using only the user's own voice assignments (no contacts access). */
object SmsSenders {
    const val PACKAGE = "sms"

    /** The display name the announcer uses: the assignment label if the user named this number. */
    fun sender(address: String, assignments: List<SenderAssignment>, resolver: VoiceSelectionResolver): SenderRef {
        val key = PhoneNumbers.toKey(address)
        val probe = SenderRef(address, key, PACKAGE)
        val match = resolver.match(probe, assignments)
        val name = match?.label ?: PhoneNumbers.spokenFallbackName(address)
        return SenderRef(name, key, PACKAGE)
    }

    /** Label for lists: the user's name for the number, or the number itself. */
    fun listLabel(address: String, assignments: List<SenderAssignment>, resolver: VoiceSelectionResolver): String =
        resolver.match(SenderRef(address, PhoneNumbers.toKey(address), PACKAGE), assignments)?.label ?: address
}

/**
 * Optional, opt-in access to the SMS inbox (READ_SMS). Queried only when the user opens
 * the Text Messages screen or asks to read; results are never cached or written anywhere.
 * RCS chat messages are stored privately by the messaging app and are not available here.
 */
class SmsInbox(private val context: Context) {

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Most recent inbox messages, newest first. */
    fun recent(limit: Int = 40, unreadOnly: Boolean = false): List<SmsItem> {
        if (!hasPermission()) return emptyList()
        val out = ArrayList<SmsItem>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ,
        )
        val selection = if (unreadOnly) "${Telephony.Sms.READ} = 0" else null
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, selection, null, "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                while (c.moveToNext() && out.size < limit) {
                    out += SmsItem(
                        id = c.getLong(iId),
                        address = c.getString(iAddr) ?: "",
                        body = c.getString(iBody),
                        dateMillis = c.getLong(iDate),
                        read = c.getInt(iRead) != 0,
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        return out
    }
}
