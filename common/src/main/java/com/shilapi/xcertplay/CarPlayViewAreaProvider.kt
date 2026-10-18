// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-18
package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayViewArea

/** Implemented by an Application to supply the areas declared for each CarPlay session. */
interface CarPlayViewAreaProvider {
    /** A non-empty list starts at area zero; null or empty keeps the host's own areas. */
    fun carPlayViewAreas(canvasWidth: Int, canvasHeight: Int): List<AirPlayViewArea>?
}
