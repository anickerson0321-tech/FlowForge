package com.flowforge.core.features

/**
 * Monetization seam. Features that could become paid ("Pro") are listed here and checked through
 * [Entitlements]. This build has NO billing: [AllFeaturesUnlocked] enables everything, and no
 * payment, subscription or advertising code is present. A future billing module can supply a
 * different [Entitlements] without touching call sites.
 */
enum class Feature(val label: String, val proCandidate: Boolean) {
    LOCAL_DICTATION("On-device dictation", proCandidate = false),
    BASIC_CLEANUP("Verbatim and light cleanup", proCandidate = false),
    PERSONAL_DICTIONARY("Personal dictionary", proCandidate = false),
    PROFESSIONAL_CLEANUP("Professional cleanup", proCandidate = false),
    CLOUD_RECOGNITION("Cloud recognition (bring your own key)", proCandidate = true),
    AI_CLEANUP("AI cleanup (bring your own key)", proCandidate = true),
}

interface Entitlements {
    fun isEnabled(feature: Feature): Boolean
}

object AllFeaturesUnlocked : Entitlements {
    override fun isEnabled(feature: Feature) = true
}
