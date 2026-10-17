package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelSiriWindowTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        WheelZoomSettings.setSiriKey(host, true)
        WheelZoomSettings.assign(host, WheelZoomSettings.Role.SIRI, WheelKey(KeyEvent.KEYCODE_F6, 0, "?"))
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        controller = mock(CarPlayController::class.java)
        `when`(controller.requestSiri()).thenReturn(true)
        field("controller", controller)
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
    }

    @After fun tearDown() {
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host) as ExecutorService).shutdownNow()
        }
        (host.javaClass.getDeclaredField("mainHandler").apply { isAccessible = true }
            .get(host) as Handler).removeCallbacksAndMessages(null)
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun heldAssignedKeyKeepsItsReleaseAfterCallSessionAndSettingChanges() {
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true)))
        WheelZoomSettings.setSiriKey(host, false)
        field("activeAirPlaySession", null)
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true, repeat = 1)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun assignedDoublePressPassesBothKeysAndStartsSiriAfterTheDelay() {
        WheelZoomSettings.setSiriDoublePress(host, true)
        WheelZoomSettings.setSiriDoublePressIntervalMillis(host, 600)
        WheelZoomSettings.setSiriTriggerDelayMillis(host, 300)

        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true, time = 1_000)))
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false, time = 1_120)))
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true, time = 1_450)))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        verify(controller, never()).requestSiri()
        assertFalse(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false, time = 1_560)))
        verify(controller, never()).requestSiri()

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun aBoundServiceThatMissesThisKeyDoesNotDisableTheWindowFallback() {
        WheelZoomSettings.setSiriDoublePress(host, true)
        WheelZoomSettings.setSiriDoublePressIntervalMillis(host, 600)
        WheelZoomSettings.setSiriTriggerDelayMillis(host, 300)
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true, time = 2_000))
            host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false, time = 2_120))
            host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true, time = 2_450))
            host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false, time = 2_560))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            verify(controller, times(1)).requestSiri()
        } finally {
            service.onDestroy()
        }
    }

    @Test fun aBoundServiceWithoutASessionLeavesTheSameEventsToTheWindowFallback() {
        WheelZoomSettings.setSiriDoublePress(host, true)
        WheelZoomSettings.setSiriDoublePressIntervalMillis(host, 600)
        WheelZoomSettings.setSiriTriggerDelayMillis(host, 300)
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.session = { null }
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            fun dispatchBoth(down: Boolean, time: Long) {
                val serviceEvent = key(KeyEvent.KEYCODE_F6, down, time = time)
                val windowEvent = key(KeyEvent.KEYCODE_F6, down, time = time)
                assertFalse(serviceKey(service, serviceEvent))
                assertFalse(host.dispatchKeyEvent(windowEvent))
            }
            dispatchBoth(true, 2_700)
            dispatchBoth(false, 2_820)
            dispatchBoth(true, 3_150)
            dispatchBoth(false, 3_260)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            verify(controller, times(1)).requestSiri()
        } finally {
            service.onDestroy()
        }
    }

    @Test fun serviceAndWindowSeeingTheSamePassedEventsRequestSiriOnlyOnce() {
        WheelZoomSettings.setSiriDoublePress(host, true)
        WheelZoomSettings.setSiriDoublePressIntervalMillis(host, 600)
        WheelZoomSettings.setSiriTriggerDelayMillis(host, 300)
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        val sessionToken = Any()
        service.session = { sessionToken }
        service.requestSiri = { controller.requestSiri(); true }
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            fun dispatchBoth(down: Boolean, time: Long) {
                val serviceEvent = key(KeyEvent.KEYCODE_F6, down, time = time)
                val windowEvent = key(KeyEvent.KEYCODE_F6, down, time = time)
                assertFalse(serviceKey(service, serviceEvent))
                assertFalse(host.dispatchKeyEvent(windowEvent))
            }
            dispatchBoth(true, 3_000)
            dispatchBoth(false, 3_120)
            dispatchBoth(true, 3_450)
            dispatchBoth(false, 3_560)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            verify(controller, times(1)).requestSiri()
        } finally {
            service.onDestroy()
        }
    }

    @Test fun anAssignedStandardVoiceKeyDoesNotAlsoInvokeTheOldReleasePath() {
        val code = KeyEvent.KEYCODE_VOICE_ASSIST
        WheelZoomSettings.assign(host, WheelZoomSettings.Role.SIRI, WheelKey(code, 0, "?"))
        assertTrue(host.dispatchKeyEvent(key(code, true)))
        assertTrue(host.dispatchKeyEvent(key(code, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun unassignedExistingVoiceKeysKeepTheirReleaseBehavior() {
        WheelZoomSettings.setSiriKey(host, false)
        for (code in listOf(KeyEvent.KEYCODE_VOICE_ASSIST,
            CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE,
            CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG)) {
            assertTrue(host.dispatchKeyEvent(key(code, true)))
            assertTrue(host.dispatchKeyEvent(key(code, false)))
        }
        verify(controller, times(3)).requestSiri()
    }

    @Test fun longPressOnlyMakesTheShortVoiceKeyDoNothingAndKeepsTheLongOne() {
        WheelZoomSettings.setSiriKey(host, false)
        WheelZoomSettings.setSiriLongPressOnly(host, true)
        val short = CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE
        assertTrue(host.dispatchKeyEvent(key(short, true)))
        assertTrue(host.dispatchKeyEvent(key(short, false)))
        verify(controller, never()).requestSiri()
        val long = CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG
        assertTrue(host.dispatchKeyEvent(key(long, true)))
        assertTrue(host.dispatchKeyEvent(key(long, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun assigningALegacyVoiceKeyBetweenDownAndUpKeepsTheOriginalAction() {
        val code = KeyEvent.KEYCODE_VOICE_ASSIST
        WheelZoomSettings.setSiriKey(host, false)
        assertTrue(host.dispatchKeyEvent(key(code, true)))
        WheelZoomSettings.assign(host, WheelZoomSettings.Role.SIRI, WheelKey(code, 0, "?"))
        WheelZoomSettings.setSiriKey(host, true)
        assertTrue(host.dispatchKeyEvent(key(code, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun carPlayCallStateBlocksAssignedSiriWhileAndroidModeIsNormal() {
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "active-call")
        })
        assertEquals(AudioManager.MODE_NORMAL, host.getSystemService(AudioManager::class.java).mode)
        assertTrue(inCall(host))
        host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true))
        BydCarPlayCall.end()
        host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false))
        verify(controller, never()).requestSiri()
    }

    private fun field(name: String, value: Any?) = host.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(host, value)

    private fun serviceKey(service: WheelKeyService, event: KeyEvent): Boolean = service.javaClass
        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
        .invoke(service, event) as Boolean

    private fun key(code: Int, down: Boolean, repeat: Int = 0, time: Long = 1_000) =
        KeyEvent(time, time, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, repeat, 0, -1, 0)
}
