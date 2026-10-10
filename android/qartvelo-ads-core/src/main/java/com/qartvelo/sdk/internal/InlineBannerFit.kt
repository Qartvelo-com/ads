package com.qartvelo.sdk.internal

import kotlin.math.floor
import kotlin.math.min

/** An inline slot's size for an ad: scaled to fit the width and max height, keeping proportions. Same rule as the server. */
internal object InlineBannerFit {
    data class Size(val width: Int, val height: Int)

    fun size(width: Int, height: Int, slotWidth: Int, maxHeight: Int): Size {
        if (width <= 0 || height <= 0 || slotWidth <= 0 || maxHeight <= 0) return Size(0, 0)
        val scale = min(slotWidth.toDouble() / width, maxHeight.toDouble() / height)
        return Size(floor(width * scale).toInt(), floor(height * scale).toInt())
    }
}
