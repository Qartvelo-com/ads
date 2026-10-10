package com.qartvelo.sdk.internal

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Persists the last remote configuration (never the session token) so offline or slow starts still
 * know placement timeouts, kill switches and fallback ad units. Disk access only from io threads.
 */
internal class ConfigStore(context: Context, private val appKey: String) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): RemoteConfig? = try {
        val raw = prefs.getString(KEY_CONFIG, null)
        if (raw == null || prefs.getString(KEY_APP, null) != appKey) {
            null
        } else {
            RemoteConfig.parse(JSONObject(raw))
        }
    } catch (t: Throwable) {
        OurLog.e("Ignoring unreadable cached config", t)
        null
    }

    fun save(json: String) {
        prefs.edit().putString(KEY_APP, appKey).putString(KEY_CONFIG, json).apply()
    }

    companion object {
        const val PREFS = "com.qartvelo.sdk.config"
        private const val KEY_CONFIG = "remote_config_v1"
        private const val KEY_APP = "app_key"
    }
}

/**
 * Creative files cached in `cacheDir/qartvelo_creatives`, named by URL hash so a creative served again
 * reuses its file. A file lives only as long as the longest-lived ad referencing it; stale files are
 * purged on start and before each download.
 */
internal class CreativeCache(context: Context, private val api: ApiClient) {
    // Lazy: resolving cacheDir touches the disk, which must not happen on the main thread.
    private val dir by lazy { File(context.cacheDir, "qartvelo_creatives") }
    private val expiries = HashMap<String, Long>()
    private val locks = HashMap<String, Any>()

    /** Downloads (or reuses) the creative for [ad] and validates it. Blocking; io threads only. */
    fun fetch(ad: ServedAd, tracker: CallTracker? = null): File {
        purgeExpired()
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create creative cache")
        val name = hash(ad.creativeUrl) + if (ad.creativeType == CreativeType.HTML5) BUNDLE_SUFFIX else extension(ad)
        val file = File(dir, name)
        synchronized(lockFor(name)) {
            // Register the expiry before downloading so a concurrent purge never removes this file.
            val cached = synchronized(expiries) {
                val had = expiries.containsKey(name) && file.exists()
                expiries[name] = maxOf(expiries[name] ?: 0L, ad.expiresAtElapsed)
                had
            }
            try {
                if (ad.creativeType == CreativeType.HTML5) {
                    fetchBundle(ad.bundle ?: throw IOException("html5 ad without bundle"), file, tracker)
                } else if (!cached) {
                    val video = ad.creativeType == CreativeType.VIDEO
                    api.download(
                        url = ad.creativeUrl,
                        dest = file,
                        maxBytes = if (video) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES,
                        timeoutMs = if (video) VIDEO_TIMEOUT_MS else IMAGE_TIMEOUT_MS,
                        tracker = tracker,
                    )
                }
                validate(ad.creativeType, file)
            } catch (t: Throwable) {
                file.deleteRecursively()
                synchronized(expiries) { expiries.remove(name) }
                throw t
            }
        }
        return file
    }

    /**
     * Downloads the served layout's files of an HTML5 bundle into [dir] (files already there are
     * kept: bundles are immutable), at most [MAX_BUNDLE_BYTES] in total.
     */
    private fun fetchBundle(bundle: Html5Bundle, dir: File, tracker: CallTracker?) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create bundle directory")
        var total = 0L
        for (path in bundle.files) {
            if (!Html5Files.isSafePath(path) || Html5Files.mimeType(path) == null) throw IOException("bundle file not allowed: $path")
            val file = File(dir, path)
            if (!file.isFile) {
                file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("cannot create bundle directory") }
                api.download(
                    url = bundle.baseUrl + path,
                    dest = file,
                    maxBytes = MAX_BUNDLE_BYTES - total,
                    timeoutMs = IMAGE_TIMEOUT_MS,
                    tracker = tracker,
                )
            }
            total += file.length()
            if (total > MAX_BUNDLE_BYTES) throw IOException("bundle too large")
        }
        if (!File(dir, Html5Files.INDEX).isFile) throw IOException("bundle without index.html")
    }

    private fun lockFor(name: String): Any = synchronized(locks) { locks.getOrPut(name) { Any() } }

    /** Deletes files whose ads have all expired, plus leftovers from earlier processes. */
    fun purgeExpired() {
        val now = Clock.elapsed()
        val files = dir.listFiles() ?: return
        synchronized(expiries) {
            for (f in files) {
                val expiry = expiries[f.name]
                val stale = when {
                    expiry != null -> now >= expiry
                    // In-progress downloads write to `.part` files; only abandoned leftovers are removed.
                    f.name.endsWith(".part") -> System.currentTimeMillis() - f.lastModified() > PART_FILE_MAX_AGE_MS
                    // Unknown files come from an earlier process whose ads are gone.
                    else -> true
                }
                if (stale) {
                    f.deleteRecursively()
                    expiries.remove(f.name)
                }
            }
        }
    }

    private fun validate(type: CreativeType, file: File) {
        when (type) {
            CreativeType.HTML5 -> Unit // Checked file by file in fetchBundle; the web view validates the rest.
            CreativeType.IMAGE -> {
                if (!hasImageSignature(file)) throw IOException("creative is not a PNG/JPEG/GIF/WebP image")
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) throw IOException("creative is not a decodable image")
            }
            CreativeType.VIDEO -> {
                // MP4/ISO-BMFF files carry an `ftyp` box at offset 4; anything else (an HTML error page,
                // a truncated upload) is rejected before we claim the ad is loaded.
                val header = ByteArray(8)
                val read = file.inputStream().use { it.read(header) }
                if (read < 8 || String(header, 4, 4, Charsets.US_ASCII) != "ftyp") {
                    throw IOException("creative is not an MP4 video")
                }
            }
        }
    }

    /** Magic bytes of the formats the backend accepts (png, jpg, gif, webp). */
    private fun hasImageSignature(file: File): Boolean {
        val h = ByteArray(12)
        val read = file.inputStream().use { it.read(h) }
        if (read < 12) return false
        fun at(i: Int) = h[i].toInt() and 0xFF
        val png = at(0) == 0x89 && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() && h[3] == 'G'.code.toByte()
        val jpeg = at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF
        val gif = String(h, 0, 4, Charsets.US_ASCII) == "GIF8"
        val webp = String(h, 0, 4, Charsets.US_ASCII) == "RIFF" && String(h, 8, 4, Charsets.US_ASCII) == "WEBP"
        return png || jpeg || gif || webp
    }

    private fun extension(ad: ServedAd): String {
        val path = ad.creativeUrl.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
        const val MAX_BUNDLE_BYTES = 2L * 1024 * 1024
        private const val BUNDLE_SUFFIX = ".bundle"
        const val MAX_VIDEO_BYTES = 40L * 1024 * 1024
        const val IMAGE_TIMEOUT_MS = 10_000L
        const val VIDEO_TIMEOUT_MS = 45_000L
        private const val PART_FILE_MAX_AGE_MS = 10 * 60_000L
    }
}
