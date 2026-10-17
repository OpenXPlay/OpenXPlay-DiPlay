// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-15
package com.shilapi.xcertplay

/**
 * Implemented by an app's Application that chooses the iPhone for wireless CarPlay itself.
 * Returning null keeps the phone saved in DiPlay, or DiPlay's own choice among bonded devices.
 */
interface CarPlayPhoneProvider {
    /** Bluetooth address of a bonded iPhone; read each time a CarPlay connection starts. */
    fun carPlayPhoneAddress(): String?
}
