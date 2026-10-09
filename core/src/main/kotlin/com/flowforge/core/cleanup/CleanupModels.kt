package com.flowforge.core.cleanup

enum class CleanupMode(val label: String, val summary: String) {
    VERBATIM("Verbatim", "Exactly what you said. Only your dictionary and spoken commands are applied."),
    LIGHT("Light cleanup", "Removes um/uh, stutters and clear self-corrections; fixes punctuation and capitals."),
    PROFESSIONAL("Professional", "Light cleanup plus formal wording, list formatting and tidier sentences."),
}

/**
 * Text surrounding the insertion point, used to decide capitalization and final punctuation.
 * Only the few characters adjacent to the cursor are needed; callers should not pass more.
 */
data class InsertionContext(val before: String = "", val after: String = "") {
    /** True when the dictated text begins a new sentence. */
    val startsSentence: Boolean
        get() {
            val trimmed = before.trimEnd(' ', '\t')
            if (trimmed.isEmpty()) return true
            val last = trimmed.last()
            return last == '\n' || last == '.' || last == '?' || last == '!' || last == '…'
        }

    /** True when existing text continues right after the insertion point on the same line. */
    val continuesSentence: Boolean
        get() {
            val next = after.trimStart(' ', '\t').firstOrNull() ?: return false
            return next != '\n'
        }

    companion object {
        val EMPTY = InsertionContext()
        const val MAX_CONTEXT_CHARS = 64

        fun of(before: CharSequence, after: CharSequence) = InsertionContext(
            before.takeLast(MAX_CONTEXT_CHARS).toString(),
            after.take(MAX_CONTEXT_CHARS).toString(),
        )
    }
}

data class CleanupOptions(
    val mode: CleanupMode = CleanupMode.LIGHT,
    /** Convert spoken commands such as "comma" or "new paragraph" into punctuation. */
    val spokenPunctuation: Boolean = true,
    /** Add a final period/question mark when a sentence has none (Light/Professional only). */
    val autoEndPunctuation: Boolean = true,
    /** Turn "first ... second ..." into a numbered list (Professional only). */
    val formatLists: Boolean = true,
    val context: InsertionContext = InsertionContext.EMPTY,
)

/** A non-text instruction found in the dictation, e.g. "scratch that" with nothing before it. */
enum class CleanupCommand { NONE, UNDO_PREVIOUS_INSERTION }

data class AppliedEdit(val rule: String, val detail: String)

data class CleanupResult(
    /** Exactly what the recognizer produced. Always kept so the user can recover it. */
    val original: String,
    val text: String,
    val mode: CleanupMode,
    val edits: List<AppliedEdit>,
    /** Rules that were skipped because they would have changed protected content. */
    val warnings: List<String>,
    val command: CleanupCommand = CleanupCommand.NONE,
) {
    val changed: Boolean get() = text != original.trim()
}
