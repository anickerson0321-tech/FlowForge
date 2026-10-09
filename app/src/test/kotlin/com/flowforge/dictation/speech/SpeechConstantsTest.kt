package com.flowforge.dictation.speech

import android.speech.SpeechRecognizer
import com.flowforge.core.recognition.RecognitionErrors
import org.junit.Assert.assertEquals
import org.junit.Test

/** Core keeps its own copy of SpeechRecognizer error codes; they must match the Android 16 framework. */
class SpeechConstantsTest {
    @Test
    fun `core error codes match the framework`() {
        assertEquals(SpeechRecognizer.ERROR_NETWORK_TIMEOUT, RecognitionErrors.ERROR_NETWORK_TIMEOUT)
        assertEquals(SpeechRecognizer.ERROR_NETWORK, RecognitionErrors.ERROR_NETWORK)
        assertEquals(SpeechRecognizer.ERROR_AUDIO, RecognitionErrors.ERROR_AUDIO)
        assertEquals(SpeechRecognizer.ERROR_SERVER, RecognitionErrors.ERROR_SERVER)
        assertEquals(SpeechRecognizer.ERROR_CLIENT, RecognitionErrors.ERROR_CLIENT)
        assertEquals(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, RecognitionErrors.ERROR_SPEECH_TIMEOUT)
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, RecognitionErrors.ERROR_NO_MATCH)
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, RecognitionErrors.ERROR_RECOGNIZER_BUSY)
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, RecognitionErrors.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, RecognitionErrors.ERROR_TOO_MANY_REQUESTS)
        assertEquals(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, RecognitionErrors.ERROR_SERVER_DISCONNECTED)
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, RecognitionErrors.ERROR_LANGUAGE_NOT_SUPPORTED)
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, RecognitionErrors.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, RecognitionErrors.ERROR_CANNOT_CHECK_SUPPORT)
        assertEquals(SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS, RecognitionErrors.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS)
    }

    @Test
    fun `language pack status is reported honestly`() {
        val s = EngineAvailability.LanguageSupport(
            installed = listOf("en-US"), pending = listOf("de-DE"), supported = listOf("en-US", "de-DE", "fr-FR"),
        )
        assertEquals("Installed on this phone (required for offline use)", s.status("en-US"))
        assertEquals("Installed on this phone (required for offline use)", s.status("en_us"))
        assertEquals("Downloading…", s.status("de-DE"))
        assertEquals("Available to download", s.status("fr-FR"))
        assertEquals("Not supported on-device", s.status("ja-JP"))
        val failed = EngineAvailability.LanguageSupport(emptyList(), emptyList(), emptyList(), error = "timed out")
        assertEquals("Couldn't check language packs: timed out", failed.status("en-US"))
    }
}
