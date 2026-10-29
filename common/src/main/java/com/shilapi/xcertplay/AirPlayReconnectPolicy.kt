// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-29
package com.shilapi.xcertplay

internal object AirPlayReconnectPolicy {
    const val IPHONE_RECONNECT_GRACE_MILLIS = 25_000L

    fun shouldCancelRestart(enabled: Boolean, activationsAtSchedule: Long, activations: Long, sessionActive: Boolean): Boolean =
        enabled && activations != activationsAtSchedule && sessionActive

    fun minimumDelayMillis(enabled: Boolean, wireless: Boolean, wasActive: Boolean): Long =
        if (enabled && wireless && !wasActive) IPHONE_RECONNECT_GRACE_MILLIS else 0L

    fun delayMillis(computed: Long, minimum: Long): Long = maxOf(computed, minimum)
}
