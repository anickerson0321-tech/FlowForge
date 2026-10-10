package com.flowforge.dictation.speech

import com.flowforge.core.audio.Pcm
import com.flowforge.core.models.ModelArch
import com.flowforge.core.models.SpeechModel
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * Runs a downloaded [SpeechModel] with sherpa-onnx (onnxruntime) entirely on this device.
 * No Android dependencies, so the same code is exercised by JVM integration tests on Linux.
 * Not thread-safe: use from one thread (FlowForge uses a dedicated recognizer thread).
 */
class LocalTranscriber private constructor(private val recognizer: OfflineRecognizer, val model: SpeechModel) {

    /** Transcribes 16 kHz mono samples in [-1, 1]. */
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, Pcm.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    companion object {
        /**
         * Loads [model] from [dir]. Takes seconds and hundreds of MB of memory; call off the main
         * thread. Throws if the files are missing or the native library can't be loaded.
         */
        fun load(model: SpeechModel, dir: File, threads: Int): LocalTranscriber {
            fun path(name: String): String {
                val f = File(dir, name)
                require(f.isFile) { "Model file missing: $name" }
                return f.path
            }
            val modelConfig = OfflineModelConfig(numThreads = threads, debug = false, provider = "cpu", tokens = path("tokens.txt"))
            when (model.arch) {
                ModelArch.NEMO_TRANSDUCER -> {
                    modelConfig.transducer = OfflineTransducerModelConfig(
                        encoder = path("encoder.int8.onnx"), decoder = path("decoder.int8.onnx"), joiner = path("joiner.int8.onnx"),
                    )
                    modelConfig.modelType = "nemo_transducer"
                }
                ModelArch.MOONSHINE -> modelConfig.moonshine = OfflineMoonshineModelConfig(
                    preprocessor = path("preprocess.onnx"), encoder = path("encode.int8.onnx"),
                    uncachedDecoder = path("uncached_decode.int8.onnx"), cachedDecoder = path("cached_decode.int8.onnx"),
                )
            }
            val config = OfflineRecognizerConfig(modelConfig = modelConfig, decodingMethod = "greedy_search")
            // A null AssetManager makes sherpa-onnx read the model from regular files.
            return LocalTranscriber(OfflineRecognizer(null, config), model)
        }
    }
}
