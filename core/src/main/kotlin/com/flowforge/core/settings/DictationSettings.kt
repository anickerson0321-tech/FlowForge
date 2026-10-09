package com.flowforge.core.settings

import com.flowforge.core.cleanup.CleanupMode
import com.flowforge.core.cleanup.CleanupOptions
import com.flowforge.core.cleanup.InsertionContext
import com.flowforge.core.recognition.EngineKind
import com.flowforge.core.recognition.EnginePreference

/** How dictated text reaches the other app's field (bubble mode). */
enum class InsertionPreference(val label: String, val summary: String) {
    AUTOMATIC("Automatic", "Edits the field directly when it is safe, otherwise pastes."),
    PASTE("Always paste", "Uses the clipboard and the field's paste action. Keeps rich formatting intact."),
    PREVIEW("Preview first", "Shows the text in the bubble; you choose Insert or Copy."),
}

data class DictationSettings(
    val cleanupMode: CleanupMode = CleanupMode.LIGHT,
    val spokenPunctuation: Boolean = true,
    val autoEndPunctuation: Boolean = true,
    val formatLists: Boolean = true,
    val smartSpacing: Boolean = true,
    val engine: EngineKind = EngineKind.ON_DEVICE,
    val allowEngineFallback: Boolean = true,
    /** BCP-47 tag such as "en-US"; empty means the device language. */
    val languageTag: String = "",
    /** Keep listening through pauses until the user taps stop. */
    val continuous: Boolean = true,
    val maxRecordingSeconds: Int = 300,
    val insertion: InsertionPreference = InsertionPreference.AUTOMATIC,
    val haptics: Boolean = true,
    val aiCleanupEnabled: Boolean = false,
) {
    val enginePreference: EnginePreference get() = EnginePreference(engine, allowEngineFallback)

    fun cleanupOptions(context: InsertionContext = InsertionContext.EMPTY) = CleanupOptions(
        mode = cleanupMode,
        spokenPunctuation = spokenPunctuation,
        autoEndPunctuation = autoEndPunctuation,
        formatLists = formatLists,
        context = context,
    )

    companion object {
        val MAX_RECORDING_CHOICES = listOf(60, 120, 300, 600)
    }
}
