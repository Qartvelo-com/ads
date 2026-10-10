package com.qartvelo.sdk.internal

import java.io.File
import java.net.URLDecoder

/**
 * The served layout of an HTML5 creative: the bundle's base URL (`creative_url` without
 * `index.html`) and the files that layout loads, relative to the base. Other layouts' files load
 * lazily from the same base when the ad is resized into them.
 */
internal class Html5Bundle(val baseUrl: String, val files: List<String>, val width: Int, val height: Int)

/** Path rules for bundle files. Pure functions, shared by the parser, the cache and the web view. */
internal object Html5Files {
    const val INDEX = "index.html"

    /** A plain relative path under the bundle base: no `..`, no absolute path, no scheme, no query. */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.length > 200 || path.startsWith("/")) return false
        if (path.any { it == '\\' || it == ':' || it == '?' || it == '#' || it == '%' || it.isWhitespace() || it.code < 0x20 }) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** The base of a bundle from its `creative_url`, or null when it is not an `index.html` URL. */
    fun baseOf(creativeUrl: String): String? =
        if (creativeUrl.endsWith("/$INDEX") && creativeUrl.indexOf('?') < 0) creativeUrl.removeSuffix(INDEX) else null

    /** The path of [url] inside the bundle at [baseUrl], or null when it is outside it. */
    fun relativePath(baseUrl: String, url: String): String? {
        if (!url.startsWith(baseUrl)) return null
        val rest = url.substring(baseUrl.length).substringBefore('#').substringBefore('?')
        val decoded = try {
            URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            return null
        }
        return decoded.takeIf { isSafePath(it) }
    }

    /** The cached copy of [url] in [dir], or null when it is outside the bundle or not cached. */
    fun resolve(dir: File, baseUrl: String, url: String): File? {
        val path = relativePath(baseUrl, url) ?: return null
        val file = File(dir, path)
        val inside = file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        return file.takeIf { inside && it.isFile }
    }

    private val MIME_TYPES = mapOf(
        "html" to "text/html",
        "css" to "text/css",
        "js" to "text/javascript",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
    )

    /** Content type of a bundle file, or null for a type bundles never contain. */
    fun mimeType(path: String): String? = MIME_TYPES[path.substringAfterLast('.', "").lowercase()]
}
