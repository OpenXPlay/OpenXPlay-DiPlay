// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-15
package com.shilapi.xcertplay

/**
 * Implemented by an app's Application to give CarPlay a canvas other than the host window, for
 * example when the stream is shown on another screen. Returning null keeps the window size.
 */
interface CarPlayCanvasProvider {
    /** Width and height in pixels; read whenever the host measures its window. */
    fun carPlayCanvas(): Pair<Int, Int>?
}
