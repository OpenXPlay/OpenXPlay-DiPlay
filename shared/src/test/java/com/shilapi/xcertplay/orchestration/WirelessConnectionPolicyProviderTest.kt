// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-29
package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Test

class WirelessConnectionPolicyProviderTest {
    @Test fun handoffExperimentsDefaultToFalse() {
        val provider = object : WirelessConnectionPolicyProvider {
            override val wirelessNetworkRecoveryEnabled = false
        }
        assertFalse(provider.iapChannelOnlyWhenOffered)
        assertFalse(provider.releaseBluetoothOnHandoffRequest)
        assertFalse(provider.awaitIphoneReconnect)
    }
}
