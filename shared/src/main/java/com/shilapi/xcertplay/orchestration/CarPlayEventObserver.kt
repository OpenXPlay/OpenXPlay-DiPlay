// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-16
package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Read-only CarPlay events for a host app. Calls arrive on iAP2 and RTSP threads, so they must
 * return quickly and never block; an exception is logged and does not affect CarPlay.
 */
interface CarPlayEventObserver {
    /** A CarPlay session started recording; may repeat for the same session. */
    fun onSessionActive() {}

    /** The active CarPlay session ended. */
    fun onSessionEnded() {}

    /** An iAP2 message outside session setup, such as route guidance, after DiPlay has handled it. */
    fun onIap2Frame(frame: Iap2Frame) {}

    /** An AirPlay command from the iPhone, such as `requestUI`, with its decoded parameters. */
    fun onCommand(type: String, params: Map<String, Any?>) {}
}

/** Implemented by an app's Application to observe CarPlay events; read for each event. */
interface CarPlayEventObserverProvider {
    val carPlayEventObserver: CarPlayEventObserver?
}
