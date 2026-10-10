package com.flowforge.core.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSelectionTest {
    private fun env(
        onDevice: Boolean = true,
        system: Boolean = true,
        cloudConfigured: Boolean = true,
        consent: Boolean = true,
        network: Boolean = true,
        local: LocalModelStatus = LocalModelStatus.READY,
    ) = EngineEnvironment(onDevice, system, cloudConfigured, consent, network, local)

    private fun use(kind: EngineKind, fallback: Boolean = false) = EngineSelector.select(EnginePreference(kind), env()).let {
        it as EngineSelection.Use
    }

    @Test
    fun `preferred engine is used when available`() {
        assertEquals(EngineKind.LOCAL_MODEL, use(EngineKind.LOCAL_MODEL).kind)
        assertEquals(EngineKind.ON_DEVICE, use(EngineKind.ON_DEVICE).kind)
        assertEquals(EngineKind.SYSTEM, use(EngineKind.SYSTEM).kind)
        assertEquals(EngineKind.CLOUD, use(EngineKind.CLOUD).kind)
    }

    @Test
    fun `on-device unavailable falls back to system with a disclosure note`() {
        val s = EngineSelector.select(EnginePreference(EngineKind.ON_DEVICE), env(onDevice = false, local = LocalModelStatus.NOT_DOWNLOADED)) as EngineSelection.Use
        assertEquals(EngineKind.SYSTEM, s.kind)
        assertTrue(s.isFallback)
        assertTrue(s.note!!.contains("may send audio"))
    }

    @Test
    fun `fallback disabled reports unavailable`() {
        val s = EngineSelector.select(EnginePreference(EngineKind.ON_DEVICE, allowFallback = false), env(onDevice = false))
        assertTrue(s is EngineSelection.Unavailable)
    }

    @Test
    fun `network loss with cloud engine falls back to on-device only`() {
        val s = EngineSelector.select(EnginePreference(EngineKind.CLOUD), env(network = false)) as EngineSelection.Use
        assertEquals(EngineKind.LOCAL_MODEL, s.kind)
        assertEquals(DataExposure.STAYS_ON_DEVICE, s.exposure)
        val noModel = EngineSelector.select(EnginePreference(EngineKind.CLOUD), env(network = false, local = LocalModelStatus.NOT_DOWNLOADED)) as EngineSelection.Use
        assertEquals(EngineKind.ON_DEVICE, noModel.kind)
        val none = EngineSelector.select(EnginePreference(EngineKind.CLOUD), env(network = false, onDevice = false, local = LocalModelStatus.NOT_DOWNLOADED))
        assertEquals(RecognitionErrorKind.NETWORK, (none as EngineSelection.Unavailable).error.kind)
    }

    @Test
    fun `cloud requires consent and configuration`() {
        val noConsent = EngineSelector.select(EnginePreference(EngineKind.CLOUD, allowFallback = false), env(consent = false))
        assertEquals(RecognitionErrorKind.CONSENT_REQUIRED, (noConsent as EngineSelection.Unavailable).error.kind)
        val noConfig = EngineSelector.select(EnginePreference(EngineKind.CLOUD, allowFallback = false), env(cloudConfigured = false))
        assertEquals(RecognitionErrorKind.NOT_CONFIGURED, (noConfig as EngineSelection.Unavailable).error.kind)
    }

    @Test
    fun `never falls back to cloud`() {
        for (kind in listOf(EngineKind.LOCAL_MODEL, EngineKind.ON_DEVICE, EngineKind.SYSTEM)) {
            val s = EngineSelector.select(EnginePreference(kind), env(onDevice = false, system = false, local = LocalModelStatus.NOT_DOWNLOADED))
            assertTrue(s is EngineSelection.Unavailable)
        }
    }

    @Test
    fun `offline model is the default and falls back to engines that keep audio local first`() {
        assertEquals(EngineKind.LOCAL_MODEL, EnginePreference().preferred)
        val missing = EngineSelector.select(EnginePreference(), env(local = LocalModelStatus.NOT_DOWNLOADED)) as EngineSelection.Use
        assertEquals(EngineKind.ON_DEVICE, missing.kind)
        assertTrue(missing.isFallback)
        assertTrue(missing.note!!.contains("isn't downloaded"))
        val sysOnly = EngineSelector.select(EnginePreference(), env(onDevice = false, local = LocalModelStatus.NOT_DOWNLOADED)) as EngineSelection.Use
        assertEquals(EngineKind.SYSTEM, sysOnly.kind)
        assertTrue(sysOnly.note!!.contains("may send audio"))
        val english = EngineSelector.select(EnginePreference(allowFallback = false), env(local = LocalModelStatus.LANGUAGE_UNSUPPORTED))
        assertTrue((english as EngineSelection.Unavailable).error.message.contains("English"))
        // Android engines that are unavailable fall back to a downloaded offline model, quietly.
        val od = EngineSelector.select(EnginePreference(EngineKind.ON_DEVICE), env(onDevice = false)) as EngineSelection.Use
        assertEquals(EngineKind.LOCAL_MODEL, od.kind)
        assertEquals(null, od.note)
    }

    @Test
    fun `exposure labels`() {
        assertEquals(DataExposure.STAYS_ON_DEVICE, EngineKind.LOCAL_MODEL.exposure())
        assertEquals(DataExposure.STAYS_ON_DEVICE, EngineKind.ON_DEVICE.exposure())
        assertEquals(DataExposure.SYSTEM_SERVICE, EngineKind.SYSTEM.exposure())
        assertEquals(DataExposure.CLOUD_PROVIDER, EngineKind.CLOUD.exposure())
    }

    @Test
    fun `android error codes map to actionable messages`() {
        val codes = 1..15
        for (c in codes) {
            val e = RecognitionErrors.fromAndroidCode(c)
            assertTrue("code $c", e.message.isNotBlank() && e.suggestion.isNotBlank())
            assertTrue("code $c mapped to UNKNOWN", e.kind != RecognitionErrorKind.UNKNOWN)
        }
        assertEquals(RecognitionErrorKind.PERMISSION, RecognitionErrors.fromAndroidCode(9).kind)
        assertEquals(false, RecognitionErrors.fromAndroidCode(9).retryable)
        assertEquals(RecognitionErrorKind.UNKNOWN, RecognitionErrors.fromAndroidCode(99).kind)
    }
}
