# QartveloAds core consumer rules (applied to apps that minify).

# The optional AdMob adapter is looked up by name; it may be absent.
-dontwarn com.qartvelo.admob.**

# Fallback seam implemented by qartvelo-ads-admob (and by custom adapters registered at runtime).
-keep interface com.qartvelo.sdk.fallback.** { *; }
