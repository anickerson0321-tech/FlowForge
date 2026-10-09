package com.flowforge.core.insertion

import com.flowforge.core.settings.InsertionPreference

/** How dictated text is delivered to the target field. */
enum class InsertionMethod {
    /** Keyboard mode: InputConnection.commitText (most reliable; cursor-aware). */
    IME_COMMIT,
    /** Accessibility ACTION_SET_TEXT with the full new text, then ACTION_SET_SELECTION. */
    SET_TEXT,
    /** Clipboard + accessibility ACTION_PASTE. Preserves rich content around the cursor. */
    PASTE,
    /** Show the text so the user can copy or insert it themselves. */
    MANUAL,
}

enum class RefusalReason(val message: String) {
    NO_FIELD("No text field is focused. Tap into a text field first, or copy the text from the preview."),
    PASSWORD("FlowForge never types into password fields."),
    SENSITIVE("This field looks like it holds sensitive data (for example a card number or one-time code), so FlowForge won't fill it."),
    NOT_EDITABLE("The focused item isn't editable."),
    BLOCKED_APP("Dictation is turned off for this app in FlowForge settings."),
    OWN_APP("Use the preview to insert text inside FlowForge itself."),
}

/** Facts about the focused field, extracted by the Android layer (accessibility node or EditorInfo). */
data class TargetField(
    val packageName: String,
    val className: String = "",
    val isEditable: Boolean = true,
    val isPassword: Boolean = false,
    /** Input type or autofill hints say credit card, OTP, PIN, etc. */
    val isSensitive: Boolean = false,
    val supportsSetText: Boolean = false,
    val supportsPaste: Boolean = false,
    val supportsSetSelection: Boolean = false,
    /** The field's text and selection were read reliably (not a hint, not truncated). */
    val textReliable: Boolean = false,
)

sealed class InsertionDecision {
    data class Insert(val method: InsertionMethod) : InsertionDecision()
    data class Refuse(val reason: RefusalReason) : InsertionDecision()
}

/**
 * Decides whether and how to insert into a field reached through the accessibility service.
 * Refusals always leave the dictated text available for manual copy.
 */
object InsertionPolicy {
    /** Plain text widgets where ACTION_SET_TEXT round-trips text faithfully. */
    private val PLAIN_EDITORS = setOf(
        "android.widget.EditText", "android.widget.AutoCompleteTextView",
        "android.widget.MultiAutoCompleteTextView", "android.widget.SearchView\$SearchAutoComplete",
        "androidx.appcompat.widget.AppCompatEditText", "androidx.appcompat.widget.AppCompatAutoCompleteTextView",
        "com.google.android.material.textfield.TextInputEditText",
    )

    fun decide(
        target: TargetField?,
        preference: InsertionPreference,
        ownPackage: String,
        blockedPackages: Set<String> = emptySet(),
    ): InsertionDecision {
        if (target == null) return InsertionDecision.Refuse(RefusalReason.NO_FIELD)
        if (target.isPassword) return InsertionDecision.Refuse(RefusalReason.PASSWORD)
        if (target.isSensitive) return InsertionDecision.Refuse(RefusalReason.SENSITIVE)
        if (target.packageName == ownPackage) return InsertionDecision.Refuse(RefusalReason.OWN_APP)
        if (target.packageName in blockedPackages) return InsertionDecision.Refuse(RefusalReason.BLOCKED_APP)
        if (!target.isEditable) return InsertionDecision.Refuse(RefusalReason.NOT_EDITABLE)

        val canSetText = target.supportsSetText && target.textReliable
        val method = when (preference) {
            InsertionPreference.PREVIEW -> InsertionMethod.MANUAL
            InsertionPreference.PASTE -> if (target.supportsPaste) InsertionMethod.PASTE else InsertionMethod.MANUAL
            InsertionPreference.AUTOMATIC -> when {
                // Rich editors (WebView, compose, custom views) can lose formatting with SET_TEXT.
                canSetText && target.className in PLAIN_EDITORS -> InsertionMethod.SET_TEXT
                target.supportsPaste -> InsertionMethod.PASTE
                canSetText -> InsertionMethod.SET_TEXT
                else -> InsertionMethod.MANUAL
            }
        }
        return InsertionDecision.Insert(method)
    }

    /**
     * App-switch safety: if the focused app changed between starting and finishing a dictation,
     * the text is shown for confirmation instead of being typed into an app the user may not expect.
     */
    fun requiresConfirmation(packageAtStart: String?, packageNow: String?): Boolean =
        packageAtStart != null && packageNow != null && packageAtStart != packageNow
}
