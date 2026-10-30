// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-30
package com.shilapi.xcertplay.orchestration

import java.io.EOFException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException

/** Why one CarPlay link ended or never opened. Tokens are fixed; [detail] is bounded free text hosts filter before storing. */
data class CarPlayLinkEnd(
    val link: String,
    val reached: String,
    val by: String,
    val cause: String,
    val detail: String = "",
    val ageMillis: Long = -1,
    val idleMillis: Long = -1,
    val facts: Map<String, String> = emptyMap(),
)

/** Owned by a socket or attempt, including its failed-open and local-close paths. */
internal class CarPlayLinkDiagnostics(
    private val link: String,
    private val report: (CarPlayLinkEnd) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val ended = AtomicBoolean(false)
    val isEnded: Boolean get() = ended.get()
    private val opened = nowNs()
    @Volatile var reached = "opening"
    @Volatile private var lastReceived = 0L
    private var bytes = 0L
    private var packets = 0L
    private var notedEnd: CarPlayLinkEnd? = null

    @Synchronized fun received(count: Int) {
        bytes += count
        packets++
        lastReceived = nowNs()
    }

    @Synchronized fun note(by: String, cause: String, detail: String = "") {
        if (ended.get() || notedEnd != null) return
        notedEnd = snapshot(by, cause, detail)
    }

    fun noteFailure(error: Throwable) = note(failureBy(error), failureCause(error), failureDetail(error))

    fun end(by: String = "local", cause: String = "teardown", detail: String = "",
        facts: Map<String, String> = emptyMap(), counters: Boolean = false, idleMillis: Long? = null) {
        val end = synchronized(this) {
            if (!ended.compareAndSet(false, true)) return
            val values = if (counters) facts + mapOf("bytes" to "$bytes", "packets" to "$packets") else facts.toMap()
            (notedEnd ?: snapshot(by, cause, detail, idleMillis)).copy(facts = values)
        }
        runCatching { report(end) }
    }

    fun failed(error: Throwable, facts: Map<String, String> = emptyMap(), counters: Boolean = false) {
        end(failureBy(error), failureCause(error), failureDetail(error), facts, counters)
    }

    private fun snapshot(by: String, cause: String, detail: String, idleMillis: Long? = null): CarPlayLinkEnd {
        val now = nowNs()
        return CarPlayLinkEnd(link, reached, by, cause, safeDetail(detail),
            ((now - opened) / 1_000_000).coerceAtLeast(0),
            idleMillis ?: if (lastReceived == 0L) -1 else ((now - lastReceived) / 1_000_000).coerceAtLeast(0))
    }

    companion object {
        private fun failureBy(error: Throwable): String = when (failureCause(error)) {
            "peer_eof", "peer_reset" -> "peer"
            "read_timeout", "health_timeout", "connect_timeout", "write_timeout" -> "timeout"
            else -> "local"
        }

        fun failureCause(error: Throwable): String {
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            return when {
                causes.any { it.message?.startsWith("CarPlay iAP tunnel peer did not connect") == true } -> "connect_timeout"
                causes.any { it.message?.contains("iap_event_ready limitMs=") == true } -> "write_timeout"
                causes.any { it.message == "AirPlay iAP tunnel pending-data limit exceeded" ||
                    it.message == "CSM received-frame queue limit exceeded" } -> "queue_overflow"
                causes.any { it is EOFException } -> "peer_eof"
                causes.any { it is AEADBadTagException || it.javaClass.simpleName.contains("BadTag") || it.javaClass.simpleName == "InvalidCipherTextException" } -> "decrypt_failed"
                causes.any { it.message?.contains(Regex("ECONNRESET|connection reset", RegexOption.IGNORE_CASE)) == true } -> "peer_reset"
                causes.any { it.message?.contains("ETIMEDOUT") == true ||
                    it.message?.contains("Connection timed out", ignoreCase = true) == true } -> "health_timeout"
                causes.any { it is SocketTimeoutException || it is java.util.concurrent.TimeoutException ||
                    it.javaClass.simpleName == "TimedOut" } -> "read_timeout"
                causes.any { it is IllegalArgumentException || it is IllegalStateException } -> "protocol_error"
                else -> "io_error"
            }
        }

        fun failureDetail(error: Throwable, privateValues: Iterable<String> = emptyList()): String {
            var text = generateSequence(error) { it.cause }.take(8).joinToString("; ") {
                "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
            }
            privateValues.filter { it.isNotEmpty() }.forEach { text = text.replace(it, "[private]") }
            return safeDetail(text)
        }

        fun safeDetail(value: String): String = value.take(4096)
            .replace(Regex("[\\r\\n\\p{Cntrl}]"), " ")
            .replace(Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"), "[address]")
            .replace(Regex("(?i)(?:ssid|passphrase|password|token|secret|key|deviceName|payload|certificate|challenge)\\s*[=:]\\s*[^;]*"), "[private]")
            .replace(Regex("[0-9a-fA-F]{32,}"), "[private]")
            .replace('"', '\'').take(160)

        fun line(end: CarPlayLinkEnd): String =
            "link ended link=${end.link} reached=${end.reached} by=${end.by} cause=${end.cause} " +
                "age=${end.ageMillis} idle=${end.idleMillis}" +
                end.facts.entries.joinToString("") { (key, value) -> " $key=$value" } +
                if (end.detail.isEmpty()) "" else " detail=\"${safeDetail(end.detail)}\""
    }
}
