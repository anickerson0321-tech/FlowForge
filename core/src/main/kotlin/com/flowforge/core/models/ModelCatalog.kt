package com.flowforge.core.models

/** Network architecture of an offline speech model, which decides how the recognizer is configured. */
enum class ModelArch { NEMO_TRANSDUCER, MOONSHINE }

/**
 * An offline speech-to-text model that FlowForge can download and run on the phone with
 * sherpa-onnx. Archives come from the sherpa-onnx project's GitHub releases and are verified
 * against a pinned SHA-256 before anything is used.
 */
data class SpeechModel(
    val id: String,
    val label: String,
    val summary: String,
    val url: String,
    val sha256: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val arch: ModelArch,
    /** Base names of the archive members FlowForge keeps; everything else is skipped. */
    val files: List<String>,
    val license: String,
) {
    val downloadMb: Int get() = (downloadBytes / 1_000_000).toInt()
    val installedMb: Int get() = (installedBytes / 1_000_000).toInt()
}

object ModelCatalog {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    /**
     * NVIDIA Parakeet TDT 0.6B v2 (English), int8. Highest accuracy of the tested models, with
     * punctuation and capitalization. Measured in development: ~0.08x real time on 4 desktop CPU
     * threads; phones are slower.
     */
    val PARAKEET_V2 = SpeechModel(
        id = "parakeet-tdt-0.6b-v2-int8",
        label = "Best accuracy (Parakeet 0.6B, English)",
        summary = "Most accurate. 482 MB download, 661 MB on the phone. Punctuates and capitalizes.",
        url = BASE + "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        sha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
        downloadBytes = 482_468_385,
        installedBytes = 661_190_513,
        arch = ModelArch.NEMO_TRANSDUCER,
        files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        license = "NVIDIA Parakeet TDT 0.6B v2 — CC-BY-4.0",
    )

    /** Useful Sensors Moonshine Base (English), int8. Smaller and faster, a little less accurate. */
    val MOONSHINE_BASE = SpeechModel(
        id = "moonshine-base-en-int8",
        label = "Smaller download (Moonshine Base, English)",
        summary = "Good accuracy. 251 MB download, 287 MB on the phone. Punctuates and capitalizes.",
        url = BASE + "sherpa-onnx-moonshine-base-en-int8.tar.bz2",
        sha256 = "21870cecaa2e44e4e2bf63e02d1072bed183ccd10284871353bd9d24dad14e5e",
        downloadBytes = 250_807_309,
        installedBytes = 286_929_760,
        arch = ModelArch.MOONSHINE,
        files = listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt"),
        license = "Moonshine (Useful Sensors) — MIT",
    )

    val all: List<SpeechModel> = listOf(PARAKEET_V2, MOONSHINE_BASE)
    val default: SpeechModel = PARAKEET_V2

    fun byId(id: String?): SpeechModel? = all.firstOrNull { it.id == id }
}
