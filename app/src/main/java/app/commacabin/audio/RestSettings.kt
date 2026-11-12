package app.commacabin.audio

/**
 * Bumped when the onboarding text changes, so everyone sees the new version once. 1 = silent STEP 5 text,
 * 2 = rest sound and announcements (STEP 6), 3 = mood lighting (STEP 8).
 */
const val ONBOARDING_VERSION = 3

/** The only stored values: onboarding version, sound choice and level, mood lighting on/off. No pickup or usage history. */
data class RestSettings(
    val onboardingVersion: Int = 0,
    val sound: SoundMode = SoundMode.QUIET,
    val level: SoundLevel = SoundLevel.MEDIUM,
    /** [무드등 조정]. First default on (PM decision). */
    val lighting: Boolean = true,
) {
    val onboardingSeen: Boolean get() = onboardingVersion >= ONBOARDING_VERSION

    companion object {
        const val KEY_ONBOARDING = "onboarding_version"
        const val KEY_SOUND = "rest_sound"
        const val KEY_LEVEL = "rest_level"
        const val KEY_LIGHTING = "rest_lighting"
        /** Written by STEP 5 (silent onboarding). Read as "not seen" and removed when the new version is saved. */
        const val KEY_OLD_ONBOARDING = "onboarding_seen"

        /**
         * Unknown, mistyped or old values fall back to the defaults (quiet, medium, onboarding not seen, lighting
         * on). Settings saved before STEP 8 keep their sound and level; they get lighting on and see the
         * onboarding once more for the lighting text.
         */
        fun from(stored: Map<String, *>): RestSettings = RestSettings(
            onboardingVersion = (stored[KEY_ONBOARDING] as? Int)?.coerceAtLeast(0) ?: 0,
            sound = SoundMode.entries.firstOrNull { it.name == stored[KEY_SOUND] } ?: SoundMode.QUIET,
            level = SoundLevel.entries.firstOrNull { it.name == stored[KEY_LEVEL] } ?: SoundLevel.MEDIUM,
            lighting = stored[KEY_LIGHTING] as? Boolean ?: true,
        )
    }
}
