// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessSecurity

/** Compare only observed values; redacted/unknown radio metadata is not evidence of a change. */
internal data class HotspotRadioSnapshot(
    val ssid: String?,
    val security: Iap2WirelessSecurity?,
    val channel: Int,
    val bssid: String?,
) {
    fun changed(current: HotspotRadioSnapshot): Boolean =
        ssid != null && current.ssid != null && ssid != current.ssid ||
            security != null && current.security != null && security != current.security ||
            channel > 0 && current.channel > 0 && channel != current.channel ||
            observedBssid(bssid)?.let { before -> observedBssid(current.bssid)?.let { it != before } } == true

    private fun observedBssid(value: String?): String? = value?.lowercase(java.util.Locale.ROOT)?.takeIf {
        it.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) &&
            it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00"
    }
}
