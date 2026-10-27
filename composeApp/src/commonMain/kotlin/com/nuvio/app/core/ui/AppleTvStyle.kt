package com.nuvio.app.core.ui

import com.nuvio.app.isIos

/**
 * Android uses an Apple TV-inspired look: full-bleed hero with left-aligned content,
 * inline shelf titles with a chevron, landscape artwork and system typography.
 * iOS keeps the original Nuvio design.
 */
internal val useAppleTvDesign: Boolean
    get() = !isIos
