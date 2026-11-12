package app.commacabin.audio

/** Bumped when the onboarding text changes, so everyone sees the new version once. 1 = silent STEP 5 text. */
const val ONBOARDING_VERSION = 2

/** The only stored values: onboarding version, sound choice and level. No pickup or usage history. */
data class RestSettings(
    val onboardingVersion: Int = 0,
    val sound: SoundMode = SoundMode.QUIET,
    val level: SoundLevel = SoundLevel.MEDIUM,
) {
    val onboardingSeen: Boolean get() = onboardingVersion >= ONBOARDING_VERSION

    companion object {
        const val KEY_ONBOARDING = "onboarding_version"
        const val KEY_SOUND = "rest_sound"
        const val KEY_LEVEL = "rest_level"
        /** Written by STEP 5 (silent onboarding). Read as "not seen" and removed when the new version is saved. */
        const val KEY_OLD_ONBOARDING = "onboarding_seen"

        /** Unknown, mistyped or old values fall back to the safe defaults (quiet, medium, onboarding not seen). */
        fun from(stored: Map<String, *>): RestSettings = RestSettings(
            onboardingVersion = (stored[KEY_ONBOARDING] as? Int)?.coerceAtLeast(0) ?: 0,
            sound = SoundMode.entries.firstOrNull { it.name == stored[KEY_SOUND] } ?: SoundMode.QUIET,
            level = SoundLevel.entries.firstOrNull { it.name == stored[KEY_LEVEL] } ?: SoundLevel.MEDIUM,
        )
    }
}
