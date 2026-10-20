package com.qartvelo.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteConfigTest {
    private fun colorScheme(value: String?): String? =
        RemoteConfig.parse(JSONObject().put("config", JSONObject().apply { if (value != null) put("ad_color_scheme", value) })).adColorScheme

    @Test
    fun theAdColorModeIsReadFromTheInitializeConfig() {
        assertEquals("light", colorScheme("light"))
        assertEquals("dark", colorScheme("dark"))
        assertNull("auto follows the app", colorScheme("auto"))
        assertNull("unknown values follow the app", colorScheme("sepia"))
        assertNull("older backends send nothing", colorScheme(null))
    }
}
