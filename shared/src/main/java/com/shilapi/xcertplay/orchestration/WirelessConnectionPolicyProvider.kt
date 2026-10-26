// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.orchestration

/** Host-owned opt-in, captured when a controller is created; changing it does not drop a live session. */
interface WirelessConnectionPolicyProvider {
    val wirelessNetworkRecoveryEnabled: Boolean
}
