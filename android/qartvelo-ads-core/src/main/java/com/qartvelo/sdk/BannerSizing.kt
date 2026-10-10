package com.qartvelo.sdk

/**
 * How a banner takes its size. [ANCHORED] (default): Google's anchored adaptive slot, full width
 * and 50 to 90 dp tall, for banners pinned to a screen edge. [INLINE]: for banners inside
 * scrolling content; the ad takes the biggest size that fits the width and
 * [QartveloAdsBannerView.inlineMaxHeightDp], keeping its proportions.
 */
public enum class BannerSizing { ANCHORED, INLINE }
