package com.flowforge.core.recognition

enum class RecognitionErrorKind {
    NETWORK, NETWORK_TIMEOUT, AUDIO, SERVER, CLIENT, NO_SPEECH, NO_MATCH, BUSY, PERMISSION,
    TOO_MANY_REQUESTS, LANGUAGE_UNSUPPORTED, LANGUAGE_UNAVAILABLE, ENGINE_UNAVAILABLE, INTERRUPTED,
    CONSENT_REQUIRED, NOT_CONFIGURED, UNKNOWN,
}

data class RecognitionError(
    val kind: RecognitionErrorKind,
    val message: String,
    /** Whether simply trying again is likely to work. */
    val retryable: Boolean,
    val suggestion: String,
) {
    /** Errors that only mean "nothing was heard"; not worth an alarming message. */
    val isSilence: Boolean get() = kind == RecognitionErrorKind.NO_SPEECH || kind == RecognitionErrorKind.NO_MATCH
}

/**
 * Maps android.speech.SpeechRecognizer error codes (stable public constants) to user-facing errors.
 * Kept here, independent of Android, so the mapping is unit tested.
 */
object RecognitionErrors {
    // Values of android.speech.SpeechRecognizer.ERROR_* (API 8 - 34).
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    const val ERROR_CANNOT_CHECK_SUPPORT = 14
    const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

    fun fromAndroidCode(code: Int): RecognitionError = when (code) {
        ERROR_NETWORK_TIMEOUT -> RecognitionError(
            RecognitionErrorKind.NETWORK_TIMEOUT, "The speech service timed out on the network.", true,
            "Check your connection, or switch to the on-device engine in Speech engine settings.",
        )
        ERROR_NETWORK, ERROR_SERVER_DISCONNECTED -> RecognitionError(
            RecognitionErrorKind.NETWORK, "The speech service could not reach the network.", true,
            "Check your connection, or switch to the on-device engine.",
        )
        ERROR_AUDIO -> RecognitionError(
            RecognitionErrorKind.AUDIO, "The microphone could not be read.", true,
            "Another app may be using the microphone (a call or recorder). Close it and try again.",
        )
        ERROR_SERVER -> RecognitionError(
            RecognitionErrorKind.SERVER, "The speech service reported an error.", true, "Try again in a moment.",
        )
        ERROR_CLIENT -> RecognitionError(
            RecognitionErrorKind.CLIENT, "The speech service stopped unexpectedly.", true, "Try again.",
        )
        ERROR_SPEECH_TIMEOUT -> RecognitionError(
            RecognitionErrorKind.NO_SPEECH, "No speech was heard.", true, "Tap the mic and start speaking.",
        )
        ERROR_NO_MATCH -> RecognitionError(
            RecognitionErrorKind.NO_MATCH, "Speech was not recognized.", true, "Speak a little closer to the phone.",
        )
        ERROR_RECOGNIZER_BUSY -> RecognitionError(
            RecognitionErrorKind.BUSY, "The speech service is busy.", true,
            "Another app is using speech recognition. Wait a moment and try again.",
        )
        ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError(
            RecognitionErrorKind.PERMISSION, "Microphone permission is missing.", false,
            "Open FlowForge and grant microphone access.",
        )
        ERROR_TOO_MANY_REQUESTS -> RecognitionError(
            RecognitionErrorKind.TOO_MANY_REQUESTS, "The speech service is rate limiting requests.", true,
            "Wait a minute before dictating again.",
        )
        ERROR_LANGUAGE_NOT_SUPPORTED -> RecognitionError(
            RecognitionErrorKind.LANGUAGE_UNSUPPORTED, "This language is not supported by the selected engine.", false,
            "Choose another language or engine in Speech engine settings.",
        )
        ERROR_LANGUAGE_UNAVAILABLE -> RecognitionError(
            RecognitionErrorKind.LANGUAGE_UNAVAILABLE, "The language pack is not downloaded.", false,
            "Download the language pack from Speech engine settings, then try again.",
        )
        ERROR_CANNOT_CHECK_SUPPORT, ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> RecognitionError(
            RecognitionErrorKind.ENGINE_UNAVAILABLE, "The speech service could not report its capabilities.", true,
            "Try again, or choose a different engine.",
        )
        else -> RecognitionError(RecognitionErrorKind.UNKNOWN, "Speech recognition failed (code $code).", true, "Try again.")
    }

    fun interrupted(reason: String) = RecognitionError(
        RecognitionErrorKind.INTERRUPTED, "Dictation was interrupted: $reason.", true,
        "Anything captured before the interruption is kept in the preview.",
    )

    fun engineUnavailable(detail: String, suggestion: String) =
        RecognitionError(RecognitionErrorKind.ENGINE_UNAVAILABLE, detail, false, suggestion)

    val permissionDenied = fromAndroidCode(ERROR_INSUFFICIENT_PERMISSIONS)
}
