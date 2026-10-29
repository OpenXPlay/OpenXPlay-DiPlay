// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-27
// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.orchestration

/** Host-owned opt-in, captured when a controller is created; changing it does not drop a live session. */
interface WirelessConnectionPolicyProvider {
    val wirelessNetworkRecoveryEnabled: Boolean
    val cancelRecoveredRetries: Boolean get() = true
    val startupDiagnosticsEnabled: Boolean get() = false
    val networkObservationBackoffEnabled: Boolean get() = false
    val iapChannelOnlyWhenOffered: Boolean get() = false
    val releaseBluetoothOnHandoffRequest: Boolean get() = false
    val awaitIphoneReconnect: Boolean get() = false
}
