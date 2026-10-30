// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-16
package com.shilapi.xcertplay.orchestration

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayEventObserverTest {
    open class RecordingApplication : Application(), CarPlayEventObserverProvider {
        val events = mutableListOf<String>()
        val linkEnds = mutableListOf<CarPlayLinkEnd>()
        override val carPlayEventObserver = object : CarPlayEventObserver {
            override fun onStatus(status: CarPlayStatus) { events += "status:${status.javaClass.simpleName}" }
            override fun onProgress(message: String) { events += "progress:$message" }
            override fun onLinkEnded(end: CarPlayLinkEnd) { linkEnds += end }
            override fun onSessionActive() { events += "active" }
            override fun onSessionEnded() { events += "ended" }
            override fun onIap2Frame(frame: Iap2Frame) {
                events += "frame:${frame.messageId}:${frame.payload.size}"
            }
            override fun onCommand(type: String, params: Map<String, Any?>) {
                events += "command:$type:${params["url"]}"
            }
        }
    }

    class HostUiApplication : RecordingApplication(), CarPlayHostUiHandler {
        var handled = true
        var failure: RuntimeException? = null
        var requests = 0

        override fun onHostUiRequested(): Boolean {
            requests++
            failure?.let { throw it }
            return handled
        }
    }

    class ThrowingApplication : Application(), CarPlayEventObserverProvider {
        override val carPlayEventObserver = object : CarPlayEventObserver {
            override fun onStatus(status: CarPlayStatus) = throw IllegalStateException("observer")
            override fun onProgress(message: String) = throw IllegalStateException("observer")
            override fun onLinkEnded(end: CarPlayLinkEnd) = throw IllegalStateException("observer")
            override fun onSessionActive() = throw IllegalStateException("observer")
            override fun onSessionEnded() = throw IllegalStateException("observer")
            override fun onIap2Frame(frame: Iap2Frame) = throw IllegalStateException("observer")
            override fun onCommand(type: String, params: Map<String, Any?>) =
                throw IllegalStateException("observer")
        }
    }

    private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
    private val diagnosticLines = mutableListOf<String>()
    private val hostEvents = mutableListOf<String>()
    private val hostStatuses = mutableListOf<CarPlayStatus>()
    private val sessions = mutableListOf<AirPlaySession>()
    private lateinit var controller: CarPlayController

    private fun start(transport: CarPlayTransport = CarPlayTransport.WIRED) {
        controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = transport, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(),
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { diagnosticLines += message }
                override fun onSessionActive(session: AirPlaySession) { hostEvents += "active" }
                override fun onSessionEnded(session: AirPlaySession) { hostEvents += "ended" }
                override fun onHostUiRequested(session: AirPlaySession) { hostEvents += "host-ui" }
                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                    hostEvents += "command:$type"
                }
            },
            object : AirPlayMediaHandler {}, { hostStatuses += it })
    }

    private fun phone() = AirPlaySession(Socket(), config, identity, PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).also { sessions += it }

    private fun sessionListener() = CarPlayController::class.java.getDeclaredField("sessionListener")
        .apply { isAccessible = true }.get(controller) as AirPlaySessionListener

    private fun activeSession() = CarPlayController::class.java.getDeclaredField("activeSession")
        .apply { isAccessible = true }.get(controller)

    private fun route(frame: Iap2Frame) {
        CarPlayController::class.java.getDeclaredMethod("onRouteFrame", Iap2Frame::class.java)
            .apply { isAccessible = true }.invoke(controller, frame)
    }

    private fun status(status: CarPlayStatus) {
        CarPlayController::class.java.getDeclaredMethod("onStatus", CarPlayStatus::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, status, null)
    }

    private fun progress(message: String) {
        val event: CarPlayEventObserver.() -> Unit = { onProgress(message) }
        CarPlayController::class.java.getDeclaredMethod("notifyObserver", Function1::class.java)
            .apply { isAccessible = true }.invoke(controller, event)
    }

    @Test
    @Config(application = RecordingApplication::class)
    fun applicationObserverReceivesDeduplicatedStatusesAndProgress() {
        start()
        status(CarPlayStatus.DiscoveringMfi)
        status(CarPlayStatus.DiscoveringMfi)
        status(CarPlayStatus.WaitingForMfi)

        val application = RuntimeEnvironment.getApplication() as RecordingApplication
        assertEquals(emptyList<String>(), application.events)
        shadowOf(Looper.getMainLooper()).idle()
        progress("identification complete")

        assertEquals(listOf("status:DiscoveringMfi", "status:WaitingForMfi", "progress:identification complete"),
            application.events)
        assertEquals(listOf(CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi), hostStatuses)
    }

    @Test
    @Config(application = ThrowingApplication::class)
    fun throwingStatusAndProgressObserverLeavesTheControllerPathIntact() {
        start()
        status(CarPlayStatus.DiscoveringMfi)
        status(CarPlayStatus.DiscoveringMfi)
        status(CarPlayStatus.WaitingForMfi)
        shadowOf(Looper.getMainLooper()).idle()
        progress("identification complete")
        runSession()

        assertEquals(listOf(CarPlayStatus.DiscoveringMfi, CarPlayStatus.WaitingForMfi), hostStatuses)
        assertEquals(listOf("active", "command:requestUI", "ended", "ended"), hostEvents)
        assertNull(activeSession())
    }

    @Test
    @Config(application = RecordingApplication::class)
    fun linkEndDeliveryIsQueuedOffTheConnectionThread() {
        start()
        val end = CarPlayLinkEnd("airplay_control", "pair_verified", "peer", "peer_eof",
            facts = mapOf("verifyOnly" to "true"))
        sessionListener().onLinkEnded(end)
        val app = RuntimeEnvironment.getApplication() as RecordingApplication
        assertTrue(app.linkEnds.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(end), app.linkEnds)
        assertTrue(diagnosticLines.any { it.contains("link ended link=airplay_control") && it.contains("verifyOnly=true") })
    }

    @Test
    @Config(application = ThrowingApplication::class)
    fun throwingLinkEndObserverDoesNotBreakDeliveryOrTeardown() {
        start()
        sessionListener().onLinkEnded(CarPlayLinkEnd("usb", "opening", "local", "connect_failed"))
        // Use a socket link: an unstarted controller has no owned USB attempt yet.
        sessionListener().onLinkEnded(CarPlayLinkEnd("airplay_event", "opening", "local", "io_error"))
        shadowOf(Looper.getMainLooper()).idle()
        runSession()
        assertNull(activeSession())
        assertTrue(diagnosticLines.any { it.contains("CarPlay event observer failed: IllegalStateException") })
    }

    private fun field(name: String) = CarPlayController::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun tracker(name: String, link: String) {
        field(name).set(controller, CarPlayLinkDiagnostics(link, { end ->
            CarPlayController::class.java.getDeclaredMethod("reportLinkEnd", CarPlayLinkEnd::class.java)
                .apply { isAccessible = true }.invoke(controller, end)
        }))
    }

    @Test
    @Config(application = RecordingApplication::class)
    fun bluetoothConnectFailuresKeepMessageAndConsecutiveCountOnTheSameLine() {
        start(CarPlayTransport.WIRELESS)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        (field("bluetoothConsecutiveFailures").get(null) as AtomicInteger).set(0)
        val fail = CarPlayController::class.java.getDeclaredMethod("recordBluetoothConnectFailure",
            BluetoothDevice::class.java, Throwable::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        repeat(2) {
            tracker("bluetoothEnd", "bluetooth")
            fail.invoke(controller, device, IOException("read failed, socket might closed AA:BB:CC:DD:EE:FF"), System.nanoTime())
        }
        shadowOf(Looper.getMainLooper()).idle()
        val app = RuntimeEnvironment.getApplication() as RecordingApplication
        assertEquals(listOf("1", "2"), app.linkEnds.map { it.facts["consecutiveFailures"] })
        assertTrue(app.linkEnds.all { it.link == "bluetooth" && it.cause == "connect_failed" })
        assertTrue(diagnosticLines.any { it.contains("Bluetooth connect failed") && it.contains("consecutiveFailures=2") &&
            it.contains("IOException: read failed, socket might closed") })
        assertTrue(diagnosticLines.none { it.contains("AA:BB:CC:DD:EE:FF") })
    }

    @Test
    @Config(application = RecordingApplication::class)
    fun handoffWatchdogNotesItsCauseUntilTheHostChoosesRecovery() {
        start(CarPlayTransport.WIRELESS)
        tracker("wirelessEnd", "wireless_run")
        val phase = field("phase")
        phase.set(controller, phase.type.enumConstants.first { it.toString() == "WIRELESS" })
        (field("wirelessHandoffRequested").get(controller) as AtomicBoolean).set(true)
        val timeout = CarPlayController::class.java.getDeclaredMethod("handleWirelessHandoffTimeout", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        timeout.invoke(controller, 0)
        timeout.invoke(controller, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue((RuntimeEnvironment.getApplication() as RecordingApplication).linkEnds.isEmpty())
        controller.endConnection("rebuild", 2_000)
        controller.close()
        shadowOf(Looper.getMainLooper()).idle()
        val ends = (RuntimeEnvironment.getApplication() as RecordingApplication).linkEnds
        assertEquals(1, ends.size)
        assertEquals("wireless_run", ends.single().link)
        assertEquals("timeout", ends.single().by)
        assertEquals("handoff_timeout", ends.single().cause)
        assertEquals("rebuild", ends.single().facts["next"])
        assertEquals("2000", ends.single().facts["nextInMs"])
        assertEquals("wireless_handoff limitMs=45000", ends.single().detail)
    }

    @Test
    @Config(application = RecordingApplication::class)
    fun aclBroadcastsTrackOnlyTheSelectedPhone() {
        start(CarPlayTransport.WIRELESS)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val target = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        val other = adapter.getRemoteDevice("11:22:33:44:55:66")
        field("bluetoothTarget").set(controller, target)
        CarPlayController::class.java.getDeclaredMethod("registerLinkReceiver").apply { isAccessible = true }.invoke(controller)
        val app = RuntimeEnvironment.getApplication() as RecordingApplication
        assertEquals("unknown", field("bluetoothAcl").get(controller))
        fun broadcast(action: String, device: BluetoothDevice) {
            app.sendBroadcast(Intent(action).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
            shadowOf(Looper.getMainLooper()).idle()
        }
        broadcast(BluetoothDevice.ACTION_ACL_CONNECTED, other)
        assertEquals("unknown", field("bluetoothAcl").get(controller))
        broadcast(BluetoothDevice.ACTION_ACL_CONNECTED, target)
        assertEquals("connected", field("bluetoothAcl").get(controller))
        broadcast(BluetoothDevice.ACTION_ACL_DISCONNECTED, target)
        assertEquals("disconnected", field("bluetoothAcl").get(controller))
    }

    /** A session runs, then a replaced session and the current one end. */
    private fun runSession() {
        val listener = sessionListener()
        val stale = phone()
        val current = phone()
        listener.onSessionActive(current)
        route(Iap2Frame(0x1234, byteArrayOf(1, 2, 3)))
        listener.onCommand(current, "requestUI", mapOf("url" to "maps:"))
        listener.onSessionEnded(stale)
        listener.onSessionEnded(current)
    }

    @Test
    @Config(application = RecordingApplication::class)
    fun applicationObserverReceivesSessionFramesAndCommands() {
        start()
        runSession()

        val application = RuntimeEnvironment.getApplication() as RecordingApplication
        // Only the active session's end is reported; the host listener still sees both.
        assertEquals(listOf("active", "frame:4660:3", "command:requestUI:maps:", "ended"), application.events)
        assertEquals(listOf("active", "command:requestUI", "ended", "ended"), hostEvents)
    }

    @Test
    @Config(application = ThrowingApplication::class)
    fun throwingObserverLeavesTheControllerPathIntact() {
        start()
        runSession()

        assertEquals(listOf("active", "command:requestUI", "ended", "ended"), hostEvents)
        assertNull(activeSession())
    }

    @Test
    fun applicationWithoutProviderKeepsTheControllerPath() {
        start()
        runSession()

        assertEquals(listOf("active", "command:requestUI", "ended", "ended"), hostEvents)
        assertNull(activeSession())
    }

    @Test
    @Config(application = HostUiApplication::class)
    fun handledHostUiRequestSkipsLauncherAndKeepsEventDelivery() {
        requestHostUiAndCommand()

        val application = RuntimeEnvironment.getApplication() as HostUiApplication
        assertEquals(1, application.requests)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    @Config(application = HostUiApplication::class)
    fun declinedHostUiRequestOpensLauncherAndKeepsEventDelivery() {
        val application = RuntimeEnvironment.getApplication() as HostUiApplication
        application.handled = false

        requestHostUiAndCommand()

        assertEquals(1, application.requests)
        assertLauncherOpened()
    }

    @Test
    @Config(application = HostUiApplication::class)
    fun throwingHostUiHandlerOpensLauncherAndKeepsEventDelivery() {
        val application = RuntimeEnvironment.getApplication() as HostUiApplication
        application.failure = IllegalStateException("host UI")

        requestHostUiAndCommand()

        assertEquals(1, application.requests)
        assertLauncherOpened()
    }

    @Test
    @Config(application = RecordingApplication::class)
    fun applicationWithoutHostUiHandlerOpensLauncherAndKeepsEventDelivery() {
        requestHostUiAndCommand()

        assertLauncherOpened()
    }

    private fun requestHostUiAndCommand() {
        start()
        val session = phone()
        val listener = sessionListener()
        listener.onHostUiRequested(session)
        listener.onCommand(session, "requestUI", emptyMap())

        val application = RuntimeEnvironment.getApplication() as RecordingApplication
        assertEquals(listOf("host-ui", "command:requestUI"), hostEvents)
        assertEquals(listOf("command:requestUI:null"), application.events)
    }

    private fun assertLauncherOpened() {
        val application = RuntimeEnvironment.getApplication() as RecordingApplication
        val intent = requireNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            controller.close()
            controller.awaitClosed(2_000)
        }
        sessions.forEach { it.close() }
    }
}
