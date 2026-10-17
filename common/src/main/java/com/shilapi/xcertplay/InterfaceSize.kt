// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: GPL-3.0-only
// Created by riko chen on 2026-10-14

package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.view.ContextThemeWrapper

internal object InterfaceSize {
    fun attach(activity: ContextThemeWrapper, base: Context): Configuration? = null

    fun enforce(resources: Resources, override: Configuration) = Unit
}
