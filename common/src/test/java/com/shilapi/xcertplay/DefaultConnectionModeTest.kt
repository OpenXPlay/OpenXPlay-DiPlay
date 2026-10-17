package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DefaultConnectionModeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
    }

    @Test fun existingInstallationsKeepLastUsedBehaviorAndUnknownValuesFallBack() {
        for (lastWireless in listOf(false, true)) {
            AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
            assertEquals(lastWireless, DiPlayPreferences.autoConnectWireless(app))
            app.getSharedPreferences("diplay", 0).edit()
                .putString("default_connection_mode", "future_mode").commit()
            assertEquals(DefaultConnectionMode.LAST_USED, DiPlayPreferences.defaultConnectionMode(app))
            assertEquals(lastWireless, DiPlayPreferences.autoConnectWireless(app))
        }
    }

    @Test fun fixedDefaultSurvivesManualTransportChangesWithoutChangingAutoConnectOrPhone() {
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        val expectedModes = mapOf(
            DefaultConnectionMode.LAST_USED to listOf(false, true),
            DefaultConnectionMode.WIRELESS to listOf(true, true),
            DefaultConnectionMode.USB to listOf(false, false),
        )
        for ((mode, expectedWireless) in expectedModes) {
            DiPlayPreferences.saveDefaultConnectionMode(app, mode)
            assertEquals(mode, DiPlayPreferences.defaultConnectionMode(app))
            for ((index, lastWireless) in listOf(false, true).withIndex()) {
                AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
                assertEquals(expectedWireless[index], DiPlayPreferences.autoConnectWireless(app))
                assertEquals(lastWireless, AirPlayPersistence.loadWirelessEnabled(app))
            }
            assertFalse(DiPlayPreferences.autoConnect(app))
            assertEquals("00:11:22:33:44:55", DiPlayPreferences.phoneAddress(app))
        }
    }

}
