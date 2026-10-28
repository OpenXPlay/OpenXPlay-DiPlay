// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-27
package com.shilapi.xcertplay.airplay

/** Captured by one media engine; changing host preferences never alters a running connection. */
data class ScreenSessionPolicy(
    val isolateSessions: Boolean = true,
    val resetOnReconnect: Boolean = true,
    val serializeDelivery: Boolean = true,
)

/** Optional host policy for screen lifecycle experiments, shared by wired and wireless inputs. */
interface ScreenSessionPolicyProvider {
    val screenSessionPolicy: ScreenSessionPolicy
}
