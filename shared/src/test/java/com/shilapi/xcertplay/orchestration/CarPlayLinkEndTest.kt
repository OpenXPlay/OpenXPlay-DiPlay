// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-30
package com.shilapi.xcertplay.orchestration

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.AEADBadTagException
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class CarPlayLinkEndTest {
    @Test fun classifiesEofResetDecryptAndKernelHealthTimeout() {
        assertEquals("peer_eof", CarPlayLinkDiagnostics.failureCause(EOFException()))
        assertEquals("peer_reset", CarPlayLinkDiagnostics.failureCause(IOException("read", SocketException("recv: ECONNRESET"))))
        assertEquals("decrypt_failed", CarPlayLinkDiagnostics.failureCause(AEADBadTagException("bad tag")))
        assertEquals("health_timeout", CarPlayLinkDiagnostics.failureCause(SocketException("recv: ETIMEDOUT")))
        assertEquals("read_timeout", CarPlayLinkDiagnostics.failureCause(SocketTimeoutException("Read timed out")))
        assertEquals("io_error", CarPlayLinkDiagnostics.failureCause(IOException("broken pipe")))
    }

    @Test fun oneReportSurvivesConcurrentCloseAndFailureWithLifetimeCounters() {
        var now = 1_000_000L
        val ends = CopyOnWriteArrayList<CarPlayLinkEnd>()
        val link = CarPlayLinkDiagnostics("stream_video", { ends += it }, { now })
        link.reached = "receiving"
        now = 6_000_000
        link.received(128)
        link.received(256)
        now = 11_000_000
        val start = CountDownLatch(1)
        val workers = (1..8).map { n -> thread {
            start.await()
            if (n % 2 == 0) link.end(counters = true) else link.failed(IOException("closed"), counters = true)
        } }
        start.countDown()
        workers.forEach { it.join(2_000) }
        assertTrue(workers.none { it.isAlive })
        assertEquals(1, ends.size)
        assertEquals(10L, ends.single().ageMillis)
        assertEquals(5L, ends.single().idleMillis)
        assertEquals(mapOf("bytes" to "384", "packets" to "2"), ends.single().facts)
    }

    @Test fun localCloseAndThrowingReporterCannotBecomeANewFailure() {
        var calls = 0
        val link = CarPlayLinkDiagnostics("usb", { calls++; throw IllegalStateException("observer") })
        link.end(cause = "stopped")
        link.failed(IOException("Socket closed"))
        link.end()
        assertEquals(1, calls)
    }

    @Test fun notingACauseWaitsForADecisionAndKeepsTheFirstFailureSnapshot() {
        var now = 1_000_000L
        val ends = mutableListOf<CarPlayLinkEnd>()
        val link = CarPlayLinkDiagnostics("wireless_run", { ends += it }, { now })
        link.reached = "connecting_bluetooth"
        now = 6_000_000
        link.noteFailure(IOException("connect failed"))
        link.note("local", "teardown", "cleanup")
        assertTrue(ends.isEmpty())
        assertFalse(link.isEnded)
        now = 11_000_000
        link.reached = "stopped"
        link.end(cause = "rebuild", facts = mapOf("next" to "rebuild", "nextInMs" to "2000"))
        link.end(cause = "stopped")
        assertEquals("io_error", ends.single().cause)
        assertEquals("IOException: connect failed", ends.single().detail)
        assertEquals("connecting_bluetooth", ends.single().reached)
        assertEquals(5L, ends.single().ageMillis)
        assertEquals(mapOf("next" to "rebuild", "nextInMs" to "2000"), ends.single().facts)
    }

    @Test fun detailsAreBoundedSingleLineAndRemoveKnownPrivateValues() {
        val detail = CarPlayLinkDiagnostics.failureDetail(IOException(
            "read failed at AA:BB:CC:DD:EE:FF for Jane's iPhone\npassphrase=private; " + "f".repeat(64) + "z".repeat(200)),
            listOf("Jane's iPhone"))
        assertEquals(160, detail.length)
        assertFalse(detail.contains('\n'))
        assertFalse(detail.contains("AA:BB") || detail.contains("Jane") || detail.contains("passphrase") || detail.contains("ffff"))
        assertTrue(detail.startsWith("IOException: read failed"))
        assertEquals("IOException: [private]", CarPlayLinkDiagnostics.failureDetail(IOException("ssid=Jane's private network")))
    }
}
