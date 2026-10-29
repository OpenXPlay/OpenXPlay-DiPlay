// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-29
package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayReconnectPolicyTest {
    @Test fun queuedRestartIsCancelledOnlyForANewerActiveSessionWithTheOptionEnabled() {
        for (enabled in listOf(false, true)) for (newer in listOf(false, true)) for (active in listOf(false, true)) {
            assertEquals("enabled=$enabled newer=$newer active=$active", enabled && newer && active,
                AirPlayReconnectPolicy.shouldCancelRestart(enabled, 3, if (newer) 4 else 3, active))
        }
    }

    @Test fun minimumDelayAppliesOnlyToEnabledWirelessSessionsThatNeverBecameActive() {
        for (enabled in listOf(false, true)) for (wireless in listOf(false, true)) for (wasActive in listOf(false, true)) {
            assertEquals("enabled=$enabled wireless=$wireless wasActive=$wasActive",
                if (enabled && wireless && !wasActive) 25_000L else 0L,
                AirPlayReconnectPolicy.minimumDelayMillis(enabled, wireless, wasActive))
        }
    }

    @Test fun minimumExtendsShortRetriesWithoutShorteningLongerBackoff() {
        assertEquals(25_000L, AirPlayReconnectPolicy.delayMillis(2_000, 25_000))
        assertEquals(25_000L, AirPlayReconnectPolicy.delayMillis(15_000, 25_000))
        assertEquals(25_000L, AirPlayReconnectPolicy.delayMillis(25_000, 25_000))
        assertEquals(30_000L, AirPlayReconnectPolicy.delayMillis(30_000, 25_000))
    }

    @Test fun otherCallersKeepTheirComputedDelay() {
        for (delay in listOf(0L, 2_000L, 15_000L, 30_000L)) {
            assertEquals(delay, AirPlayReconnectPolicy.delayMillis(delay, 0))
        }
    }
}
