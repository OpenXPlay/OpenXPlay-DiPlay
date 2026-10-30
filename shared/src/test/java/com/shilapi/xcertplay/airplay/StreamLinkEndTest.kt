// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-30
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.orchestration.CarPlayLinkEnd
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StreamLinkEndTest {
    @Test fun screenEofThenLocalCleanupReportsOneEndWithCounters() {
        val ends = CopyOnWriteArrayList<CarPlayLinkEnd>()
        val ended = CountDownLatch(1)
        val stream = ScreenStream(ByteArray(32)).apply {
            onLinkEnded = { ends += it; ended.countDown() }
        }
        try {
            val port = stream.listen(object : ScreenStream.Listener {})
            Socket(InetAddress.getLoopbackAddress(), port).use { it.shutdownOutput() }
            assertTrue(ended.await(2, TimeUnit.SECONDS))
            stream.close()
            assertEquals(1, ends.size)
            assertEquals("stream_video", ends.single().link)
            assertEquals("peer_eof", ends.single().cause)
            assertEquals("0", ends.single().facts["packets"])
        } finally { stream.close() }
    }

    @Test fun tunnelReportsEachAcceptedTcpConnectionIncludingCleanEof() {
        val ends = CopyOnWriteArrayList<CarPlayLinkEnd>()
        val closed = java.util.concurrent.LinkedBlockingQueue<CarPlayLinkEnd>()
        val tunnel = IapTunnel(ByteArray(32), InetAddress.getLoopbackAddress()).apply {
            onLinkEnded = { ends += it; closed.offer(it) }
        }
        try {
            val port = tunnel.listen(object : IapTunnel.Listener {})
            repeat(2) {
                Socket(InetAddress.getLoopbackAddress(), port).use { it.shutdownOutput() }
                assertNotNull(closed.poll(2, TimeUnit.SECONDS))
            }
            tunnel.close()
            assertEquals(2, ends.size)
            assertTrue(ends.all { it.link == "stream_other" && it.cause == "peer_eof" && it.by == "peer" })
        } finally { tunnel.close() }
    }
}
