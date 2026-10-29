// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-29
package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AirPlaySetupFeaturesTest {
    private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))

    @Test fun defaultEnablesIapChannelWithoutAnOffer() {
        assertFalse(config.iapChannelOnlyWhenOffered)
        for (proposed in listOf(null, emptyList<String>(), listOf("viewAreas"), listOf("iAPChannel"))) {
            assertEquals(listOf("iAPChannel", "viewAreas"), setupEnabledFeatures(config, proposed))
        }
    }

    @Test fun enabledPolicyAcceptsTheOfferedChannel() {
        assertEquals(listOf("iAPChannel", "viewAreas"),
            setupEnabledFeatures(config.copy(iapChannelOnlyWhenOffered = true), listOf("iAPChannel")))
    }

    @Test fun enabledPolicyOmitsTheChannelWithoutAnOffer() {
        for (proposed in listOf(null, emptyList<String>(), listOf("viewAreas"), listOf("iapchannel", 1))) {
            assertEquals(listOf("viewAreas"),
                setupEnabledFeatures(config.copy(iapChannelOnlyWhenOffered = true), proposed))
        }
    }

    @Test fun otherFeaturesKeepTheirExistingNegotiationAndOrder() {
        val full = config.copy(hevc = true, cluster = config.main, videoInCar = true, mainBufferedAudio = true,
            main = config.main.copy(useIphoneCornerMask = true))
        for (proposed in listOf(null, listOf("cornerMasks", VideoInCar.FEATURE, MAIN_BUFFERED_FEATURE))) {
            assertEquals(setupEnabledFeatures(full, proposed).filterNot { it == "iAPChannel" },
                setupEnabledFeatures(full.copy(iapChannelOnlyWhenOffered = true), proposed))
        }
    }
}
