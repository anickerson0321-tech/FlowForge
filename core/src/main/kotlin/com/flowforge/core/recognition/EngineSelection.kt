package com.flowforge.core.recognition

/** Speech engines FlowForge can drive. */
enum class EngineKind(val label: String, val summary: String) {
    ON_DEVICE(
        "On-device (Android)",
        "Android's on-device recognizer. Audio is processed on this phone; no internet needed once the language pack is installed.",
    ),
    SYSTEM(
        "System speech service",
        "Your phone's default speech service (for example Google or Samsung). It may send audio to its provider's servers, depending on that service's settings.",
    ),
    CLOUD(
        "Cloud (your own provider)",
        "An OpenAI-compatible transcription endpoint you configure with your own API key. Audio is uploaded to that provider.",
    ),
}

/** Where dictated audio goes. Shown to the user before and during dictation. */
enum class DataExposure(val description: String) {
    STAYS_ON_DEVICE("Audio stays on this device."),
    SYSTEM_SERVICE("Audio is handled by your phone's speech service, which may use its provider's servers."),
    CLOUD_PROVIDER("Audio is uploaded to the cloud provider you configured."),
}

fun EngineKind.exposure(): DataExposure = when (this) {
    EngineKind.ON_DEVICE -> DataExposure.STAYS_ON_DEVICE
    EngineKind.SYSTEM -> DataExposure.SYSTEM_SERVICE
    EngineKind.CLOUD -> DataExposure.CLOUD_PROVIDER
}

data class EngineEnvironment(
    val onDeviceAvailable: Boolean,
    val systemAvailable: Boolean,
    val cloudConfigured: Boolean,
    val cloudConsentValid: Boolean,
    val networkAvailable: Boolean,
)

data class EnginePreference(
    val preferred: EngineKind = EngineKind.ON_DEVICE,
    /** Use another local/system engine when the preferred one is unavailable. Never falls back to cloud. */
    val allowFallback: Boolean = true,
)

sealed class EngineSelection {
    data class Use(val kind: EngineKind, val isFallback: Boolean, val note: String? = null) : EngineSelection() {
        val exposure: DataExposure get() = kind.exposure()
    }

    data class Unavailable(val error: RecognitionError) : EngineSelection()
}

/**
 * Chooses an engine for a dictation. Rules (unit tested):
 *  - Cloud is used only if explicitly preferred, configured, consented to and online.
 *  - FlowForge never silently switches *to* cloud; fallbacks go cloud → on-device → system.
 *  - Falling back from on-device to the system service is reported in [EngineSelection.Use.note]
 *    because the system service may use the network.
 */
object EngineSelector {
    fun select(pref: EnginePreference, env: EngineEnvironment): EngineSelection = when (pref.preferred) {
        EngineKind.CLOUD -> selectCloud(pref, env)
        EngineKind.ON_DEVICE -> when {
            env.onDeviceAvailable -> EngineSelection.Use(EngineKind.ON_DEVICE, isFallback = false)
            pref.allowFallback && env.systemAvailable -> EngineSelection.Use(
                EngineKind.SYSTEM, isFallback = true,
                note = "On-device recognition isn't available, so your phone's speech service is used. It may send audio to its provider.",
            )
            else -> EngineSelection.Unavailable(
                RecognitionErrors.engineUnavailable(
                    "On-device speech recognition isn't available on this phone.",
                    "Install or update a speech service with offline support (e.g. Speech Recognition & Synthesis from Google), download your language, or choose another engine.",
                ),
            )
        }
        EngineKind.SYSTEM -> when {
            env.systemAvailable -> EngineSelection.Use(EngineKind.SYSTEM, isFallback = false)
            pref.allowFallback && env.onDeviceAvailable -> EngineSelection.Use(EngineKind.ON_DEVICE, isFallback = true)
            else -> EngineSelection.Unavailable(
                RecognitionErrors.engineUnavailable(
                    "No speech recognition service is installed.",
                    "Install a speech service (for example Speech Recognition & Synthesis from Google) and set it as default in Android settings.",
                ),
            )
        }
    }

    private fun selectCloud(pref: EnginePreference, env: EngineEnvironment): EngineSelection {
        val problem: RecognitionError? = when {
            !env.cloudConfigured -> RecognitionError(
                RecognitionErrorKind.NOT_CONFIGURED, "The cloud engine isn't set up.", false,
                "Add an endpoint and API key in Speech engine settings, or choose the on-device engine.",
            )
            !env.cloudConsentValid -> RecognitionError(
                RecognitionErrorKind.CONSENT_REQUIRED, "Cloud recognition needs your permission to upload audio.", false,
                "Review and accept the cloud processing notice in Privacy settings.",
            )
            !env.networkAvailable -> RecognitionError(
                RecognitionErrorKind.NETWORK, "No internet connection for the cloud engine.", true,
                "Reconnect, or use the on-device engine.",
            )
            else -> null
        }
        if (problem == null) return EngineSelection.Use(EngineKind.CLOUD, isFallback = false)
        if (pref.allowFallback && env.onDeviceAvailable) {
            return EngineSelection.Use(EngineKind.ON_DEVICE, isFallback = true, note = "${problem.message} Using on-device recognition instead.")
        }
        return EngineSelection.Unavailable(problem)
    }
}
