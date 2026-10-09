package com.flowforge.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PrivacyTest {
    @Test
    fun `consent is bound to provider host and purpose`() {
        val c = ConsentPolicy.grant(CloudPurpose.RECOGNITION, "https://api.example.com/v1", 1000)!!
        assertTrue(ConsentPolicy.isValid(c, CloudPurpose.RECOGNITION, "https://api.example.com/v1/"))
        assertTrue(ConsentPolicy.isValid(c, CloudPurpose.RECOGNITION, "https://API.example.com:443/v2"))
        assertFalse(ConsentPolicy.isValid(c, CloudPurpose.RECOGNITION, "https://evil.example.net/v1"))
        assertFalse(ConsentPolicy.isValid(c, CloudPurpose.AI_CLEANUP, "https://api.example.com/v1"))
        assertFalse(ConsentPolicy.isValid(null, CloudPurpose.RECOGNITION, "https://api.example.com/v1"))
    }

    @Test
    fun `old notice versions require fresh consent`() {
        val old = ConsentRecord(CloudPurpose.RECOGNITION, "api.example.com", 0, ConsentPolicy.NOTICE_VERSION - 1)
        assertFalse(ConsentPolicy.isValid(old, CloudPurpose.RECOGNITION, "https://api.example.com"))
    }

    @Test
    fun `plain http is only allowed for servers on this device`() {
        assertNull(ConsentPolicy.hostOf("http://api.example.com/v1"))
        assertEquals("localhost", ConsentPolicy.hostOf("http://localhost:8080/v1"))
        assertEquals("127.0.0.1", ConsentPolicy.hostOf("http://127.0.0.1:8080/v1"))
        assertNull(ConsentPolicy.hostOf("http://192.168.1.20:9000"))
        assertNull(ConsentPolicy.hostOf("http://10.example.com/v1"))
        assertEquals("192.168.1.20", ConsentPolicy.hostOf("https://192.168.1.20:9000"))
        assertNull(ConsentPolicy.hostOf("ftp://api.example.com"))
        assertNull(ConsentPolicy.hostOf("not a url"))
        assertNull(ConsentPolicy.grant(CloudPurpose.RECOGNITION, "http://api.example.com", 0))
    }

    @Test
    fun `notice names the provider`() {
        assertTrue(ConsentPolicy.noticeText(CloudPurpose.RECOGNITION, "api.example.com").contains("api.example.com"))
    }

    @Test
    fun `retention expiry`() {
        val now = TimeUnit.DAYS.toMillis(100)
        assertTrue(RetentionPolicy.isExpired(now - TimeUnit.DAYS.toMillis(8), now, 7))
        assertFalse(RetentionPolicy.isExpired(now - TimeUnit.DAYS.toMillis(6), now, 7))
    }
}
