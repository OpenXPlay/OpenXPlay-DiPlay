// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-27
package com.shilapi.xcertplay.network

import android.net.TrafficStats
import android.os.Process
import java.io.File
import java.io.IOException

/**
 * Optional kernel receive counters sampled by the existing wireless observer, never a media thread.
 * Android/OEM policy may hide these files; an inaccessible source is tried only once per run and reported once per process.
 * App UID traffic is the fallback when kernel counters cannot be read.
 * UDP counters cover the device network namespace, not a particular interface, app or RTP stream.
 */
internal class WirelessReceiveDiagnostics(
    interfaceName: String?,
    private val read: (String, Int) -> String = ::readBoundedDiagnosticFile,
    private val nowNs: () -> Long = System::nanoTime,
    @Suppress("UNUSED_PARAMETER") suppressUnavailable: Boolean = false,
    private val uidCounters: () -> Map<String, Long> = {
        mapOf("RxBytes" to TrafficStats.getUidRxBytes(Process.myUid()), "TxBytes" to TrafficStats.getUidTxBytes(Process.myUid()))
    },
) {
    private val interfaceSource = CounterSource("ifaceRx") {
        if (interfaceName == null || !INTERFACE_NAME.matches(interfaceName)) throw IOException()
        linkedMapOf<String, Long>().apply {
            INTERFACE_COUNTERS.forEach { (file, label) ->
                put(label, counter(read("/sys/class/net/$interfaceName/statistics/$file", 32).trim()))
            }
        }
    }
    private val udp4Source = CounterSource("udp4") {
        parseUdp4(read("/proc/net/snmp", MAX_PROC_BYTES))
    }
    private val udp6Source = CounterSource("udp6") {
        parseUdp6(read("/proc/net/snmp6", MAX_PROC_BYTES))
    }
    private val uidSource = CounterSource("uid") {
        uidCounters().also { if (it.values.any { value -> value < 0 }) throw IOException() }
    }
    private var previousSampleNs: Long? = null

    fun snapshot(): String {
        val now = nowNs()
        val elapsed = previousSampleNs?.let { ((now - it).coerceAtLeast(0)) / 1_000_000 }
        previousSampleNs = now
        val prefix = "receiveCounters windowMs=${elapsed ?: "baseline"} udpScope=device "
        // Keep every source on its own short exported line, including full-width Long deltas.
        val sources = listOf(interfaceSource, udp4Source, udp6Source)
        val lines = sources.mapNotNull { source -> source.snapshot()?.let { prefix + it } }.toMutableList()
        if (sources.any { it.isUnavailable }) uidSource.snapshot()?.let {
            lines += "receiveCounters windowMs=${elapsed ?: "baseline"} uidScope=app $it"
        }
        return lines.joinToString("\n")
    }

    private class CounterSource(private val label: String, private val sample: () -> Map<String, Long>) {
        private var previous: Map<String, Long>? = null
        private var unavailable: String? = null

        val isUnavailable: Boolean get() = unavailable != null

        fun snapshot(): String? {
            if (unavailable != null) return null
            val current = try { sample() } catch (error: Exception) {
                val type = error.javaClass.simpleName
                unavailable = type
                return if (unavailableSources.add(label)) "$label=unavailable failureClass=$type" else null
            }
            val before = previous
            previous = current
            if (before == null) return "$label=baseline"
            // Interface recreation or counter wrap must never appear as a negative loss count.
            if (current.keys != before.keys || current.any { (key, value) -> value < before.getValue(key) }) {
                return "$label=reset"
            }
            return "$label=sampled " + current.entries.joinToString(" ") { (key, value) ->
                "$label${key}Delta=${value - before.getValue(key)}"
            }
        }
    }

    companion object {
        internal val unavailableSources = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private const val MAX_PROC_BYTES = 16 * 1024
        private val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")
        private val INTERFACE_COUNTERS = linkedMapOf(
            "rx_packets" to "Packets", "rx_bytes" to "Bytes", "rx_dropped" to "Dropped",
            "rx_errors" to "Errors", "rx_missed_errors" to "MissedErrors",
        )
        private val UDP_COUNTERS = listOf("InDatagrams", "InErrors", "RcvbufErrors", "InCsumErrors")

        internal fun parseUdp4(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val rows = text.lineSequence().filter { it.startsWith("Udp:") }.take(3).toList()
            if (rows.size != 2) throw IOException()
            val names = rows[0].trim().split(Regex("\\s+")).drop(1)
            val values = rows[1].trim().split(Regex("\\s+")).drop(1)
            if (names.size != values.size || names.distinct().size != names.size) throw IOException()
            return selectedUdpCounters(names.zip(values).toMap())
        }

        internal fun parseUdp6(text: String): Map<String, Long> {
            if (text.length > MAX_PROC_BYTES) throw IOException()
            val values = linkedMapOf<String, String>()
            text.lineSequence().filter { it.startsWith("Udp6") }.forEach { row ->
                val parts = row.trim().split(Regex("\\s+"))
                if (parts.size != 2 || values.put(parts[0].removePrefix("Udp6"), parts[1]) != null) {
                    throw IOException()
                }
            }
            return selectedUdpCounters(values)
        }

        private fun selectedUdpCounters(values: Map<String, String>): Map<String, Long> =
            linkedMapOf<String, Long>().apply {
                UDP_COUNTERS.forEach { key ->
                    val value = values[key]
                    // Older kernels may omit checksum-specific accounting.
                    if (value == null && key == "InCsumErrors") return@forEach
                    put(key, counter(value ?: throw IOException()))
                }
            }

        private fun counter(value: String): Long =
            value.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException()
    }
}

/** Read only a small, fixed diagnostic file; never export its content or a read exception message. */
internal fun readBoundedDiagnosticFile(path: String, maxBytes: Int): String = File(path).inputStream().use { input ->
    val bytes = ByteArray(maxBytes + 1)
    var count = 0
    while (count < bytes.size) {
        val read = input.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) throw IOException()
        count += read
    }
    if (count > maxBytes) throw IOException()
    String(bytes, 0, count, Charsets.US_ASCII)
}
