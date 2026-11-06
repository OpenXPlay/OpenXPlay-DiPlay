package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class AirPlayDualListenerTest {
    private val addresses = listOf(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1"))

    @Test fun acceptsBothFamiliesOnTheSamePort() {
        val servers = AirPlayPortSelector.bindAll(addresses, 0)
        try {
            val port = servers.first().localPort
            assertEquals(listOf(port, port), servers.map { it.localPort })
            servers.forEachIndexed { index, server ->
                server.soTimeout = 1000
                Socket(addresses[index], port).use { client ->
                    server.accept().use { peer ->
                        assertEquals(addresses[index], peer.inetAddress)
                        assertTrue(client.isConnected)
                    }
                }
            }
        } finally { servers.forEach { it.close() } }
    }

    @Test fun collisionOnSecondFamilyClosesPartialBindAndFallsBackTogether() {
        ServerSocket(0, 50, addresses[1]).use { busy ->
            val servers = AirPlayPortSelector.bindAll(addresses, busy.localPort, emptyList())
            try {
                assertEquals(1, servers.map { it.localPort }.distinct().size)
                assertNotEquals(busy.localPort, servers.first().localPort)
                ServerSocket(busy.localPort, 50, addresses[0]).use { assertTrue(it.isBound) }
            } finally { servers.forEach { it.close() } }
        }
    }

    @Test fun exportedAttachFailureDistinguishesAnOccupiedPortFromAnUnavailableAddress() {
        val loopback = addresses[1]
        val unavailable = InetAddress.getByName("2001:db8::1")
        assertNull(NetworkInterface.getByInetAddress(unavailable))
        ServerSocket(0, 50, loopback).use { busy ->
            val failure = assertThrows(java.net.BindException::class.java) {
                AirPlayPortSelector.bindAll(listOf(loopback, unavailable), busy.localPort, emptyList())
            }
            assertEquals("No common AirPlay port available for the selected interface addresses", failure.message)
            val detail = CarPlayVpnService.attachFailure(failure)
            val interfaceIndex = NetworkInterface.getByInetAddress(loopback).index
            assertTrue(detail, detail.contains("family=IPv4 interfaceIndex=$interfaceIndex cause=BindException errno=EADDRINUSE"))
            assertTrue(detail, detail.contains("family=IPv6 interfaceIndex=unknown cause=BindException errno=EADDRNOTAVAIL"))
            assertFalse(detail, detail.contains(loopback.hostAddress!!))
            assertFalse(detail, detail.contains(unavailable.hostAddress!!))
            assertEquals(2, failure.suppressed.size)
            assertTrue(failure.suppressed.all { it is java.net.BindException })
        }
    }

    @Test fun failedFallbackNotificationReleasesBothListeners() {
        ServerSocket(0, 50, addresses[1]).use { busy ->
            var port = 0
            assertThrows(IllegalStateException::class.java) {
                AirPlayPortSelector.bindAll(addresses, busy.localPort, emptyList()) { _, bound ->
                    port = bound
                    throw IllegalStateException("notification failed")
                }
            }
            assertTrue(port > 0)
            addresses.forEach { address -> ServerSocket(port, 50, address).use { assertTrue(it.isBound) } }
        }
    }
}
