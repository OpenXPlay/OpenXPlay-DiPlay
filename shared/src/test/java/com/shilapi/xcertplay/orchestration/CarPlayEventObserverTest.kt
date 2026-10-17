// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-16
package com.shilapi.xcertplay.orchestration

import android.app.Application
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayEventObserverTest {
    class RecordingApplication : Application(), CarPlayEventObserverProvider {
        val events = mutableListOf<String>()
        override val carPlayEventObserver = object : CarPlayEventObserver {
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

    class ThrowingApplication : Application(), CarPlayEventObserverProvider {
        override val carPlayEventObserver = object : CarPlayEventObserver {
            override fun onSessionActive() = throw IllegalStateException("observer")
            override fun onSessionEnded() = throw IllegalStateException("observer")
            override fun onIap2Frame(frame: Iap2Frame) = throw IllegalStateException("observer")
            override fun onCommand(type: String, params: Map<String, Any?>) =
                throw IllegalStateException("observer")
        }
    }

    private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
    private val hostEvents = mutableListOf<String>()
    private val sessions = mutableListOf<AirPlaySession>()
    private lateinit var controller: CarPlayController

    private fun start() {
        controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(),
            object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) { hostEvents += "active" }
                override fun onSessionEnded(session: AirPlaySession) { hostEvents += "ended" }
                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                    hostEvents += "command:$type"
                }
            },
            object : AirPlayMediaHandler {}, {})
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

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            controller.close()
            controller.awaitClosed(2_000)
        }
        sessions.forEach { it.close() }
    }
}
