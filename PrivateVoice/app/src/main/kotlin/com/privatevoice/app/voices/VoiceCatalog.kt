package com.privatevoice.app.voices

import com.privatevoice.app.model.EngineKind
import com.privatevoice.app.model.VoiceCategory
import com.privatevoice.app.model.VoiceInfo

/**
 * Bundled Kokoro-82M voices. Presentation labels follow the model authors'
 * naming (a/b = American/British English, f/m = feminine/masculine voice).
 * Users pick voices for people themselves; nothing is inferred about senders.
 */
object KokoroVoices {
    private data class Spec(val code: String, val name: String, val note: String)

    private val specs = listOf(
        Spec("af_heart", "Heart", "Warm and natural (recommended)"),
        Spec("af_bella", "Bella", "Bright and expressive"),
        Spec("af_nicole", "Nicole", "Soft, close-mic"),
        Spec("af_sarah", "Sarah", "Clear and friendly"),
        Spec("af_sky", "Sky", "Light and airy"),
        Spec("af_nova", "Nova", "Crisp and modern"),
        Spec("af_alloy", "Alloy", "Even and neutral"),
        Spec("af_aoede", "Aoede", "Gentle"),
        Spec("af_jessica", "Jessica", "Casual"),
        Spec("af_kore", "Kore", "Calm"),
        Spec("af_river", "River", "Relaxed"),
        Spec("am_michael", "Michael", "Natural and steady (recommended)"),
        Spec("am_adam", "Adam", "Deeper"),
        Spec("am_onyx", "Onyx", "Deep and smooth"),
        Spec("am_fenrir", "Fenrir", "Strong"),
        Spec("am_eric", "Eric", "Professional"),
        Spec("am_liam", "Liam", "Youthful"),
        Spec("am_echo", "Echo", "Resonant"),
        Spec("am_puck", "Puck", "Playful"),
        Spec("am_santa", "Santa", "Novelty, jolly"),
        Spec("bf_emma", "Emma", "British, polished"),
        Spec("bf_isabella", "Isabella", "British, warm"),
        Spec("bf_alice", "Alice", "British, crisp"),
        Spec("bf_lily", "Lily", "British, soft"),
        Spec("bm_george", "George", "British, professional"),
        Spec("bm_fable", "Fable", "British, storyteller"),
        Spec("bm_lewis", "Lewis", "British, deep"),
        Spec("bm_daniel", "Daniel", "British, calm"),
    )

    val all: List<VoiceInfo> = specs.map { s ->
        VoiceInfo(
            id = "kokoro:${s.code}",
            displayName = s.name,
            engine = EngineKind.KOKORO,
            presentation = if (s.code[1] == 'm') VoiceCategory.MASCULINE else VoiceCategory.FEMININE,
            accent = if (s.code[0] == 'b') "British English" else "American English",
            description = s.note,
        )
    }

    fun code(voiceId: String): String? = voiceId.removePrefix("kokoro:").takeIf { voiceId.startsWith("kokoro:") && specs.any { s -> s.code == it } }

    fun find(voiceId: String): VoiceInfo? = all.firstOrNull { it.id == voiceId }
}
