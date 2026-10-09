package com.flowforge.dictation.accessibility

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.flowforge.core.insertion.FieldState
import com.flowforge.core.insertion.TargetField

/** Reads only what insertion needs from the focused node. Nothing is stored or logged. */
object FieldInspector {
    /** Above this length the node's text may be incomplete; fall back to paste instead of rewriting. */
    private const val MAX_RELIABLE_TEXT = 20_000

    private val SENSITIVE = Regex(
        """(?i)(password|passcode|passwort|contraseña|mot de passe|\bcvv\b|\bcvc\b|security code|card ?number|credit ?card|""" +
            """one[- ]time|\botp\b|verification code|2fa|two[- ]factor|\bssn\b|social security|(^|[^a-z])pin([^a-z]|$))""",
    )

    fun target(node: AccessibilityNodeInfo): TargetField {
        val actions = node.actionList.map { it.id }.toSet()
        return TargetField(
            packageName = node.packageName?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            isEditable = node.isEditable,
            isPassword = node.isPassword || isPasswordInputType(node.inputType),
            isSensitive = isSensitive(node),
            supportsSetText = AccessibilityNodeInfo.ACTION_SET_TEXT in actions,
            supportsPaste = AccessibilityNodeInfo.ACTION_PASTE in actions,
            supportsSetSelection = AccessibilityNodeInfo.ACTION_SET_SELECTION in actions,
            textReliable = state(node) != null,
        )
    }

    /**
     * Current text and selection, or null when they can't be trusted (null text on a non-empty
     * field, unknown cursor, very long text). Null steers insertion to paste, which never
     * rewrites the rest of the field.
     */
    fun state(node: AccessibilityNodeInfo): FieldState? {
        if (node.isShowingHintText) return FieldState("", 0, 0)
        val text = node.text?.toString() ?: return null
        if (text.length > MAX_RELIABLE_TEXT) return null
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0) return null
        return FieldState(text, start, end)
    }

    fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_TEXT_VARIATIONS) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private val PASSWORD_TEXT_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    fun isSensitive(node: AccessibilityNodeInfo): Boolean {
        val clues = listOfNotNull(node.hintText, node.viewIdResourceName, node.contentDescription, node.paneTitle)
        return clues.any { SENSITIVE.containsMatchIn(it) }
    }

    fun key(node: AccessibilityNodeInfo): String =
        "${node.packageName}|${node.windowId}|${node.viewIdResourceName ?: node.className}"
}
