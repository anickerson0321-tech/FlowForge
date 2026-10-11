package com.privatevoice.app.speech

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.privatevoice.app.model.EngineKind
import com.privatevoice.app.model.VoiceCategory
import com.privatevoice.app.model.VoiceInfo
import com.privatevoice.app.speech.kokoro.KokoroSynthesizer
import com.privatevoice.app.speech.kokoro.TextNormalizer
import com.privatevoice.app.voices.KokoroVoices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Engine lifecycle as shown in the UI. */
sealed class EngineState {
    object NotLoaded : EngineState()
    object Loading : EngineState()
    data class Ready(val loadMillis: Long) : EngineState()
    data class Failed(val reason: String) : EngineState()
}

/** Common surface of the two offline engines. */
interface SpeechEngine {
    val kind: EngineKind
    val state: StateFlow<EngineState>
    fun voices(): List<VoiceInfo>
    fun release()
}

/** Kokoro assets read directly from the APK. The model is memory-mapped (stored uncompressed). */
class ApkKokoroAssets(private val assets: AssetManager) : KokoroSynthesizer.Assets {
    override fun model(): ByteBuffer {
        assets.openFd(MODEL_PATH).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
    override fun voice(name: String): InputStream = assets.open("kokoro/voices/$name.bin")
    override fun lexicon(): InputStream = assets.open("kokoro/lexicon_us.tsv", AssetManager.ACCESS_STREAMING)

    companion object { const val MODEL_PATH = "kokoro/kokoro-v1.0-q8.onnx" }
}

/**
 * Kokoro-82M neural voices, fully on-device via ONNX Runtime.
 * The model is loaded on demand and unloaded after a period of inactivity so it
 * holds no memory or CPU while idle.
 */
class KokoroEngine(context: Context) : SpeechEngine {
    override val kind = EngineKind.KOKORO
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    val synthesizer = KokoroSynthesizer(ApkKokoroAssets(context.applicationContext.assets), threads)
    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    override val state: StateFlow<EngineState> = _state
    @Volatile var lastUsedElapsed: Long = 0L
        private set
    @Volatile var lastRealTimeFactor: Double = 0.0
        private set

    override fun voices(): List<VoiceInfo> = KokoroVoices.all

    /** Loads the model if needed. Blocking; call from a background thread. */
    fun ensureLoaded(): Boolean {
        if (synthesizer.isLoaded) return true
        if (_state.value is EngineState.Failed) return false
        _state.value = EngineState.Loading
        val t0 = SystemClock.elapsedRealtime()
        return try {
            synthesizer.load()
            _state.value = EngineState.Ready(SystemClock.elapsedRealtime() - t0)
            lastUsedElapsed = SystemClock.elapsedRealtime()
            true
        } catch (t: Throwable) {
            // OutOfMemoryError / UnsatisfiedLinkError / OrtException: fall back to the system engine.
            synthesizer.unloadModel()
            _state.value = EngineState.Failed(t.javaClass.simpleName + ": " + (t.message ?: "model could not be loaded").take(160))
            false
        }
    }

    /** Lets the user retry after a failure (e.g. after freeing memory). */
    fun resetFailure() { if (_state.value is EngineState.Failed) _state.value = EngineState.NotLoaded }

    fun configureText(describeEmoji: Boolean, expandShorthand: Boolean) {
        synthesizer.normalizer.emojiMode = if (describeEmoji) TextNormalizer.EmojiMode.DESCRIBE else TextNormalizer.EmojiMode.SKIP
        synthesizer.normalizer.expandShorthand = expandShorthand
    }

    /** Streams PCM chunks for [text]. Throws if the model is unavailable. */
    fun synthesize(text: String, voiceCode: String, speed: Float, isActive: () -> Boolean, onChunk: (FloatArray) -> Unit) {
        check(ensureLoaded()) { "Kokoro engine unavailable" }
        val t0 = SystemClock.elapsedRealtime()
        var samples = 0L
        synthesizer.synthesize(text, voiceCode, speed, isActive) { samples += it.size; onChunk(it) }
        val elapsed = SystemClock.elapsedRealtime() - t0
        if (samples > 0) lastRealTimeFactor = elapsed / 1000.0 / (samples / KokoroSynthesizer.SAMPLE_RATE.toDouble())
        lastUsedElapsed = SystemClock.elapsedRealtime()
    }

    /** Frees the ONNX session (called when idle or under memory pressure). */
    fun unloadIfIdle(idleMillis: Long): Boolean {
        if (!synthesizer.isLoaded) return false
        if (SystemClock.elapsedRealtime() - lastUsedElapsed < idleMillis) return false
        synthesizer.unloadModel()
        _state.value = EngineState.NotLoaded
        return true
    }

    override fun release() {
        synthesizer.unloadModel()
        if (_state.value !is EngineState.Failed) _state.value = EngineState.NotLoaded
    }
}

/**
 * Android's installed TextToSpeech engine, restricted to voices that do not
 * require a network connection. Used as a fallback when Kokoro cannot run.
 */
class SystemTtsEngine(context: Context) : SpeechEngine {
    override val kind = EngineKind.SYSTEM
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    override val state: StateFlow<EngineState> = _state
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = CompletableDeferred<Boolean>()
    private val pending = HashMap<String, CompletableDeferred<Boolean>>()
    private val ids = AtomicInteger()
    @Volatile private var cachedVoices: List<VoiceInfo> = emptyList()

    @Synchronized
    fun start() {
        if (tts != null) return
        _state.value = EngineState.Loading
        val t0 = SystemClock.elapsedRealtime()
        ready = CompletableDeferred()
        val d = ready
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                _state.value = EngineState.Ready(SystemClock.elapsedRealtime() - t0)
                refreshVoices()
                d.complete(true)
            } else {
                _state.value = EngineState.Failed("No offline text-to-speech engine is available")
                d.complete(false)
            }
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId, true)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId, false)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
            })
        }
    }

    private fun finish(id: String?, ok: Boolean) {
        val d = synchronized(pending) { pending.remove(id) }
        d?.complete(ok)
    }

    private fun refreshVoices() {
        val engine = tts ?: return
        val voices: Set<Voice> = try { engine.voices ?: emptySet() } catch (e: Exception) { emptySet() }
        cachedVoices = voices
            .filter { !it.isNetworkConnectionRequired && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) }
            .filter { it.locale.language == Locale.ENGLISH.language }
            .sortedWith(compareBy({ it.locale.country != "US" }, { -it.quality }, { it.name }))
            .map { v ->
                VoiceInfo(
                    id = "system:${v.name}",
                    displayName = v.name.substringAfterLast('-').ifBlank { v.name },
                    engine = EngineKind.SYSTEM,
                    presentation = VoiceCategory.DEFAULT,
                    accent = v.locale.displayName,
                    description = "System voice (offline) · quality ${v.quality}",
                )
            }
    }

    override fun voices(): List<VoiceInfo> = cachedVoices

    /** Speaks [text] and suspends until done. Returns false on error or interruption. */
    suspend fun speak(text: String, voiceName: String?, rate: Float, volume: Float, attributes: AudioAttributes): Boolean {
        start()
        if (withTimeoutOrNull(5000) { ready.await() } != true) return false
        val engine = tts ?: return false
        engine.setAudioAttributes(attributes)
        engine.setSpeechRate(rate)
        val voice = voiceName?.let { n -> try { engine.voices?.firstOrNull { it.name == n && !it.isNetworkConnectionRequired } } catch (e: Exception) { null } }
            ?: try { engine.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == "en" } } catch (e: Exception) { null }
        if (voice != null) engine.voice = voice
        val id = "pv-" + ids.incrementAndGet()
        val done = CompletableDeferred<Boolean>()
        synchronized(pending) { pending[id] = done }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) {
            synchronized(pending) { pending.remove(id) }
            return false
        }
        return done.await()
    }

    fun stop() {
        tts?.stop()
        val all = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        all.forEach { it.complete(false) }
    }

    @Synchronized
    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        _state.value = EngineState.NotLoaded
    }
}
