package com.privatevoice.app.messaging

import com.privatevoice.app.model.IncomingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Summary of pending messages for UI; contains no message text. */
data class PendingState(
    /** Messages not yet read aloud. */
    val count: Int = 0,
    /** All messages still held (heard + not heard). */
    val total: Int = 0,
    val latestId: Long? = null,
    val latestSender: String? = null,
    val latestAppLabel: String? = null,
    val latestContentAvailable: Boolean = false,
    val latestExpiresInMillis: Long = 0,
)

/**
 * Short-lived holding area for messages waiting to be read.
 * Implementations must keep everything in memory only.
 */
interface TemporaryMessageRepository {
    val state: StateFlow<PendingState>
    fun add(message: IncomingMessage)
    fun latest(): IncomingMessage?
    fun get(id: Long): IncomingMessage?
    fun all(): List<IncomingMessage>
    fun remove(id: Long)
    fun removeByNotificationKey(key: String)
    /** Newest message not yet read aloud. */
    fun latestUnheard(): IncomingMessage?
    /** Marks a message as read aloud; it stays available to hear again until it expires. */
    fun markHeard(id: Long)
    /** Drops expired messages; returns how many were removed. */
    fun expire(): Int
    fun clear()
    var ttlMillis: Long
}

/**
 * Bounded, expiring, in-memory message store. Removed messages are wiped
 * (text reference dropped). Not thread-confined: all methods synchronize.
 */
class InMemoryMessageRepository(
    ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long,
) : TemporaryMessageRepository {

    private val lock = Any()
    private val items = ArrayList<IncomingMessage>()
    private val _state = MutableStateFlow(PendingState())
    override val state: StateFlow<PendingState> = _state

    @Volatile
    override var ttlMillis: Long = ttlMillis
        set(value) { field = value.coerceIn(MIN_TTL_MILLIS, MAX_TTL_MILLIS); expire() }

    override fun add(message: IncomingMessage) {
        synchronized(lock) {
            dropExpiredLocked()
            items.add(message)
            while (items.size > capacity) items.removeAt(0).wipe()
            publishLocked()
        }
    }

    override fun latest(): IncomingMessage? = synchronized(lock) { refreshLocked(); items.lastOrNull() }

    override fun latestUnheard(): IncomingMessage? = synchronized(lock) { refreshLocked(); items.lastOrNull { !it.heard } }

    override fun markHeard(id: Long) {
        synchronized(lock) {
            items.firstOrNull { it.id == id }?.heard = true
            publishLocked()
        }
    }

    override fun get(id: Long): IncomingMessage? = synchronized(lock) { refreshLocked(); items.firstOrNull { it.id == id } }

    override fun all(): List<IncomingMessage> = synchronized(lock) { refreshLocked(); items.toList() }

    /** Drops expired items and republishes state if anything changed. */
    private fun refreshLocked() { if (dropExpiredLocked() > 0) publishLocked() }

    override fun remove(id: Long) {
        synchronized(lock) {
            val it = items.iterator()
            while (it.hasNext()) { val m = it.next(); if (m.id == id) { m.wipe(); it.remove() } }
            publishLocked()
        }
    }

    override fun removeByNotificationKey(key: String) {
        synchronized(lock) {
            val it = items.iterator()
            while (it.hasNext()) { val m = it.next(); if (m.notificationKey == key) { m.wipe(); it.remove() } }
            publishLocked()
        }
    }

    override fun expire(): Int = synchronized(lock) { val n = dropExpiredLocked(); if (n > 0) publishLocked(); n }

    override fun clear() {
        synchronized(lock) {
            items.forEach { it.wipe() }
            items.clear()
            publishLocked()
        }
    }

    private fun dropExpiredLocked(): Int {
        val now = clock()
        var removed = 0
        val it = items.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (now - m.receivedAtElapsed >= ttlMillis) { m.wipe(); it.remove(); removed++ }
        }
        return removed
    }

    private fun publishLocked() {
        val unheard = items.filter { !it.heard }
        val last = unheard.lastOrNull()
        _state.value = PendingState(
            count = unheard.size,
            total = items.size,
            latestId = last?.id,
            latestSender = last?.sender?.displayName,
            latestAppLabel = last?.appLabel,
            latestContentAvailable = last?.contentAvailable == true,
            latestExpiresInMillis = last?.let { (ttlMillis - (clock() - it.receivedAtElapsed)).coerceAtLeast(0) } ?: 0,
        )
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 10 * 60_000L
        const val MIN_TTL_MILLIS = 60_000L
        const val MAX_TTL_MILLIS = 24 * 60 * 60_000L
        const val DEFAULT_CAPACITY = 50
    }
}

/**
 * Decides whether a parsed message is new, using only notification metadata:
 * the notification key and per-message timestamps (MessagingStyle), or the
 * notification post time. It never stores message text or content hashes.
 * Bounded LRU over notification keys.
 */
class NewMessageTracker(private val maxKeys: Int = 256) {
    private val lastSeen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxKeys
    }

    /**
     * Returns true if a message with [timestamp] under notification [key] has not
     * been seen before, and records it. Messages at or before the newest
     * timestamp already seen for that key are treated as re-posts/updates.
     */
    @Synchronized
    fun isNew(key: String, timestamp: Long): Boolean {
        val prev = lastSeen[key]
        if (prev != null && timestamp <= prev) return false
        lastSeen[key] = timestamp
        return true
    }

    /** Marks all current messages as seen without reading them (e.g. on listener connect). */
    @Synchronized
    fun prime(key: String, timestamp: Long) {
        val prev = lastSeen[key]
        if (prev == null || timestamp > prev) lastSeen[key] = timestamp
    }

    @Synchronized
    fun forget(key: String) { lastSeen.remove(key) }

    @Synchronized
    fun clear() = lastSeen.clear()

    @Synchronized
    fun size() = lastSeen.size
}
