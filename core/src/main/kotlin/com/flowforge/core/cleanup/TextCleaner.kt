package com.flowforge.core.cleanup

import com.flowforge.core.dictionary.PersonalDictionary
import com.flowforge.core.text.Renderer
import com.flowforge.core.text.Token
import com.flowforge.core.text.Tokenizer

/** Output of one cleanup rule. Removals of protected content must be listed in [declaredRemovals]. */
internal class StepOutput(
    val tokens: List<Token>,
    val edits: List<AppliedEdit> = emptyList(),
    val declaredRemovals: List<Token> = emptyList(),
    val command: CleanupCommand = CleanupCommand.NONE,
)

internal interface CleanupStep {
    val name: String
    fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput
}

/**
 * Deterministic, conservative dictation cleanup.
 *
 * The cleaner is a pipeline of small rules. After every rule, [SafetyGuard] compares numbers,
 * dates, negations and names before and after; a rule that would change them without declaring the
 * change (as a self-correction does) is skipped and reported in [CleanupResult.warnings]. If no rule
 * changes anything, the recognizer's text is returned untouched.
 */
class TextCleaner(private val dictionary: PersonalDictionary = PersonalDictionary.EMPTY) {

    fun clean(raw: String, options: CleanupOptions = CleanupOptions()): CleanupResult {
        var tokens = Tokenizer.tokenize(raw)
        val edits = ArrayList<AppliedEdit>()
        val warnings = ArrayList<String>()
        var command = CleanupCommand.NONE
        var changed = false

        for (step in stepsFor(options)) {
            val out = step.apply(tokens, options)
            if (out.tokens == tokens && out.command == CleanupCommand.NONE) continue
            val violations = SafetyGuard.violations(
                SafetyGuard.bagOf(tokens),
                SafetyGuard.bagOf(out.tokens),
                SafetyGuard.bagOf(out.declaredRemovals),
            )
            if (violations.isNotEmpty()) {
                warnings += "Skipped ${step.name}: ${violations.joinToString()}"
                continue
            }
            if (out.tokens != tokens) changed = true
            tokens = out.tokens
            edits += out.edits
            if (out.command != CleanupCommand.NONE) command = out.command
        }

        val text = if (changed) Renderer.render(tokens) else raw.trim()
        return CleanupResult(raw, text, options.mode, edits, warnings, command)
    }

    private fun stepsFor(options: CleanupOptions): List<CleanupStep> {
        val steps = ArrayList<CleanupStep>()
        if (options.spokenPunctuation) steps += SpokenPunctuationStep
        steps += DictionaryStep(dictionary)
        if (options.mode != CleanupMode.VERBATIM) {
            steps += FillerStep
            steps += RepetitionStep
            steps += SelfCorrectionStep
            steps += DiscourseActuallyStep
            if (options.mode == CleanupMode.PROFESSIONAL) {
                steps += ProfessionalWordingStep
                if (options.formatLists) steps += ListFormattingStep
            }
            steps += CommaStep
            steps += CapitalizationStep
            if (options.autoEndPunctuation) steps += EndPunctuationStep
        }
        steps += TidyPunctuationStep
        return steps
    }
}

internal class DictionaryStep(private val dictionary: PersonalDictionary) : CleanupStep {
    override val name = "personal dictionary"
    override fun apply(tokens: List<Token>, options: CleanupOptions): StepOutput {
        val result = dictionary.apply(tokens)
        val removedForms = result.replacements.flatMap { Tokenizer.tokenize(it.from) }
        return StepOutput(
            result.tokens,
            result.replacements.map { AppliedEdit(name, "\"${it.from}\" → \"${it.to}\"") },
            // The user asked for these rewrites explicitly, so the spoken form may disappear.
            declaredRemovals = removedForms,
        )
    }
}
