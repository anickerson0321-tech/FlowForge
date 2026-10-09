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
    ) = EngineEnvironment(onDevice, system, cloudConfigured, consent, network)

    private fun use(kind: EngineKind, fallback: Boolean = false) = EngineSelector.select(EnginePreference(kind), env()).let {
        it as EngineSelection.Use
    }

    @Test
    fun `preferred engine is used when available`() {
        assertEquals(EngineKind.ON_DEVICE, use(EngineKind.ON_DEVICE).kind)
        assertEquals(EngineKind.SYSTEM, use(EngineKind.SYSTEM).kind)
        assertEquals(EngineKind.CLOUD, use(EngineKind.CLOUD).kind)
    }

    @Test
    fun `on-device unavailable falls back to system with a disclosure note`() {
        val s = EngineSelector.select(EnginePreference(EngineKind.ON_DEVICE), env(onDevice = false)) as EngineSelection.Use
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
        assertEquals(EngineKind.ON_DEVICE, s.kind)
        assertEquals(DataExposure.STAYS_ON_DEVICE, s.exposure)
        val none = EngineSelector.select(EnginePreference(EngineKind.CLOUD), env(network = false, onDevice = false))
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
        for (kind in listOf(EngineKind.ON_DEVICE, EngineKind.SYSTEM)) {
            val s = EngineSelector.select(EnginePreference(kind), env(onDevice = false, system = false))
            assertTrue(s is EngineSelection.Unavailable)
        }
    }

    @Test
    fun `exposure labels`() {
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
