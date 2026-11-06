package com.shilapi.xcertplay.network

import android.system.ErrnoException
import android.system.OsConstants
import java.net.BindException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

internal class AirPlayDualBindException(val diagnostics: String) :
    BindException("No common AirPlay port available for the selected interface addresses")

/**
 * Binds the AirPlay control listener, falling back when the preferred port is already taken.
 *
 * Some head units ship a factory CarPlay daemon that permanently listens on the default AirPlay
 * port (7000) on every interface, so binding DiPlay's listener fails with EADDRINUSE. The bound
 * port is advertised to the iPhone through Bonjour and iAP2, so any free port works.
 */
object AirPlayPortSelector {
    private const val MAX_BIND_FAILURES = 8
    private val BIND_ERRNO = Regex("""\bbind failed:\s*(E[A-Z][A-Z0-9_]{2,20})\b""")

    /** Ports tried, in order, after the preferred port; an ephemeral port is the last resort. */
    val FALLBACK_PORTS: IntRange = 7001..7010

    /** Specific per-family listeners avoid relying on a platform's IPV6_V6ONLY default. */
    fun bindAll(
        addresses: List<InetAddress>,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (Int, Int) -> Unit = { _, _ -> },
    ): List<ServerSocket> {
        require(addresses.isNotEmpty()) { "At least one listener address is required" }
        val candidates = listOf(preferredPort) + fallbackPorts.filter { it != preferredPort } + List(4) { 0 }
        val listenerAddresses = addresses.distinct()
        val failures = linkedMapOf<Pair<InetAddress, String>, BindException>()
        candidates@ for (candidate in candidates) {
            val servers = mutableListOf<ServerSocket>()
            for (address in listenerAddresses) {
                try {
                    servers.add(bindPort(address, servers.firstOrNull()?.localPort ?: candidate))
                } catch (error: Throwable) {
                    servers.forEach { closeAfterFailure(it, error) }
                    if (error !is BindException) throw error
                    if (failures.size < MAX_BIND_FAILURES) {
                        failures.putIfAbsent(address to bindFailureCause(error), error)
                    }
                    continue@candidates
                }
            }
            try {
                val port = servers.first().localPort
                if (preferredPort != 0 && port != preferredPort) onFallback(preferredPort, port)
                return servers
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                throw error
            }
        }
        val diagnostics = failures.keys.joinToString("; ") { (address, cause) ->
            val family = if (address is Inet6Address) "IPv6" else "IPv4"
            val scope = (address as? Inet6Address)?.scopeId?.takeIf { it > 0 }
            val index = scope ?: runCatching { NetworkInterface.getByInetAddress(address)?.index }.getOrNull()
            "family=$family interfaceIndex=${index?.takeIf { it > 0 } ?: "unknown"} $cause"
        }
        throw AirPlayDualBindException(diagnostics).apply { failures.values.forEach(::addSuppressed) }
    }

    private fun bindFailureCause(error: BindException): String {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(4).toList()
        val native = chain.filterIsInstance<ErrnoException>().firstOrNull()
        val errno = native?.let { OsConstants.errnoName(it.errno) ?: it.errno.toString() }
            ?: chain.firstNotNullOfOrNull { failure ->
                val message = failure.message.orEmpty()
                BIND_ERRNO.find(message)?.groupValues?.get(1) ?: when {
                    message.contains("Address already in use", ignoreCase = true) -> "EADDRINUSE"
                    message.contains("assign requested address", ignoreCase = true) -> "EADDRNOTAVAIL"
                    message.contains("Permission denied", ignoreCase = true) -> "EACCES"
                    else -> null
                }
            }
        val cause = (native ?: chain.last()).javaClass.simpleName
        return "cause=$cause errno=${errno ?: "unknown"}"
    }

    fun bind(
        address: InetAddress,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (busyPort: Int, boundPort: Int) -> Unit = { _, _ -> },
    ): ServerSocket {
        tryBind(address, preferredPort)?.let { return it }
        for (port in fallbackPorts) {
            if (port == preferredPort) continue
            tryBind(address, port)?.let { server ->
                return reportFallback(server, preferredPort, onFallback)
            }
        }
        return reportFallback(bindPort(address, 0), preferredPort, onFallback)
    }

    private fun tryBind(address: InetAddress, port: Int): ServerSocket? = try {
        bindPort(address, port)
    } catch (_: BindException) {
        null
    }

    private fun bindPort(address: InetAddress, port: Int): ServerSocket {
        val server = ServerSocket()
        return try {
            server.bind(InetSocketAddress(address, port))
            server
        } catch (error: Throwable) {
            closeAfterFailure(server, error)
            throw error
        }
    }

    private fun reportFallback(
        server: ServerSocket,
        preferredPort: Int,
        onFallback: (Int, Int) -> Unit,
    ): ServerSocket = try {
        onFallback(preferredPort, server.localPort)
        server
    } catch (error: Throwable) {
        // Ownership transfers to the caller only after notification succeeds.
        closeAfterFailure(server, error)
        throw error
    }

    private fun closeAfterFailure(server: ServerSocket, error: Throwable) {
        try {
            server.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
    }
}
