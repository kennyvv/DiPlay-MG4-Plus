package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is an Android stub on the plain JVM.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OtaUpdaterTest {
    private val digest = "a".repeat(64)

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, digest: String? = this.digest) =
        JSONObject()
            .put("tag_name", tag)
            .put("draft", draft)
            .put("prerelease", prerelease)
            .put("assets", JSONArray().put(
                JSONObject()
                    .put("name", "MG4CPlay-$tag.apk")
                    .put("browser_download_url", "https://github.com/kennyvv/DiPlay-MG4-Plus/releases/download/$tag/MG4CPlay-$tag.apk")
                    .apply { if (digest != null) put("digest", "sha256:$digest") },
            ))

    @Test
    fun theDebugSuffixDoesNotChangeTheVersion() {
        assertEquals(0, OtaUpdater.compare("0.2.9-mg4.91", "0.2.9-mg4.91-hud-test"))
        assertTrue(OtaUpdater.compare("0.2.9-mg4.92", "0.2.9-mg4.91-hud-test") > 0)
        assertTrue(OtaUpdater.compare("0.2.9-mg4.100", "0.2.9-mg4.99") > 0)
        assertTrue(OtaUpdater.compare("0.2.10-mg4.1", "0.2.9-mg4.99") > 0)
    }

    @Test
    fun offersTheNewerRelease() {
        val releases = JSONArray().put(release("v0.2.9-mg4.92")).put(release("v0.2.9-mg4.91"))
        val found = OtaUpdater.select(releases, "0.2.9-mg4.91-hud-test")!!
        assertEquals("0.2.9-mg4.92", found.version)
        assertEquals("MG4CPlay-v0.2.9-mg4.92.apk", found.fileName)
        assertEquals(digest, found.sha256)
    }

    @Test
    fun nothingWhenUpToDate() {
        val releases = JSONArray().put(release("v0.2.9-mg4.91")).put(release("v0.2.9-mg4.90"))
        assertNull(OtaUpdater.select(releases, "0.2.9-mg4.91-hud-test"))
    }

    @Test
    fun picksTheHighestVersionWhateverTheListOrder() {
        // A re-published older release can come first in GitHub's list.
        val releases = JSONArray().put(release("v0.2.9-mg4.92")).put(release("v0.2.9-mg4.94")).put(release("v0.2.9-mg4.93"))
        assertEquals("0.2.9-mg4.94", OtaUpdater.select(releases, "0.2.9-mg4.91")!!.version)
    }

    @Test
    fun skipsDraftsPrereleasesAndUnverifiableAssets() {
        val releases = JSONArray()
            .put(release("v0.2.9-mg4.95", draft = true))
            .put(release("v0.2.9-mg4.94", prerelease = true))
            .put(release("v0.2.9-mg4.93", digest = null))
            .put(release("v0.2.9-mg4.92"))
        assertEquals("0.2.9-mg4.92", OtaUpdater.select(releases, "0.2.9-mg4.91")!!.version)
    }
}
