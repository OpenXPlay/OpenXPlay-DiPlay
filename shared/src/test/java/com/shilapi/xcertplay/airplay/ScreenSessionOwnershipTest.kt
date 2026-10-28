// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-27
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ScreenSessionOwnershipTest {
    private class RecordingSink : MediaSink {
        val activeScreens = mutableMapOf<Int, Boolean>()
        val states = mutableListOf<Boolean>()
        val active: Boolean get() = activeScreens[110] == true
        val recoveries = mutableMapOf<Int, () -> Unit>()
        val diagnostics = mutableMapOf<Int, (String) -> Unit>()
        val deliveries = mutableListOf<String>()
        override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recoveries[type] = handler }
        override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostics[type] = handler }
        override fun onVideoCodec(type: Int, codec: VideoCodec) { deliveries += "codec:$type:$codec" }
        override fun onVideoConfig(type: Int, codecData: ByteArray) { deliveries += "config:$type" }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
            deliveries += "timed:$type:$senderNanos:$arrivalNanos"
        }
        val frames = mutableListOf<Int>()
        override fun onScreenStreamActive(type: Int, active: Boolean) { activeScreens[type] = active; if (type == 110) states += active }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frames += naluBytes[0].toInt() }
    }
    private fun session(engine: CarPlayMediaEngine, observer: AirPlaySessionListener = object : AirPlaySessionListener {}) = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = observer, media = engine,
    ).also { peer ->
        PairVerify::class.java.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(peer.pairVerify, ByteArray(32) { it.toByte() })
    }
    @Suppress("UNCHECKED_CAST")
    private fun listener(engine: CarPlayMediaEngine, peer: AirPlaySession, type: Int = 110): ScreenStream.Listener {
        val streams = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as Map<CarPlayMediaEngine.StreamKey, Closeable>
        val screen = streams.getValue(CarPlayMediaEngine.StreamKey(peer, type)) as ScreenStream
        return ScreenStream::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(screen) as ScreenStream.Listener
    }
    @Test fun closingOldSessionMustNotDeactivateTheNewMainScreen() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val old = session(engine)
        val fresh = session(engine)
        try {
            assertNotNull(engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L)))
            assertNotNull(engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L)))
            listener(engine, fresh).onFrame(byteArrayOf(2))
            old.close()
            assertFalse("new control connection should remain open", fresh.isClosed)
            assertEquals(listOf(2), sink.frames)
            assertTrue("closing old control session deactivated the new live screen", sink.active)
        } finally { old.close(); fresh.close() }
    }
    @Test fun oldSessionFramesMustNotOverwriteTheNewMainScreen() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val old = session(engine)
        val fresh = session(engine)
        try {
            assertNotNull(engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L)))
            val previous = listener(engine, old)
            assertNotNull(engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L)))
            listener(engine, fresh).onFrame(byteArrayOf(2))
            previous.onFrame(byteArrayOf(1))
            assertEquals("old session still delivers after a new main screen starts", listOf(2), sink.frames)
        } finally { old.close(); fresh.close() }
    }
    @Test fun closingTheOnlyScreenMustStillDeactivateIt() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val peer = session(engine)
        try {
            assertNotNull(engine.onScreen(peer, 110, mapOf("streamConnectionID" to 1L)))
            assertTrue(sink.active)
            peer.close()
            assertFalse(sink.active)
        } finally { peer.close() }
    }
    @Test fun closingAnOldSessionWithoutAScreenMustLeaveTheNewScreenActive() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val old = session(engine)
        val fresh = session(engine)
        try {
            assertNotNull(engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L)))
            old.close()
            assertTrue(sink.active)
        } finally { old.close(); fresh.close() }
    }

    @Test fun oldTeardownAndDelayedEndCannotStopTheReplacement() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val old = session(engine)
        val fresh = session(engine)
        try {
            engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
            val previous = listener(engine, old)
            engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L))
            engine.onTeardown(old, 110)
            previous.onClosed(null)
            assertTrue(sink.active)
            assertFalse(fresh.isClosed)
            listener(engine, fresh).onFrame(byteArrayOf(2))
            assertEquals(listOf(2), sink.frames)
        } finally { old.close(); fresh.close() }
    }

    @Test fun replacedStreamEndCannotCloseTheSameControlSession() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val peer = session(engine)
        try {
            engine.onScreen(peer, 110, mapOf("streamConnectionID" to 1L))
            val previous = listener(engine, peer)
            engine.onScreen(peer, 110, mapOf("streamConnectionID" to 2L))
            previous.onClosed(null)
            assertFalse(peer.isClosed)
            assertTrue(sink.active)
            listener(engine, peer).onFrame(byteArrayOf(2))
            assertEquals(listOf(2), sink.frames)
        } finally { peer.close() }
    }

    @Test fun everyOldDeliveryAndSavedCallbackIsFenced() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val observed = mutableListOf<String>()
        val old = session(engine, object : AirPlaySessionListener {
            override fun onVideoFrameRendered(session: AirPlaySession) { observed += "rendered" }
            override fun onDebugLog(message: String) { observed += message }
            override fun onStartupMilestone(session: AirPlaySession, name: String) { observed += name }
        })
        val fresh = session(engine)
        try {
            engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
            val previous = listener(engine, old)
            val recovery = sink.recoveries.getValue(110)
            val diagnostic = sink.diagnostics.getValue(110)
            engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L))
            observed.clear()
            previous.onCodec(VideoCodec.H265)
            previous.onConfig(byteArrayOf(1))
            previous.onFrame(byteArrayOf(1))
            previous.onFrame(byteArrayOf(1), 10, 20)
            recovery()
            diagnostic("first frame rendered")
            assertTrue(sink.deliveries.isEmpty())
            assertTrue(sink.frames.isEmpty())
            assertTrue(observed.isEmpty())
        } finally { old.close(); fresh.close() }
    }

    @Test fun aRetiredSessionCannotReclaimTheNewMainScreen() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val old = session(engine)
        val fresh = session(engine)
        try {
            engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
            engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L))
            assertNull(engine.onScreen(old, 110, mapOf("streamConnectionID" to 3L)))
            listener(engine, fresh).onFrame(byteArrayOf(2))
            assertEquals(listOf(2), sink.frames)
            assertTrue(sink.active)
        } finally { old.close(); fresh.close() }
    }

    @Test fun mainAndClusterOwnershipAreIndependent() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val old = session(engine)
        val fresh = session(engine)
        try {
            engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
            engine.onScreen(old, 111, mapOf("streamConnectionID" to 2L))
            engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 3L))
            listener(engine, old, 111).onFrame(byteArrayOf(1), 10, 20)
            assertEquals(listOf("timed:111:10:20"), sink.deliveries)
            old.close()
            assertTrue(sink.active)
            assertEquals(false, sink.activeScreens[111])
            listener(engine, fresh).onFrame(byteArrayOf(2))
            assertEquals(listOf(2), sink.frames)
        } finally { old.close(); fresh.close() }
    }

    @Test fun currentTeardownCanReopenOnTheSameControl() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val peer = session(engine)
        try {
            engine.onScreen(peer, 110, mapOf("streamConnectionID" to 1L))
            engine.onTeardown(peer, 110)
            assertFalse(sink.active)
            assertNotNull(engine.onScreen(peer, 110, mapOf("streamConnectionID" to 2L)))
            assertTrue(sink.active)
        } finally { peer.close() }
    }

    @Test fun closedSessionCannotStartOrInstallCallbacks() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val peer = session(engine)
        peer.close()
        assertNull(engine.onScreen(peer, 110, mapOf("streamConnectionID" to 1L)))
        assertFalse(sink.active)
        assertTrue(sink.recoveries.isEmpty())
        assertTrue(sink.diagnostics.isEmpty())
    }

    @Test fun reentrantCloseDuringCallbackInstallationCannotPublishALiveScreen() {
        val events = mutableListOf<Boolean>()
        lateinit var peer: AirPlaySession
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { peer.close() }
            override fun onScreenStreamActive(type: Int, active: Boolean) { events += active }
        })
        peer = session(engine)
        assertNull(engine.onScreen(peer, 110, mapOf("streamConnectionID" to 1L)))
        assertFalse(events.contains(true))
    }

    @Test fun reentrantCloseAtFirstFrameCannotDeliverAfterTheStop() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        val peer = session(engine, object : AirPlaySessionListener {
            override fun onStartupMilestone(session: AirPlaySession, name: String) { session.close() }
        })
        try {
            engine.onScreen(peer, 110, mapOf("streamConnectionID" to 1L))
            listener(engine, peer).onFrame(byteArrayOf(1))
            assertFalse(sink.active)
            assertTrue(sink.frames.isEmpty())
        } finally { peer.close() }
    }

    @Test fun allPolicyCombinationsHaveRealEnabledAndDisabledBehavior() {
        for (bits in 0..7) {
            val policy = ScreenSessionPolicy(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            val sink = RecordingSink()
            val engine = CarPlayMediaEngine(sink, screenSessionPolicy = policy)
            val old = session(engine)
            val fresh = session(engine)
            try {
                engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
                val previous = listener(engine, old)
                engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L))
                previous.onFrame(byteArrayOf(1))
                listener(engine, fresh).onFrame(byteArrayOf(2))
                assertEquals(policy.toString(),
                    if (policy.isolateSessions || policy.resetOnReconnect) listOf(2) else listOf(1, 2), sink.frames)
                assertEquals(policy.toString(),
                    if (policy.resetOnReconnect) listOf(true, false, true) else listOf(true, true), sink.states)
                old.close()
                assertEquals(policy.toString(), policy.isolateSessions || policy.resetOnReconnect, sink.active)
                assertFalse(fresh.isClosed)
            } finally { old.close(); fresh.close() }
        }
    }

    @Test fun isolationOptionControlsRetiredSetupAndUnknownTeardown() {
        for (isolate in listOf(false, true)) {
            val sink = RecordingSink()
            val engine = CarPlayMediaEngine(sink, screenSessionPolicy = ScreenSessionPolicy(isolateSessions = isolate))
            val old = session(engine)
            val fresh = session(engine)
            try {
                engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
                engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L))
                engine.onTeardown(old, 110)
                assertEquals(isolate, sink.active)
                val reclaimed = engine.onScreen(old, 110, mapOf("streamConnectionID" to 3L))
                if (isolate) assertNull(reclaimed) else assertNotNull(reclaimed)
            } finally { old.close(); fresh.close() }
        }
    }

    @Test fun sameControlReconfigurationPreservesTheSessionUnderEveryPolicy() {
        for (bits in 0..7) {
            val policy = ScreenSessionPolicy(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
            val sink = RecordingSink()
            val engine = CarPlayMediaEngine(sink, screenSessionPolicy = policy)
            val peer = session(engine)
            try {
                engine.onScreen(peer, 110, mapOf("streamConnectionID" to 1L))
                engine.onScreen(peer, 110, mapOf("streamConnectionID" to 2L))
                assertEquals(policy.toString(), listOf(true, true), sink.states)
            } finally { peer.close() }
        }
    }

    @Test fun serializationPolicyControlsActualEngineHandoffDuringAFrame() {
        for (serialize in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val replacing = CountDownLatch(1)
            val replaced = CountDownLatch(1)
            val engine = CarPlayMediaEngine(object : MediaSink {
                override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }, screenSessionPolicy = ScreenSessionPolicy(serializeDelivery = serialize))
            val old = session(engine)
            val fresh = session(engine)
            engine.onScreen(old, 110, mapOf("streamConnectionID" to 1L))
            val previous = listener(engine, old)
            val delivery = Thread { previous.onFrame(byteArrayOf(1)) }
            val replacement = Thread {
                replacing.countDown()
                engine.onScreen(fresh, 110, mapOf("streamConnectionID" to 2L))
                replaced.countDown()
            }
            try {
                delivery.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                replacement.start()
                assertTrue(replacing.await(5, TimeUnit.SECONDS))
                if (serialize) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (replacement.state != Thread.State.BLOCKED && replaced.count > 0 && System.nanoTime() < deadline) Thread.yield()
                    assertEquals(Thread.State.BLOCKED, replacement.state)
                    assertEquals(1L, replaced.count)
                } else assertTrue(replaced.await(5, TimeUnit.SECONDS))
                release.countDown()
                delivery.join(5_000)
                replacement.join(5_000)
                assertFalse(delivery.isAlive || replacement.isAlive)
            } finally {
                release.countDown()
                delivery.join(5_000)
                replacement.join(5_000)
                old.close(); fresh.close()
            }
        }
    }
}
