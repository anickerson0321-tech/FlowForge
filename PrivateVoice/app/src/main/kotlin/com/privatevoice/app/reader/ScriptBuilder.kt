package com.privatevoice.app.reader

import com.privatevoice.app.audio.Segment
import com.privatevoice.app.settings.ReadContent
import com.privatevoice.app.voices.VoiceChoice

/** Options that shape what is spoken, taken from settings. */
data class ScriptOptions(
    val announceSender: Boolean,
    val readGroupName: Boolean,
    val repeatSenderName: Boolean,
    val content: ReadContent,
)

/**
 * Builds the spoken segments for one message:
 *
 *   announcer voice:  "Message from Sarah."            (optional)
 *   sender's voice:   "Hey, are we still going out tonight?"
 *
 * Hidden content is reported as hidden — nothing is invented.
 */
object ScriptBuilder {
    fun build(
        senderName: String,
        groupName: String?,
        text: String?,
        voices: VoiceChoice,
        options: ScriptOptions,
        previousSenderOfSession: String?,
    ): List<Segment> {
        val segments = ArrayList<Segment>()
        val group = groupName?.takeIf { options.readGroupName && it.isNotBlank() && !it.equals(senderName, ignoreCase = true) }
        val where = if (group != null) " in $group" else ""
        val sameSenderAgain = previousSenderOfSession != null && previousSenderOfSession.equals(senderName, ignoreCase = true)
        if (options.content == ReadContent.SENDER_ONLY || text.isNullOrBlank()) {
            val announce = if (text.isNullOrBlank() && options.content == ReadContent.SENDER_AND_MESSAGE) {
                "Message from $senderName$where. The app didn't share the message text."
            } else {
                "You have a message from $senderName$where."
            }
            segments += Segment(announce, voices.announcerVoice)
            return segments
        }
        val announce = options.announceSender && (options.repeatSenderName || !sameSenderAgain)
        if (announce) segments += Segment("Message from $senderName$where.", voices.announcerVoice)
        segments += Segment(text, voices.messageVoice)
        return mergeSameVoice(segments)
    }

    /** Joins adjacent segments that use the same voice into one utterance for smoother prosody. */
    private fun mergeSameVoice(list: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>()
        for (s in list) {
            val last = out.lastOrNull()
            if (last != null && last.voiceId == s.voiceId) out[out.size - 1] = Segment(last.text + " " + s.text, s.voiceId)
            else out += s
        }
        return out
    }

    fun previewText(voiceName: String) = "Hi, this is $voiceName. This is how your messages will sound when PrivateVoice reads them to you."
}
