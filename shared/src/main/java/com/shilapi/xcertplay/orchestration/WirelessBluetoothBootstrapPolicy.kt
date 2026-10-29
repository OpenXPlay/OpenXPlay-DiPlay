// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-29
package com.shilapi.xcertplay.orchestration

internal object WirelessBluetoothBootstrapPolicy {
    fun shouldRelease(
        closed: Boolean,
        wireless: Boolean,
        sameGeneration: Boolean,
        sameSession: Boolean,
        failureReported: Boolean,
        handoffRequested: Boolean,
        tunnelReady: Boolean,
        wirelessActive: Boolean,
        frameRendered: Boolean,
        bootstrapReleased: Boolean,
    ): Boolean = !closed && wireless && sameGeneration && sameSession && !failureReported &&
        handoffRequested && !tunnelReady && !wirelessActive && !frameRendered && !bootstrapReleased

    fun shouldReportTimeout(wirelessActive: Boolean, bootstrapReleased: Boolean, sameGeneration: Boolean): Boolean =
        !wirelessActive && !(bootstrapReleased && sameGeneration)

    fun toleratesError(bootstrapReleased: Boolean, sameGeneration: Boolean, sessionActive: Boolean, error: Throwable): Boolean =
        bootstrapReleased && sameGeneration && sessionActive && error !is Error
}
