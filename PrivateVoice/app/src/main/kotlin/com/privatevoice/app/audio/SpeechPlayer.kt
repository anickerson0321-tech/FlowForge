package com.privatevoice.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.privatevoice.app.model.EngineKind
import com.privatevoice.app.reader.RoutePolicy
import com.privatevoice.app.settings.AppSettings
import com.privatevoice.app.settings.RouteRestriction
import com.privatevoice.app.speech.KokoroEngine
import com.privatevoice.app.speech.SystemTtsEngine
import com.privatevoice.app.speech.kokoro.KokoroSynthesizer
import com.privatevoice.app.voices.KokoroVoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** One piece of speech in one voice. */
data class Segment(val text: String, val voiceId: String)

/** A unit of playback: usually announcement + message for one message. Text lives only in memory. */
class PlaybackJob(
    val id: Long,
    val label: String,
    val messageId: Long?,
    val segments: List<Segment>,
    val route: RouteRestriction,
    val automatic: Boolean,
    /** Previously rendered neural audio per segment (Repeat), or null to synthesize. */
    val cachedAudio: List<List<FloatArray>?>? = null,
)

sealed class PlaybackState {
    object Idle : PlaybackState()
    data class Preparing(val label: String) : PlaybackState()
    data class Speaking(val label: String, val engine: EngineKind) : PlaybackState()
    data class Paused(val label: String) : PlaybackState()
}

/** Things worth telling the user about (never contains message text). */
data class PlaybackEvent(val message: String)

interface AudioPlaybackController {
    val state: StateFlow<PlaybackState>
    val events: SharedFlow<PlaybackEvent>
    /** Plays [job]; when [interrupt] is true, current playback and the queue are discarded first. */
    fun play(job: PlaybackJob, interrupt: Boolean)
    fun stop()
    fun pause()
    fun resume()
    fun repeatLast(): Boolean
    fun hasRepeat(): Boolean
    /** Drops cached audio/text kept for Repeat. */
    fun clearCache()
    fun clearCacheFor(messageId: Long)
    fun release()
}

/**
 * Plays speech with audio focus, route checks, a bounded queue, pause/resume,
 * and an in-memory cache of the last rendered audio for Repeat.
 * Generated audio is never written to storage.
 */
class SpeechPlayer(
    context: Context,
    private val kokoro: KokoroEngine,
    private val system: SystemTtsEngine,
    private val routes: AudioRouteMonitor,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    /**
     * Called before audio focus is requested. Android 15+ only grants focus to the
     * top app or an app running a foreground service, so this starts the short-lived
     * playback service when the app is in the background. Returns true when focus
     * can be expected to work.
     */
    private val enterForeground: suspend () -> Boolean = { true },
) : AudioPlaybackController {

    /** Per-job playback state, so a stopped job can never touch the next job's track. */
    private inner class Session(val job: PlaybackJob) {
        val stopped = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        @Volatile var track: AudioTrack? = null
        @Volatile var engine: EngineKind = EngineKind.KOKORO
        @Volatile var focus: AudioFocusRequest? = null
        var framesWritten = 0L

        fun halt() {
            stopped.set(true)
            paused.set(false)
            track?.let { runCatching { it.pause(); it.flush() } }
            if (engine == EngineKind.SYSTEM) system.stop()
        }
    }

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PlaybackEvent> = _events

    private val lock = Any()
    private val queue = ArrayDeque<PlaybackJob>()
    private var runner: Job? = null
    /** Only the runner of the current generation may take jobs from the queue. */
    private var generation = 0
    @Volatile private var active: Session? = null
    @Volatile private var pausedByFocus = false

    // Repeat cache (memory only)
    private var lastJob: PlaybackJob? = null
    private var lastAudio: List<MutableList<FloatArray>?> = emptyList()

    init {
        routes.onRouteChanged = { route ->
            val s = active
            if (s != null && !RoutePolicy.allows(route, s.job.route, settings().approvedBluetoothDevices)) {
                stop(); emit("Stopped: audio moved to an output that is not allowed for private messages")
            }
        }
        routes.onOutputLost = {
            if (settings().stopOnDisconnect && active != null) { stop(); emit("Stopped: audio output disconnected") }
        }
        routes.onCallStateChanged = { inCall ->
            if (inCall && settings().pauseDuringCalls && active != null) { stop(); emit("Stopped for a phone call") }
        }
    }

    private fun emit(msg: String) { _events.tryEmit(PlaybackEvent(msg)) }

    override fun play(job: PlaybackJob, interrupt: Boolean) {
        synchronized(lock) {
            if (interrupt) {
                queue.clear()
                active?.halt()
                runner?.cancel()
                runner = null
                generation++
            }
            if (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.addLast(job)
            if (runner?.isActive != true) {
                val gen = ++generation
                runner = scope.launch(Dispatchers.Default) { drain(gen) }
            }
        }
    }

    private suspend fun drain(gen: Int) {
        while (true) {
            val job = synchronized(lock) { if (gen == generation) queue.removeFirstOrNull() else null } ?: break
            val session = Session(job)
            active = session
            try {
                playJob(session)
            } finally {
                releaseTrack(session)
                abandonFocus(session)
                if (active === session) active = null
            }
        }
        synchronized(lock) { if (gen == generation && queue.isEmpty() && active == null) _state.value = PlaybackState.Idle }
    }

    private suspend fun playJob(session: Session) {
        val job = session.job
        val s = settings()
        if (!RoutePolicy.allows(routes.currentRoute(), job.route, s.approvedBluetoothDevices)) {
            emit("Not read aloud: current audio output isn't allowed (${RoutePolicy.describe(job.route)})")
            return
        }
        if (s.pauseDuringCalls && routes.inCall.value) { emit("Not read aloud during a call"); return }
        _state.value = PlaybackState.Preparing(job.label)
        val foreground = enterForeground()
        if (!requestFocus(session)) {
            // Reading still proceeds (it is short); music just isn't ducked.
            emit(
                if (foreground) "Couldn't get audio focus — other audio may keep playing"
                else "Reading without audio ducking: Android blocked background playback controls. " +
                    "Allow PrivateVoice to run unrestricted in battery settings to fix this.",
            )
        }
        val rendered = MutableList<MutableList<FloatArray>?>(job.segments.size) { null }
        job.segments.forEachIndexed { index, seg ->
            if (session.stopped.get()) return@forEachIndexed
            rendered[index] = speakSegment(session, seg, s, job.cachedAudio?.getOrNull(index))
        }
        if (!session.stopped.get()) waitForDrain(session)
        if (!session.stopped.get()) {
            synchronized(lock) {
                if (job.cachedAudio == null) { wipeCacheLocked(); lastAudio = rendered }
                lastJob = job
            }
        } else if (job.cachedAudio == null) {
            rendered.forEach { list -> list?.forEach { it.fill(0f) } }
        }
    }

    /** Speaks one segment; returns rendered PCM chunks when Kokoro was used (for Repeat). */
    private suspend fun speakSegment(session: Session, seg: Segment, s: AppSettings, cached: List<FloatArray>?): MutableList<FloatArray>? {
        val label = session.job.label
        val code = KokoroVoices.code(seg.voiceId)
        if (cached != null) {
            session.engine = EngineKind.KOKORO
            setSpeaking(session, label)
            val t = ensureTrack(session, s.volume)
            for (chunk in cached) if (!write(session, t, chunk)) break
            return null
        }
        val useKokoro = code != null && !s.preferSystemEngine && withContext(Dispatchers.Default) { kokoro.ensureLoaded() }
        if (!useKokoro) {
            if (code != null && !s.preferSystemEngine) emit("Neural voice unavailable — using the system offline voice")
            session.engine = EngineKind.SYSTEM
            setSpeaking(session, label)
            val voiceName = seg.voiceId.removePrefix("system:").takeIf { seg.voiceId.startsWith("system:") }
            system.speak(seg.text, voiceName, s.speechRate, s.volume, ATTRIBUTES)
            return null
        }
        session.engine = EngineKind.KOKORO
        val out = ArrayList<FloatArray>()
        val t = ensureTrack(session, s.volume)
        val channel = Channel<FloatArray>(capacity = 4)
        val producer = scope.launch(Dispatchers.Default) {
            try {
                kokoro.synthesize(seg.text, code!!, s.speechRate, isActive = { !session.stopped.get() }) { chunk ->
                    // Fails fast once the consumer has gone away; never blocks forever.
                    if (channel.trySendBlocking(chunk).isFailure) session.stopped.set(true)
                }
            } catch (e: Throwable) {
                if (!session.stopped.get()) emit("Voice engine error — message not fully read")
            } finally {
                channel.close()
            }
        }
        try {
            for (chunk in channel) {
                setSpeaking(session, label)
                out += chunk
                if (!write(session, t, chunk)) break
            }
        } finally {
            channel.cancel()
            producer.join()
        }
        return out
    }

    private fun setSpeaking(session: Session, label: String) {
        if (active === session && !session.paused.get()) _state.value = PlaybackState.Speaking(label, session.engine)
    }

    private fun ensureTrack(session: Session, volume: Float): AudioTrack {
        session.track?.let { it.setVolume(volume); return it }
        val rate = KokoroSynthesizer.SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 2))
            .build()
        t.setVolume(volume)
        t.play()
        session.track = t
        session.framesWritten = 0
        return t
    }

    /** Writes in ~100 ms slices so stop/pause stay responsive. Returns false when stopped. */
    private suspend fun write(session: Session, t: AudioTrack, pcm: FloatArray): Boolean {
        var off = 0
        val slice = KokoroSynthesizer.SAMPLE_RATE / 10
        while (off < pcm.size) {
            if (session.stopped.get()) return false
            while (session.paused.get() && !session.stopped.get()) delay(50)
            val n = minOf(slice, pcm.size - off)
            val w = t.write(pcm, off, n, AudioTrack.WRITE_BLOCKING)
            if (w < 0) return false
            off += w
            session.framesWritten += w
        }
        return true
    }

    private suspend fun waitForDrain(session: Session) {
        val t = session.track ?: return
        var waited = 0
        while (!session.stopped.get() && waited < 30_000) {
            val head = try { t.playbackHeadPosition.toLong() and 0xffffffffL } catch (e: IllegalStateException) { break }
            if (head >= session.framesWritten) break
            delay(30)
            if (!session.paused.get()) waited += 30
        }
    }

    private fun releaseTrack(session: Session) {
        val t = session.track ?: return
        session.track = null
        runCatching { t.pause(); t.flush() }
        runCatching { t.release() }
    }

    private fun requestFocus(session: Session): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener { change ->
                if (active !== session) return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { pausedByFocus = true; pause() }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; resume() }
                }
            }
            .build()
        session.focus = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus(session: Session) {
        val req = session.focus ?: return
        session.focus = null
        audioManager.abandonAudioFocusRequest(req)
        if (active === session || active == null) pausedByFocus = false
    }

    override fun stop() {
        synchronized(lock) {
            queue.clear()
            active?.halt()
            runner?.cancel()
            runner = null
            generation++
        }
        system.stop()
        _state.value = PlaybackState.Idle
    }

    override fun pause() {
        val s = active ?: return
        if (s.engine == EngineKind.SYSTEM) { stop(); return } // system TTS cannot pause mid-utterance
        s.paused.set(true)
        s.track?.let { runCatching { it.pause() } }
        _state.value = PlaybackState.Paused(s.job.label)
    }

    override fun resume() {
        val s = active ?: return
        if (!s.paused.get()) return
        s.paused.set(false)
        s.track?.let { runCatching { it.play() } }
        _state.value = PlaybackState.Speaking(s.job.label, s.engine)
    }

    override fun hasRepeat(): Boolean = synchronized(lock) { lastJob != null }

    override fun repeatLast(): Boolean {
        val job = synchronized(lock) {
            val last = lastJob ?: return false
            PlaybackJob(last.id, last.label, last.messageId, last.segments, last.route, false, lastAudio.map { it?.toList() })
        }
        play(job, interrupt = true)
        return true
    }

    private fun wipeCacheLocked() {
        lastAudio.forEach { list -> list?.forEach { it.fill(0f) } }
        lastAudio = emptyList()
        lastJob = null
    }

    override fun clearCache() = synchronized(lock) { wipeCacheLocked() }

    override fun clearCacheFor(messageId: Long) = synchronized(lock) { if (lastJob?.messageId == messageId) wipeCacheLocked() }

    override fun release() {
        stop()
        clearCache()
    }

    companion object {
        const val MAX_QUEUE = 5
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
