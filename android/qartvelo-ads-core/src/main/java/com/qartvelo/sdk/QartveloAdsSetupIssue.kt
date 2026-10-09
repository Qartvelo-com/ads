package com.qartvelo.sdk

/**
 * A setup problem the app developer has to fix, such as an app key registered for another package or
 * a placement code missing from the dashboard. Reported once per process through
 * [QartveloAdsListener.onSetupIssue] (global listeners) and logged as an error.
 */
public data class QartveloAdsSetupIssue(
    /** One of [PACKAGE_MISMATCH], [PLATFORM_MISMATCH], [UNKNOWN_PLACEMENT], [FORMAT_MISMATCH]. */
    val code: String,
    /** What is wrong and how to fix it, in English. */
    val message: String,
    /** The placement concerned, or null for app-level issues. */
    val placementId: String?,
) {
    public companion object {
        public const val PACKAGE_MISMATCH: String = "package_mismatch"
        public const val PLATFORM_MISMATCH: String = "platform_mismatch"
        public const val UNKNOWN_PLACEMENT: String = "unknown_placement"
        public const val FORMAT_MISMATCH: String = "format_mismatch"
    }
}
