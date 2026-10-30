// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-30
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.orchestration.CarPlayLinkEnd
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayLinkEndTest {
    private val ends = mutableListOf<CarPlayLinkEnd>()
    private val logs = mutableListOf<String>()

    private fun session(input: InputStream, failOutput: Boolean = false) = AirPlaySession(
        object : Socket() {
            override fun getInputStream() = input
            override fun getOutputStream(): ByteArrayOutputStream {
                if (failOutput) throw IOException("output unavailable")
                return ByteArrayOutputStream()
            }
        }, AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
        object : AirPlaySessionListener {
            override fun onLinkEnded(end: CarPlayLinkEnd) { ends += end }
            override fun onDebugLog(message: String) { logs += message }
        }, object : AirPlayMediaHandler {})

    private fun run(session: AirPlaySession) {
        AirPlaySession::class.java.getDeclaredMethod("runControl").apply { isAccessible = true }.invoke(session)
        session.close()
    }

    @Test fun peerEofAfterPairVerifyIsVerifyOnlyAndIncludesTheControlFacts() {
        lateinit var phone: AirPlaySession
        phone = session(object : InputStream() {
            override fun read(): Int {
                phone.cipher = ControlCipher(ByteArray(32), ByteArray(32))
                phone.startupMilestone("pair_verified")
                return -1
            }
        })
        phone.connectionNumber = 7
        run(phone)
        val end = ends.single()
        assertEquals("peer", end.by)
        assertEquals("peer_eof", end.cause)
        assertEquals("pair_verified", end.reached)
        assertEquals("true", end.facts["verifyOnly"])
        assertEquals("true", end.facts["pairVerified"])
        assertEquals("false", end.facts["record"])
        assertEquals("7", end.facts["connection"])
        assertEquals(11, end.facts.size)
        assertTrue(logs.any { it.contains("airplay control closing reason=peer EOF") && it.contains("verifyOnly=true") })
    }

    @Test fun setupAndRecordClearVerifyOnlyAndTrackOnlyFixedRequestRoutes() {
        val phone = session(ByteArrayInputStream((
            "SETUP /private-name?token=secret RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n" +
            "RECORD /private-name RTSP/1.0\r\nCSeq: 2\r\n\r\n").toByteArray()))
        run(phone)
        val end = ends.single()
        assertEquals("2", end.facts["requests"])
        assertEquals("record", end.facts["lastRequest"])
        assertEquals("200", end.facts["lastStatus"])
        assertEquals("true", end.facts["record"])
        assertEquals("false", end.facts["verifyOnly"])
        assertEquals("session_active", end.reached)
        assertFalse(end.toString().contains("private-name") || end.toString().contains("secret"))
    }

    @Test fun resetAndHealthTimeoutAreDistinctFromLocalClose() {
        for ((message, cause, by) in listOf(
            Triple("recv: ECONNRESET", "peer_reset", "peer"),
            Triple("recv: ETIMEDOUT", "health_timeout", "timeout"))) {
            ends.clear()
            run(session(object : InputStream() { override fun read(): Int = throw SocketException(message) }))
            assertEquals(cause, ends.single().cause)
            assertEquals(by, ends.single().by)
        }
        ends.clear()
        val local = session(ByteArrayInputStream(byteArrayOf()))
        local.close()
        run(local)
        assertEquals("local", ends.single().by)
        assertEquals("teardown", ends.single().cause)
    }

    @Test fun decryptAndStreamAcquisitionFailuresReportOnce() {
        val phone = session(ByteArrayInputStream(ByteArray(18)))
        phone.cipher = ControlCipher(ByteArray(32), ByteArray(32))
        run(phone)
        assertEquals("decrypt_failed", ends.single().cause)
        ends.clear()
        run(session(ByteArrayInputStream(byteArrayOf()), failOutput = true))
        assertEquals("io_error", ends.single().cause)
        assertTrue(ends.single().detail.contains("output unavailable"))
    }
}
