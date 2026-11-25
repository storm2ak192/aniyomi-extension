package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Anime3rbParsingTest {

    // Shape of the episode page data: HTML-escaped JSON, slashes escaped, & written as &amp;.
    private val episodeHtml =
        """<div wire:snapshot="{&quot;data&quot;:{&quot;video_number&quot;:&quot;24&quot;,&quot;views&quot;:27598,""" +
            """&quot;video_url&quot;:&quot;https:\/\/video.vid3rb.com\/player\/9d6ed573-4927-43f0-8571-9a1a57db4244""" +
            """?token=abc123&amp;expires=1795631036&quot;,&quot;video_source&quot;:&quot;x&quot;}}"></div>""" +
            """<script type="application/ld+json">{"embedUrl":"https://anime3rb.com/embed/9d6ed573?expires=1&signature=z"}</script>"""

    private val sourcesJson =
        """[{"src":"","type":"video\/mp4","label":"1080p Premium","res":"1080","premium":true},""" +
            """{"src":"https:\/\/video.vid3rb.com\/video\/aaa?speed=381&token=t1&expires=9","type":"video\/mp4","label":"1080p","res":"1080","premium":false},""" +
            """{"src":"https:\/\/video.vid3rb.com\/video\/bbb?speed=175&token=t2&expires=9","type":"video\/mp4","label":"720p","res":"720","premium":false},""" +
            """{"src":"https:\/\/video.vid3rb.com\/video\/ccc?speed=75&token=t3&expires=9","type":"video\/mp4","label":"480p","res":"480","premium":false}]"""

    @Test
    fun playerUrlIsReadFromEscapedEmbeddedData() {
        assertEquals(
            "https://video.vid3rb.com/player/9d6ed573-4927-43f0-8571-9a1a57db4244?token=abc123&expires=1795631036",
            Anime3rbParsing.playerUrl(episodeHtml),
        )
    }

    @Test
    fun playerUrlIsNullWhenPageHasNoPlayer() {
        assertNull(Anime3rbParsing.playerUrl("<html>no player here</html>"))
    }

    @Test
    fun premiumAndEmptyEntriesAreSkipped() {
        val items = Anime3rbParsing.parseSources(sourcesJson)
        assertEquals(listOf(1080, 720, 480), items.map { it.resolution })
        assertEquals(listOf("1080p", "720p", "480p"), items.map { it.label })
        assertTrue(items.all { it.url.startsWith("https://video.vid3rb.com/video/") && '\\' !in it.url })
    }

    @Test
    fun objectWrappedListIsAccepted() {
        val items = Anime3rbParsing.parseSources("""{"sources":$sourcesJson}""")
        assertEquals(3, items.size)
    }

    @Test
    fun onlyPremiumGivesEmptyList() {
        val raw = """[{"src":"","label":"1080p Premium","res":"1080","premium":true}]"""
        assertTrue(Anime3rbParsing.parseSources(raw).isEmpty())
    }

    @Test
    fun bestSortsHighToLow() {
        val items = Anime3rbParsing.parseSources(sourcesJson)
        val sorted = Anime3rbParsing.sortByQuality(items, "best") { it.resolution }
        assertEquals(listOf(1080, 720, 480), sorted.map { it.resolution })
    }

    @Test
    fun preferredQualityComesFirstThenLowerThenHigher() {
        val items = Anime3rbParsing.parseSources(sourcesJson)
        assertEquals(
            listOf(720, 480, 1080),
            Anime3rbParsing.sortByQuality(items, "720") { it.resolution }.map { it.resolution },
        )
        assertEquals(
            480,
            Anime3rbParsing.sortByQuality(items, "480") { it.resolution }.first().resolution,
        )
    }
}
