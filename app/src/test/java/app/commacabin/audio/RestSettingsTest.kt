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

    @Test
    fun `settings saved before STEP 8 keep sound and level, get lighting on and see the new onboarding once`() {
        val old = RestSettings.from(mapOf(RestSettings.KEY_ONBOARDING to 2, RestSettings.KEY_SOUND to "ON", RestSettings.KEY_LEVEL to "HIGH"))
        assertEquals(SoundMode.ON, old.sound)
        assertEquals(SoundLevel.HIGH, old.level)
        assertTrue(old.lighting)
        assertFalse(old.onboardingSeen)

        assertFalse(RestSettings.from(mapOf(RestSettings.KEY_LIGHTING to false)).lighting)
        assertTrue(RestSettings.from(mapOf(RestSettings.KEY_LIGHTING to "false")).lighting)
    }

    @Test
    fun `STEP 9 settings - fan and temperature default on at 22, earlier choices kept, version 3 sees the new onboarding`() {
        val old = RestSettings.from(mapOf(RestSettings.KEY_ONBOARDING to 3, RestSettings.KEY_SOUND to "ON", RestSettings.KEY_LEVEL to "LOW", RestSettings.KEY_LIGHTING to false))
        assertEquals(SoundMode.ON, old.sound)
        assertEquals(SoundLevel.LOW, old.level)
        assertFalse(old.lighting)
        assertTrue(old.fan && old.temp)
        assertEquals(22.0f, old.tempC)
        assertFalse(old.onboardingSeen)

        val stored = RestSettings.from(mapOf(RestSettings.KEY_FAN to false, RestSettings.KEY_TEMP to false, RestSettings.KEY_TEMP_C to 22.25f))
        assertFalse(stored.fan || stored.temp)
        assertEquals(22.25f, stored.tempC)
        assertEquals(22.0f, RestSettings.from(mapOf(RestSettings.KEY_TEMP_C to Float.NaN)).tempC)
        assertEquals(22.0f, RestSettings.from(mapOf(RestSettings.KEY_TEMP_C to "22.5")).tempC)

        // STEP 10: a version 4 user sees the shorter text once; every stored choice stays.
        val v4 = RestSettings.from(
            mapOf(
                RestSettings.KEY_ONBOARDING to 4, RestSettings.KEY_SOUND to "ON", RestSettings.KEY_LEVEL to "HIGH", RestSettings.KEY_LIGHTING to false,
                RestSettings.KEY_FAN to false, RestSettings.KEY_TEMP to true, RestSettings.KEY_TEMP_C to 23.5f,
            ),
        )
        assertFalse(v4.onboardingSeen)
        assertEquals(RestSettings(4, SoundMode.ON, SoundLevel.HIGH, lighting = false, fan = false, temp = true, tempC = 23.5f), v4)
    }
}
