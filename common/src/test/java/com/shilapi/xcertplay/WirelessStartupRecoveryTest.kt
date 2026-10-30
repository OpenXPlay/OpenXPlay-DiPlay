// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-27
package com.shilapi.xcertplay

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayEventObserver
import com.shilapi.xcertplay.orchestration.CarPlayEventObserverProvider
import com.shilapi.xcertplay.orchestration.CarPlayLinkEnd
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupRecoveryTest {
    class LinkApplication : Application(), CarPlayEventObserverProvider {
        val ends = mutableListOf<CarPlayLinkEnd>()
        override val carPlayEventObserver = object : CarPlayEventObserver {
            override fun onLinkEnded(end: CarPlayLinkEnd) { ends += end }
        }
    }

    private val controllers = mutableListOf<CarPlayController>()
    private lateinit var activity: CarPlayHostActivity
    private lateinit var retry: Button
    private val failure = CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", activity)
        retry = Button(activity).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "startupRetryButton", retry)
    }

    @After fun cleanup() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
    }

    private fun report(generation: Int = 0, status: CarPlayStatus = failure) {
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            from(Int::class.javaPrimitiveType, generation))(status)
    }
    private fun budget() = ReflectionHelpers.getField<WirelessStartupRetryBudget>(activity, "startupRetryBudget")

    private fun controller(transport: CarPlayTransport): CarPlayController {
        val controller = CarPlayController(activity,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = transport,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        controllers += controller
        ReflectionHelpers.setField(activity, "controller", controller)
        // Install an unstarted phone link without touching Bluetooth, USB or hotspot hardware.
        val type = Class.forName("com.shilapi.xcertplay.orchestration.CarPlayLinkDiagnostics")
        val deliver: (CarPlayLinkEnd) -> Unit = { end ->
            CarPlayController::class.java.getDeclaredMethod("reportLinkEnd", CarPlayLinkEnd::class.java)
                .apply { isAccessible = true }.invoke(controller, end)
        }
        val now: () -> Long = System::nanoTime
        val link = type.getDeclaredConstructor(String::class.java, Function1::class.java, Function0::class.java)
            .apply { isAccessible = true }.newInstance(if (transport == CarPlayTransport.WIRELESS) "wireless_run" else "usb", deliver, now)
        ReflectionHelpers.setField(controller, if (transport == CarPlayTransport.WIRELESS) "wirelessEnd" else "usbEnd", link)
        return controller
    }

    private fun noteFailure(controller: CarPlayController) {
        CarPlayController::class.java.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, IOException("socket failed"), null)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue((activity.application as LinkApplication).ends.isEmpty())
    }

    private fun assertDecision(controller: CarPlayController, transport: CarPlayTransport, next: String, delay: String) {
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        val ends = (activity.application as LinkApplication).ends
        val end = ends.single()
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(end), ends)
        assertEquals(if (transport == CarPlayTransport.WIRELESS) "wireless_run" else "usb", end.link)
        assertEquals("local", end.by)
        assertEquals(if (transport == CarPlayTransport.WIRELESS) "connect_failed" else "io_error", end.cause)
        assertEquals("IOException: socket failed", end.detail)
        assertEquals(next, end.facts["next"])
        assertEquals(delay, end.facts["nextInMs"])
        ends.clear()
    }

    @Test @Config(application = LinkApplication::class)
    fun notedFailureThenActivityRetryReportsTheScheduledRebuildOnce() {
        for (transport in CarPlayTransport.entries) {
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            ReflectionHelpers.setField(activity, "reconnectAttempts", 0)
            val controller = controller(transport)
            noteFailure(controller)
            report(status = CarPlayStatus.Failed("socket failed"))
            assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
            assertDecision(controller, transport, "rebuild", "2000")
        }
    }

    @Test @Config(application = LinkApplication::class)
    fun notedFailureThenExhaustedRetriesReportsStopOnce() {
        repeat(com.shilapi.xcertplay.network.WirelessStartupPolicy.MAX_STARTUP_RETRIES) { budget().nextDelayMillis() }
        for (transport in CarPlayTransport.entries) {
            ReflectionHelpers.setField(activity, "startupRetryStopped", false)
            ReflectionHelpers.setField(activity, "startupFailureGeneration", -1)
            val controller = controller(transport)
            noteFailure(controller)
            report()
            assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
            assertDecision(controller, transport, "stop", "-")
        }
    }

    @Test @Config(application = LinkApplication::class)
    fun closeWithoutARecoveryDecisionReportsStopOnce() {
        for (transport in CarPlayTransport.entries) {
            val controller = controller(transport)
            noteFailure(controller)
            controller.close()
            assertDecision(controller, transport, "stop", "-")
        }
    }

    @Test @Config(application = LinkApplication::class)
    fun closeWithoutAFailureReportsALocalStop() {
        for (transport in CarPlayTransport.entries) {
            val controller = controller(transport)
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            val ends = (activity.application as LinkApplication).ends
            assertEquals("local", ends.single().by)
            assertEquals("stopped", ends.single().cause)
            assertEquals(mapOf("next" to "stop", "nextInMs" to "-"), ends.single().facts)
            ends.clear()
        }
    }

    @Test @Config(application = LinkApplication::class)
    fun activityReconnectGraceReportsWaitWithTheScheduledDelay() {
        val controller = controller(CarPlayTransport.WIRELESS)
        noteFailure(controller)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "reconnectAfterLoss",
            from(String::class.java, "AirPlay session ended"), from(WirelessStartupFailure::class.java, null),
            from(String::class.java, "AirPlay session ended"), from(Long::class.javaPrimitiveType, 25_000L))
        assertDecision(controller, CarPlayTransport.WIRELESS, "wait_iphone", "25000")
    }

    @Test fun recoveredControlCancelsQueuedRetryButAnotherLossKeepsItEligible() {
        val log = java.io.File.createTempFile("retry-recovered", ".log")
        ReflectionHelpers.setField(activity, "sessionLog", SessionLogFile(log))
        report()
        report(status = CarPlayStatus.RunningWireless)
        assertEquals(1L, ReflectionHelpers.getField<Long>(activity, "controlRunningCount"))
        assertFalse(ReflectionHelpers.getField(activity, "lossSinceControlRunning"))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(35))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(activity, "restartGeneration"))
        assertTrue(log.readText().contains("cancelling queued retry"))
        log.delete()
        report(status = CarPlayStatus.ControlEnded)
        assertTrue(ReflectionHelpers.getField(activity, "lossSinceControlRunning"))
    }

    @Test fun duplicateFailureAndCleanupErrorScheduleOnlyOneRetry() {
        report()
        report()
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(1, budget().retries)
        assertEquals(1, ReflectionHelpers.getField<Int>(activity, "reconnectAttempts"))
    }

    @Test fun exhaustedBudgetStopsAutomaticRecoveryAndExposesRetry() {
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation)
        }
        assertEquals(5, budget().retries)
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
        report(5, CarPlayStatus.Failed("Bluetooth socket closed"))
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
    }

    @Test fun unrecoverableConfigurationDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Permission missing", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // No further attempt can make a head unit's Bluetooth reach the phone.
    @Test fun bluetoothThatReachesNoPhoneDoesNotAutomaticallyRetry() {
        report(status = CarPlayStatus.Failed("Android Bluetooth on this head unit reports connections that are not real",
            startupFailure = WirelessStartupFailure.BLUETOOTH_UNUSABLE))
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // Issue 709: every connect fails inside Android. The iPhone is not what is missing, and no retry helps.
    @Test fun bluetoothThatCannotOpenConnectionsSaysSoAndDoesNotRetry() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val failure = BluetoothLinkCheck.connectFailure(NullPointerException("Attempt to invoke interface method " +
            "'android.os.ParcelFileDescriptor android.bluetooth.IBluetoothSocketManager.connectSocket(" +
            "android.bluetooth.BluetoothDevice, int, android.os.ParcelUuid, int, int)' on a null object reference"),
            "phone-address") as WirelessStartupException
        report(status = CarPlayStatus.Failed(failure.message!!, startupFailure = failure.reason))
        assertEquals(activity.getString(R.string.bluetooth_cannot_open_connections), stage.text.toString())
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // A head unit whose Android has no Bluetooth adapter: nothing to turn on and nothing to wait for.
    @Test fun aHeadUnitWithoutABluetoothAdapterSaysSoAndDoesNotRetry() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val failure = BluetoothRadioCheck.failure(BluetoothRadioCheck.NO_ADAPTER) as WirelessStartupException
        report(status = CarPlayStatus.Failed(failure.message!!, startupFailure = failure.reason))
        assertEquals(activity.getString(R.string.bluetooth_cannot_open_connections), stage.text.toString())
        assertEquals(0, budget().retries)
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    // The driver can switch Bluetooth on, so the screen asks for it and the attempts go on.
    @Test fun bluetoothThatIsOffAsksForItAndKeepsRetrying() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        report(status = CarPlayStatus.Failed(BluetoothRadioCheck.RADIO_OFF))
        assertEquals(activity.getString(R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect), stage.text.toString())
        assertTrue(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertFalse(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.GONE, retry.visibility)
    }

    /** Fails every attempt with [status] until the retries stop, checking the screen names [cause] meanwhile. */
    private fun assertNamedUntilRetriesStop(status: CarPlayStatus.Failed, cause: Int) {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        repeat(6) { generation ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "reconnectScheduled", false)
            report(generation, status)
            if (generation < 5) assertEquals(activity.getString(cause), stage.text.toString())
        }
        assertTrue(ReflectionHelpers.getField(activity, "startupRetryStopped"))
        assertEquals(View.VISIBLE, retry.visibility)
        assertEquals(activity.getString(cause) + "\n" + activity.getString(R.string.wireless_startup_retries_exhausted),
            stage.text.toString())
    }

    private val hotspotNotReady = CarPlayStatus.Failed("Could not establish MANUAL hotspot: Hotspot network is not ready",
        startupFailure = WirelessStartupFailure.HOTSPOT_NOT_READY)

    // #809: the screen said "Getting CarPlay ready…" while the car hotspot never came up, and after the retries stopped.
    @Test fun aHotspotThatIsNotReadyIsNamedAndTheStopIsSaid() =
        assertNamedUntilRetriesStop(hotspotNotReady, R.string.hotspot_network_not_ready)

    @Test @Config(qualifiers = "zh-rCN") fun aHotspotThatIsNotReadyIsNamedInChinese() {
        assertNotEquals("Hotspot network is not ready", activity.getString(R.string.hotspot_network_not_ready))
        assertNamedUntilRetriesStop(hotspotNotReady, R.string.hotspot_network_not_ready)
    }

    @Test fun anIphoneThatDoesNotConnectInTimeIsNamedAndTheStopIsSaid() =
        assertNamedUntilRetriesStop(failure, R.string.first_tcp_timeout)

    // Nothing is retried for a car hotspot DiPlay cannot use, so the screen does not say "Retrying…".
    @Test fun aHotspotDiPlayCannotUseSaysTheRetriesStopped() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        listOf("The car hotspot is off. Turn it on in the car settings and connect again.",
            "Hotspot control permission is missing. Open the car hotspot settings and connect again.",
            "Manual hotspot is not running on 5 GHz").forEachIndexed { generation, cause ->
            ReflectionHelpers.setField(activity, "restartGeneration", generation)
            ReflectionHelpers.setField(activity, "startupRetryStopped", false)
            report(generation, CarPlayStatus.Failed(cause, startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
            assertEquals(cause, activity.getString(R.string.hotspot_network_not_ready) + "\n" +
                activity.getString(R.string.wireless_startup_retries_exhausted), stage.text.toString())
            assertEquals(0, budget().retries)
            assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        }
    }

    // The stopped attempt can still report its own failure again, or its cleanup's.
    @Test fun aLaterFailureOfTheStoppedAttemptKeepsTheLineSayingTheRetriesStopped() {
        val stage = TextView(activity)
        ReflectionHelpers.setField(activity, "stageStatusView", stage)
        val offline = CarPlayStatus.Failed("The car hotspot is off. Turn it on in the car settings and connect again.",
            startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION)
        report(status = offline)
        val stopped = activity.getString(R.string.hotspot_network_not_ready) + "\n" +
            activity.getString(R.string.wireless_startup_retries_exhausted)
        assertEquals(stopped, stage.text.toString())
        report(status = offline)
        report(status = CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(stopped, stage.text.toString())
        assertFalse(ReflectionHelpers.getField(activity, "reconnectScheduled"))
        assertEquals(View.VISIBLE, retry.visibility)
    }

    @Test fun staleGenerationUserDisconnectMenuAndDifferentOwnerCannotSchedule() {
        report(1)
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.setField(activity, "menuOpen", false)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        report()
        assertEquals(0, budget().retries)
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(false)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", Any())
        report()
        assertEquals(0, budget().retries)
    }
}
