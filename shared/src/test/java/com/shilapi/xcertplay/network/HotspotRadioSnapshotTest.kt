// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test

class HotspotRadioSnapshotTest {
    private val saved = HotspotRadioSnapshot("CarPlay", Iap2WirelessSecurity.WPA_WPA2, 36, "a0:b1:c2:d3:e4:f5")

    @Test fun observedNetworkChangesInvalidateThePublishedEndpoint() {
        for (next in listOf(saved.copy(ssid = "Other"), saved.copy(channel = 149),
            saved.copy(security = Iap2WirelessSecurity.WPA3_ONLY), saved.copy(bssid = "a0:b1:c2:d3:e4:f6"))) {
            assertTrue(saved.changed(next))
        }
        assertFalse(saved.changed(saved.copy(bssid = "A0:B1:C2:D3:E4:F5")))
    }

    @Test fun missingAndRedactedValuesCannotProveANetworkChange() {
        val unknown = HotspotRadioSnapshot(null, null, 0, null)
        assertFalse(saved.changed(unknown))
        assertFalse(unknown.changed(saved))
        for (address in listOf("02:00:00:00:00:00", "00:00:00:00:00:00", "unavailable")) {
            assertFalse(saved.changed(saved.copy(bssid = address)))
        }
    }
}
