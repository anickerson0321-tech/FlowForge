package com.flowforge.dictation.speech

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.flowforge.core.privacy.CloudPurpose
import com.flowforge.core.privacy.ConsentPolicy
import com.flowforge.core.recognition.EngineEnvironment
import com.flowforge.dictation.data.SettingsStore
import java.util.Locale

/** What the device can do right now. Cheap to call; query just before each dictation. */
object EngineAvailability {
    fun systemAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun onDeviceAvailable(context: Context): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun networkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun environment(context: Context, settings: SettingsStore): EngineEnvironment {
        val url = settings.cloudBaseUrl
        return EngineEnvironment(
            onDeviceAvailable = onDeviceAvailable(context),
            systemAvailable = systemAvailable(context),
            cloudConfigured = settings.hasCloudApiKey && ConsentPolicy.hostOf(url) != null,
            cloudConsentValid = ConsentPolicy.isValid(settings.privacy.recognitionConsent, CloudPurpose.RECOGNITION, url),
            networkAvailable = networkAvailable(context),
        )
    }

    data class LanguageSupport(
        val installed: List<String>,
        val pending: List<String>,
        val supported: List<String>,
        val error: String? = null,
    ) {
        fun status(tag: String): String {
            val want = normalize(tag)
            return when {
                error != null -> "Couldn't check language packs: $error"
                installed.any { normalize(it) == want } -> "Installed — works offline"
                pending.any { normalize(it) == want } -> "Downloading…"
                supported.any { normalize(it) == want } -> "Available to download"
                else -> "Not supported on-device"
            }
        }

        private fun normalize(t: String) = t.replace('_', '-').lowercase(Locale.ROOT)
    }

    fun effectiveLanguage(tag: String): String = tag.ifBlank { Locale.getDefault().toLanguageTag() }

    private fun languageIntent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, effectiveLanguage(tag))
    }

    /** Asks the on-device recognizer which language packs are installed. Result on the main thread. */
    fun checkOnDeviceLanguages(context: Context, tag: String, callback: (LanguageSupport) -> Unit) {
        if (!onDeviceAvailable(context)) {
            callback(LanguageSupport(emptyList(), emptyList(), emptyList(), "on-device recognition is not available"))
            return
        }
        val main = Handler(Looper.getMainLooper())
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        var done = false
        fun finish(r: LanguageSupport) {
            if (done) return
            done = true
            callback(r)
            recognizer.destroy()
        }
        recognizer.checkRecognitionSupport(
            languageIntent(tag), context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    finish(
                        LanguageSupport(
                            support.installedOnDeviceLanguages, support.pendingOnDeviceLanguages,
                            support.supportedOnDeviceLanguages,
                        ),
                    )
                }

                override fun onError(error: Int) {
                    finish(LanguageSupport(emptyList(), emptyList(), emptyList(), "error $error"))
                }
            },
        )
        main.postDelayed({ finish(LanguageSupport(emptyList(), emptyList(), emptyList(), "timed out")) }, 5000)
    }

    /** Asks the on-device recognizer to download the language pack (the system shows its own UI). */
    fun downloadOnDeviceLanguage(context: Context, tag: String): Boolean {
        if (!onDeviceAvailable(context)) return false
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return try {
            recognizer.triggerModelDownload(languageIntent(tag))
            true
        } catch (e: RuntimeException) {
            false
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({ recognizer.destroy() }, 2000)
        }
    }
}
