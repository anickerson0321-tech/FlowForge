package com.privatevoice.app.robo

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import com.privatevoice.app.sms.SmsInbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Fake SMS provider: rows (id, address, body, date, read). Applies the "read = 0" selection like the real one. */
class FakeSmsProvider : ContentProvider() {
    val rows = mutableListOf(
        arrayOf<Any?>(1L, "+15551234567", "oldest", 1_000L, 1),
        arrayOf<Any?>(2L, "+15550001111", "middle unread", 2_000L, 0),
        arrayOf<Any?>(3L, "(555) 123-4567", "newest", 3_000L, 1),
    )
    var lastUri: Uri? = null
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        lastUri = uri
        val cols = projection ?: arrayOf("_id", "address", "body", "date", "read")
        val c = MatrixCursor(cols)
        var list = rows.toList()
        if (selection?.replace(" ", "") == "read=0") list = list.filter { it[4] == 0 }
        if (sort?.startsWith("date DESC") == true) list = list.sortedByDescending { it[3] as Long }
        val idx = mapOf("_id" to 0, "address" to 1, "body" to 2, "date" to 3, "read" to 4)
        list.forEach { r -> c.addRow(cols.map { r[idx.getValue(it)] }.toTypedArray()) }
        return c
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsInboxTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun provider(): FakeSmsProvider {
        val p = FakeSmsProvider()
        p.onCreate()
        ShadowContentResolver.registerProviderInternal("sms", p)
        return p
    }

    @Test fun noPermissionMeansNoAccess() {
        provider()
        assertTrue(SmsInbox(app).recent().isEmpty())
    }

    @Test fun readsInboxNewestFirstIncludingAlreadyReadMessages() {
        val p = provider()
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        val items = SmsInbox(app).recent()
        assertEquals(Telephony.Sms.Inbox.CONTENT_URI, p.lastUri)
        assertEquals(listOf("newest", "middle unread", "oldest"), items.map { it.body })
        assertEquals(listOf(true, false, true), items.map { it.read })
        assertEquals("(555) 123-4567", items.first().address)
    }

    @Test fun unreadOnlyAndLimit() {
        provider()
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        assertEquals(listOf("middle unread"), SmsInbox(app).recent(unreadOnly = true).map { it.body })
        assertEquals(2, SmsInbox(app).recent(limit = 2).size)
    }
}
