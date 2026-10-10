package com.qartvelo.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Html5PolicyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://ads.qartvelo.com/api/v1/bundles/01abc/"

    private fun bundleDir(): File = tmp.newFolder("b").also { dir ->
        File(dir, "index.html").writeText("<html>")
        File(dir, "m").mkdirs()
        File(dir, "m/a.png").writeBytes(byteArrayOf(1))
    }

    @Test
    fun pathsMustStayInsideTheBundle() {
        assertTrue(Html5Files.isSafePath("index.html"))
        assertTrue(Html5Files.isSafePath("m/3f2a.jpg"))
        for (bad in listOf("", "../x", "m/../../x", "/abs", "https://x", "a\\b", "m//a", "./a", "a?b", "a#b", "%2e%2e/x", "a b")) {
            assertFalse(bad, Html5Files.isSafePath(bad))
        }
    }

    @Test
    fun theBaseIsTheCreativeUrlWithoutIndexHtml() {
        assertEquals(base, Html5Files.baseOf(base + "index.html"))
        assertNull(Html5Files.baseOf(base + "main.js"))
        assertNull(Html5Files.baseOf(base + "index.html?x=1"))
    }

    @Test
    fun resolveMapsOnlyCachedFilesOfTheBundle() {
        val dir = bundleDir()
        assertEquals(File(dir, "m/a.png").canonicalPath, Html5Files.resolve(dir, base, base + "m/a.png?v=1")?.canonicalPath)
        assertNull("not cached", Html5Files.resolve(dir, base, base + "m/b.png"))
        assertNull("other bundle", Html5Files.resolve(dir, base, "https://ads.qartvelo.com/api/v1/bundles/01xyz/index.html"))
        assertNull("escapes", Html5Files.resolve(dir, base, base + "m/%2e%2e/%2e%2e/secret"))
        assertNull("other host", Html5Files.resolve(dir, base, "https://evil.example/m/a.png"))
    }

    @Test
    fun requestsAreServedFetchedOrBlocked() {
        val dir = bundleDir()
        val served = Html5Policy.intercept(dir, base, base + "index.html", "GET")
        assertTrue(served is Html5Policy.Decision.Serve)
        assertEquals("text/html", (served as Html5Policy.Decision.Serve).mimeType)
        assertSame("another layout's file", Html5Policy.Decision.Network, Html5Policy.intercept(dir, base, base + "m/b.png", "GET"))
        assertSame(Html5Policy.Decision.Block, Html5Policy.intercept(dir, base, "https://evil.example/x.js", "GET"))
        assertSame(Html5Policy.Decision.Block, Html5Policy.intercept(dir, base, base + "index.html", "POST"))
        assertSame("no unknown types", Html5Policy.Decision.Block, Html5Policy.intercept(dir, base, base + "data.json", "GET"))
        assertTrue(Html5Policy.headers.getValue("Content-Security-Policy").startsWith("sandbox allow-scripts"))
        assertEquals("nosniff", Html5Policy.headers["X-Content-Type-Options"])
    }

    @Test
    fun theClickSchemeAndAnyLaterNavigationAreClicks() {
        assertTrue(Html5Policy.isClick("qartvelo://click", firstLoadDone = false))
        assertTrue(Html5Policy.isClick("https://advertiser.example/", firstLoadDone = true))
        assertFalse(Html5Policy.isClick("https://advertiser.example/", firstLoadDone = false))
    }
}
