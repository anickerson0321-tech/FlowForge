package com.flowforge.core.recognition

/** Speech engines FlowForge can drive. */
enum class EngineKind(val label: String, val summary: String) {
    LOCAL_MODEL(
        "FlowForge offline model (recommended)",
        "A high-accuracy speech model (NVIDIA Parakeet or Moonshine) that runs inside FlowForge. Audio never leaves this phone and no internet is needed after the one-time model download.",
    ),
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
    EngineKind.LOCAL_MODEL, EngineKind.ON_DEVICE -> DataExposure.STAYS_ON_DEVICE
    EngineKind.SYSTEM -> DataExposure.SYSTEM_SERVICE
    EngineKind.CLOUD -> DataExposure.CLOUD_PROVIDER
}

/** Whether FlowForge's own offline model can be used right now. */
enum class LocalModelStatus { READY, NOT_DOWNLOADED, LANGUAGE_UNSUPPORTED, DEVICE_UNSUPPORTED, LOAD_FAILED }

data class EngineEnvironment(
    val onDeviceAvailable: Boolean,
    val systemAvailable: Boolean,
    val cloudConfigured: Boolean,
    val cloudConsentValid: Boolean,
    val networkAvailable: Boolean,
    val localModel: LocalModelStatus = LocalModelStatus.NOT_DOWNLOADED,
) {
    val localModelReady: Boolean get() = localModel == LocalModelStatus.READY
}

data class EnginePreference(
    val preferred: EngineKind = EngineKind.LOCAL_MODEL,
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
 *  - FlowForge never silently switches *to* cloud; fallbacks go to engines that keep audio on the
 *    phone first (offline model, then Android on-device), and only then to the system service.
 *  - Falling back to the system service is reported in [EngineSelection.Use.note] because the
 *    system service may use the network.
 */
object EngineSelector {
    fun select(pref: EnginePreference, env: EngineEnvironment): EngineSelection = when (pref.preferred) {
        EngineKind.CLOUD -> selectCloud(pref, env)
        EngineKind.LOCAL_MODEL -> selectLocal(pref, env)
        EngineKind.ON_DEVICE -> when {
            env.onDeviceAvailable -> EngineSelection.Use(EngineKind.ON_DEVICE, isFallback = false)
            pref.allowFallback && env.localModelReady -> EngineSelection.Use(EngineKind.LOCAL_MODEL, isFallback = true)
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
            pref.allowFallback && env.localModelReady -> EngineSelection.Use(EngineKind.LOCAL_MODEL, isFallback = true)
            pref.allowFallback && env.onDeviceAvailable -> EngineSelection.Use(EngineKind.ON_DEVICE, isFallback = true)
            else -> EngineSelection.Unavailable(
                RecognitionErrors.engineUnavailable(
                    "No speech recognition service is installed.",
                    "Install a speech service (for example Speech Recognition & Synthesis from Google) and set it as default in Android settings.",
                ),
            )
        }
    }

    private fun selectLocal(pref: EnginePreference, env: EngineEnvironment): EngineSelection {
        if (env.localModelReady) return EngineSelection.Use(EngineKind.LOCAL_MODEL, isFallback = false)
        val why = when (env.localModel) {
            LocalModelStatus.LANGUAGE_UNSUPPORTED -> "The offline model only understands English."
            LocalModelStatus.DEVICE_UNSUPPORTED -> "The offline model can't run on this phone."
            LocalModelStatus.LOAD_FAILED -> "The offline model couldn't be loaded."
            else -> "The offline model isn't downloaded yet."
        }
        val fix = when (env.localModel) {
            LocalModelStatus.LANGUAGE_UNSUPPORTED -> "Set the dictation language to English, or choose another engine in Speech settings."
            LocalModelStatus.DEVICE_UNSUPPORTED -> "Choose another engine in Speech settings."
            LocalModelStatus.LOAD_FAILED -> "Delete and re-download it in Speech settings, try the smaller model, or choose another engine."
            else -> "Download it in Speech settings (one time, needs Wi-Fi or data)."
        }
        return when {
            pref.allowFallback && env.onDeviceAvailable -> EngineSelection.Use(
                EngineKind.ON_DEVICE, isFallback = true, note = "$why Using Android's on-device recognizer for now.",
            )
            pref.allowFallback && env.systemAvailable -> EngineSelection.Use(
                EngineKind.SYSTEM, isFallback = true,
                note = "$why Using your phone's speech service for now. It may send audio to its provider.",
            )
            else -> EngineSelection.Unavailable(RecognitionErrors.engineUnavailable(why, fix))
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
        if (pref.allowFallback && env.localModelReady) {
            return EngineSelection.Use(EngineKind.LOCAL_MODEL, isFallback = true, note = "${problem.message} Using the offline model instead.")
        }
        if (pref.allowFallback && env.onDeviceAvailable) {
            return EngineSelection.Use(EngineKind.ON_DEVICE, isFallback = true, note = "${problem.message} Using on-device recognition instead.")
        }
        return EngineSelection.Unavailable(problem)
    }
}
