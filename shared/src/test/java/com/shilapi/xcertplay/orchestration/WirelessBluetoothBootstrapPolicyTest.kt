// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-29
package com.shilapi.xcertplay.orchestration

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessBluetoothBootstrapPolicyTest {
    @Test fun stalledCurrentHandoffMayReleaseBootstrap() {
        assertTrue(shouldRelease())
    }

    @Test fun eachBlockingConditionPreventsRelease() {
        val blocked = mapOf(
            "closed" to shouldRelease(closed = true),
            "not wireless" to shouldRelease(wireless = false),
            "old generation" to shouldRelease(sameGeneration = false),
            "different or ended session" to shouldRelease(sameSession = false),
            "failure reported" to shouldRelease(failureReported = true),
            "no handoff request" to shouldRelease(handoffRequested = false),
            "tunnel ready" to shouldRelease(tunnelReady = true),
            "wireless active" to shouldRelease(wirelessActive = true),
            "rendered frame" to shouldRelease(frameRendered = true),
            "already released" to shouldRelease(bootstrapReleased = true),
        )
        for ((condition, decision) in blocked) assertFalse(condition, decision)
    }

    @Test fun timeoutIsIgnoredOnlyAfterActivationOrTheCurrentGenerationsRelease() {
        for (active in listOf(false, true)) for (released in listOf(false, true)) for (current in listOf(false, true)) {
            val report = WirelessBluetoothBootstrapPolicy.shouldReportTimeout(active, released, current)
            if (active || released && current) assertFalse(report) else assertTrue(report)
        }
    }

    @Test fun releaseToleratesExceptionsOnlyForTheCurrentActiveSessionAndNeverErrors() {
        for (released in listOf(false, true)) for (current in listOf(false, true)) for (active in listOf(false, true)) {
            val tolerate = WirelessBluetoothBootstrapPolicy.toleratesError(released, current, active, IOException("Socket closed"))
            if (released && current && active) assertTrue(tolerate) else assertFalse(tolerate)
            assertFalse(WirelessBluetoothBootstrapPolicy.toleratesError(released, current, active, AssertionError("fatal")))
        }
        assertTrue(WirelessBluetoothBootstrapPolicy.toleratesError(true, true, true, IllegalStateException("closed")))
    }

    private fun shouldRelease(
        closed: Boolean = false,
        wireless: Boolean = true,
        sameGeneration: Boolean = true,
        sameSession: Boolean = true,
        failureReported: Boolean = false,
        handoffRequested: Boolean = true,
        tunnelReady: Boolean = false,
        wirelessActive: Boolean = false,
        frameRendered: Boolean = false,
        bootstrapReleased: Boolean = false,
    ): Boolean = WirelessBluetoothBootstrapPolicy.shouldRelease(closed, wireless, sameGeneration, sameSession,
        failureReported, handoffRequested, tunnelReady, wirelessActive, frameRendered, bootstrapReleased)
}
