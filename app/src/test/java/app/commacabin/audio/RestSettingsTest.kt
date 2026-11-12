package app.commacabin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestSettingsTest {
    @Test
    fun `nothing stored means quiet, medium and the onboarding not seen`() {
        val s = RestSettings.from(emptyMap<String, Any>())

        assertEquals(RestSettings(), s)
        assertEquals(SoundMode.QUIET, s.sound)
        assertEquals(SoundLevel.MEDIUM, s.level)
        assertFalse(s.onboardingSeen)
    }

    @Test
    fun `unknown or mistyped values fall back to the safe defaults`() {
        val s = RestSettings.from(mapOf(RestSettings.KEY_SOUND to "LOUD", RestSettings.KEY_LEVEL to 3, RestSettings.KEY_ONBOARDING to "2"))

        assertEquals(RestSettings(), s)
        assertEquals(0, RestSettings.from(mapOf(RestSettings.KEY_ONBOARDING to -5)).onboardingVersion)
    }

    @Test
    fun `the silent STEP 5 onboarding flag does not count as seeing the new onboarding`() {
        assertFalse(RestSettings.from(mapOf(RestSettings.KEY_OLD_ONBOARDING to true)).onboardingSeen)
        assertFalse(RestSettings.from(mapOf(RestSettings.KEY_ONBOARDING to 1)).onboardingSeen)
        assertTrue(RestSettings.from(mapOf(RestSettings.KEY_ONBOARDING to ONBOARDING_VERSION)).onboardingSeen)
    }

    @Test
    fun `stored choices are read back`() {
        val s = RestSettings.from(
            mapOf(RestSettings.KEY_ONBOARDING to ONBOARDING_VERSION, RestSettings.KEY_SOUND to "ON", RestSettings.KEY_LEVEL to "LOW"),
        )

        assertEquals(RestSettings(ONBOARDING_VERSION, SoundMode.ON, SoundLevel.LOW), s)
    }
}
