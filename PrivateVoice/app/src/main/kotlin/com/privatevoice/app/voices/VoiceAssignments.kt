package com.privatevoice.app.voices

import com.privatevoice.app.model.SenderAssignment
import com.privatevoice.app.model.SenderRef
import com.privatevoice.app.model.VoiceCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Base64

/** Persistent sender -> voice preferences (the one deliberate exception to "store nothing"). */
interface VoiceAssignmentRepository {
    val assignments: StateFlow<List<SenderAssignment>>
    fun upsert(assignment: SenderAssignment)
    fun remove(id: String)
    fun deleteAll()
}

/** Symmetric authenticated encryption for the assignment file. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
    /** Destroys the key so any leftover ciphertext becomes unreadable. */
    fun destroyKey() {}
}

/** Line-based codec: each field Base64url-encoded, tab separated. No JSON/serialization libraries needed. */
object AssignmentCodec {
    private const val VERSION = "pv-assign-1"
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    private fun e(s: String?) = if (s == null) "-" else "=" + enc.encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun d(s: String): String? = if (s == "-") null else String(dec.decode(s.removePrefix("=")), Charsets.UTF_8)

    fun encode(list: List<SenderAssignment>): ByteArray {
        val sb = StringBuilder(VERSION).append('\n')
        for (a in list) {
            sb.append(listOf(e(a.id), e(a.label), e(a.matchKey), e(a.matchName), e(a.appPackage), e(a.voiceId), e(a.category.name), e(a.updatedAt.toString())).joinToString("\t"))
            sb.append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<SenderAssignment> {
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        require(lines.firstOrNull() == VERSION) { "Unknown assignment format" }
        return lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 8) return@mapNotNull null
            SenderAssignment(
                id = d(f[0]) ?: return@mapNotNull null,
                label = d(f[1]) ?: "",
                matchKey = d(f[2]),
                matchName = d(f[3]) ?: "",
                appPackage = d(f[4]),
                voiceId = d(f[5]),
                category = runCatching { VoiceCategory.valueOf(d(f[6]) ?: "DEFAULT") }.getOrDefault(VoiceCategory.DEFAULT),
                updatedAt = d(f[7])?.toLongOrNull() ?: 0,
            )
        }
    }
}

/**
 * Encrypted file store (AES-GCM via [SecretBox]) in a directory excluded from backups.
 */
class EncryptedFileAssignmentRepository(
    private val file: File,
    private val box: SecretBox,
) : VoiceAssignmentRepository {
    private val _assignments = MutableStateFlow(load())
    override val assignments: StateFlow<List<SenderAssignment>> = _assignments

    private fun load(): List<SenderAssignment> = try {
        if (file.exists()) AssignmentCodec.decode(box.open(file.readBytes())) else emptyList()
    } catch (e: Exception) {
        // Unreadable (e.g. key invalidated): start fresh rather than crash. Never log contents.
        emptyList()
    }

    @Synchronized
    override fun upsert(assignment: SenderAssignment) {
        val next = _assignments.value.filter { it.id != assignment.id } + assignment
        persist(next)
    }

    @Synchronized
    override fun remove(id: String) = persist(_assignments.value.filter { it.id != id })

    @Synchronized
    override fun deleteAll() {
        _assignments.value = emptyList()
        if (file.exists()) {
            // Overwrite before deleting (best effort on flash storage), then destroy the key.
            runCatching { file.writeBytes(ByteArray(file.length().toInt())) }
            file.delete()
        }
        box.destroyKey()
    }

    private fun persist(list: List<SenderAssignment>) {
        val sorted = list.sortedBy { it.label.lowercase() }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(box.seal(AssignmentCodec.encode(sorted)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _assignments.value = sorted
    }
}

/** Result of voice resolution for one message. */
data class VoiceChoice(
    val messageVoice: String,
    val announcerVoice: String,
    val source: Source,
    val assignment: SenderAssignment?,
) {
    enum class Source { SENDER_VOICE, SENDER_CATEGORY, DEFAULT }
}

data class VoiceDefaults(
    val defaultVoice: String,
    val announcerVoice: String,
    val announcerUsesDefault: Boolean,
    val masculineVoice: String,
    val feminineVoice: String,
)

interface VoiceSelectionResolver {
    fun resolve(sender: SenderRef, assignments: List<SenderAssignment>, defaults: VoiceDefaults): VoiceChoice
    fun match(sender: SenderRef, assignments: List<SenderAssignment>): SenderAssignment?
}

/**
 * Deterministic voice selection:
 *  1. exact sender assignment with a specific voice,
 *  2. otherwise the voice for the sender's manually assigned category,
 *  3. otherwise the global default voice.
 *
 * Matching: an assignment created from a received message stores the
 * notification's stable sender key and then matches only that key, so a
 * different person who happens to share the display name does not get that
 * voice. Assignments typed in by name (no key) match the exact display name
 * (case- and whitespace-insensitive), never fuzzily, and prefer the same app.
 * Ties resolve by most specific match, then by assignment id, so results are stable.
 */
class DefaultVoiceSelectionResolver : VoiceSelectionResolver {

    override fun match(sender: SenderRef, assignments: List<SenderAssignment>): SenderAssignment? {
        val key = sender.stableKey
        if (key != null) {
            assignments.filter { it.matchKey == key && (it.appPackage == null || it.appPackage == sender.appPackage) }
                .minByOrNull { it.id }?.let { return it }
        }
        val name = normName(sender.displayName)
        if (name.isEmpty()) return null
        val byName = assignments.filter { it.matchKey == null && normName(it.matchName) == name }
        // Same-app name matches beat any-app name matches.
        byName.filter { it.appPackage == sender.appPackage }.minByOrNull { it.id }?.let { return it }
        byName.filter { it.appPackage == null }.minByOrNull { it.id }?.let { return it }
        // The notification exposes no sender key at all: a keyed assignment from the
        // same app with the exact same name is the best available evidence.
        if (key == null) {
            return assignments.filter { it.matchKey != null && it.appPackage == sender.appPackage && normName(it.matchName) == name }
                .minByOrNull { it.id }
        }
        return null
    }

    override fun resolve(sender: SenderRef, assignments: List<SenderAssignment>, defaults: VoiceDefaults): VoiceChoice {
        val announcer = if (defaults.announcerUsesDefault) defaults.defaultVoice else defaults.announcerVoice
        val a = match(sender, assignments)
        if (a != null) {
            a.voiceId?.let { return VoiceChoice(it, announcer, VoiceChoice.Source.SENDER_VOICE, a) }
            when (a.category) {
                VoiceCategory.MASCULINE -> return VoiceChoice(defaults.masculineVoice, announcer, VoiceChoice.Source.SENDER_CATEGORY, a)
                VoiceCategory.FEMININE -> return VoiceChoice(defaults.feminineVoice, announcer, VoiceChoice.Source.SENDER_CATEGORY, a)
                VoiceCategory.DEFAULT -> {}
            }
        }
        return VoiceChoice(defaults.defaultVoice, announcer, VoiceChoice.Source.DEFAULT, a)
    }

    companion object {
        fun normName(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
