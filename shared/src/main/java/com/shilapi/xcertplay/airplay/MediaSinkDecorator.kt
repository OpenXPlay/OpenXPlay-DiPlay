// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-14
package com.shilapi.xcertplay.airplay

/** Non-blocking input to the current session, callable from any thread. */
fun interface SessionControl {
    /** Returns false when no session accepts the input. Coordinates are normalized to [0, 1]. */
    fun sendTouch(contacts: List<AirPlayContact>): Boolean
    /** Queues a switch to a declared area; false when unavailable. */
    /** Opaque identity for the currently accepted main stream. Never cache across streams. */
    fun playbackToken(): Any? = null
    /** Reports a frame actually displayed by an external receiver; stale tokens are rejected. */
    fun reportVideoFrameRendered(token: Any): Boolean = false
    fun showViewArea(index: Int): Boolean = false
    /**
     * Sends one CarPlay media HID press, with the index taken from [CarPlayMediaButton];
     * false when no session accepts it.
     *
     * A head unit's steering-wheel or console keys reach the host as key events and have to
     * travel through this channel to the phone instead of the head unit's own player.
     */
    fun sendMediaButton(index: Int): Boolean = false
}

/** Called once per session before its media engine starts; returning the supplied sink is valid. */
fun interface MediaSinkDecorator {
    fun decorate(sink: MediaSink, control: SessionControl): MediaSink
}

/** Implemented by an app's Application to opt in to media sink decoration. */
interface MediaSinkDecoratorProvider {
    val mediaSinkDecorator: MediaSinkDecorator?
}
