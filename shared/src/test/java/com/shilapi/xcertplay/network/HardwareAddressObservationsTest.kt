// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-27
package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class HardwareAddressObservationsTest {
    @Test fun unsupportedInterfaceIsReadOnceButOtherInterfacesAndNewAttemptsAreIndependent() {
        var reads = 0
        var notices = 0
        val observations = HardwareAddressObservations(true, { reads++; null }, { notices++ })
        repeat(5) { assertNull(observations.read("ap0")) }
        assertEquals(1, reads); assertEquals(1, notices)
        observations.read("ap1")
        assertEquals(2, reads)
        HardwareAddressObservations(true, { reads++; null }).read("ap0")
        assertEquals(3, reads)
    }
    @Test fun readableAddressesRemainObservableAndDisablingBackoffRestoresRepeatedReads() {
        var address: String? = "a0:b1:c2:d3:e4:f5"
        var reads = 0
        val observations = HardwareAddressObservations(true, { reads++; address })
        assertEquals(address, observations.read("ap0"))
        address = "a0:b1:c2:d3:e4:f6"
        assertEquals(address, observations.read("ap0"))
        address = null
        observations.read("ap0"); observations.read("ap0")
        assertEquals(3, reads)
        val disabled = HardwareAddressObservations(false, { reads++; null })
        repeat(3) { disabled.read("ap0") }
        assertEquals(6, reads)
    }
}
