// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-15
package com.shilapi.xcertplay

import android.view.View
import androidx.activity.ComponentActivity

/** Implemented by the host Application to supply optional UI over CarPlay, below host menus. */
interface CarPlayHostOverlayProvider {
    /** Called on the main thread; returning null preserves the default host UI. */
    fun carPlayHostOverlay(activity: ComponentActivity): View?
}
