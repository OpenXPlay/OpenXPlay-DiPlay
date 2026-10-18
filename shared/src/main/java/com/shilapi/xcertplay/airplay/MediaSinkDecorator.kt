// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-14
package com.shilapi.xcertplay.airplay

/** Non-blocking input to the current session, callable from any thread. */
fun interface SessionControl {
    /** Returns false when no session accepts the input. Coordinates are normalized to [0, 1]. */
    fun sendTouch(contacts: List<AirPlayContact>): Boolean
    /** Queues a switch to a declared area; false when unavailable. */
    fun showViewArea(index: Int): Boolean = false
}

/** Called once per session before its media engine starts; returning the supplied sink is valid. */
fun interface MediaSinkDecorator {
    fun decorate(sink: MediaSink, control: SessionControl): MediaSink
}

/** Implemented by an app's Application to opt in to media sink decoration. */
interface MediaSinkDecoratorProvider {
    val mediaSinkDecorator: MediaSinkDecorator?
}
