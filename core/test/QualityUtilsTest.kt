package extensions.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityUtilsTest {
    @Test fun heightFromPLabel() = assertEquals(1080, QualityUtils.heightOf("Server · 1080p"))

    @Test fun heightFromDimensions() = assertEquals(720, QualityUtils.heightOf("1280x720"))

    @Test fun heightFromNames() {
        assertEquals(1080, QualityUtils.heightOf("FHD"))
        assertEquals(720, QualityUtils.heightOf("HD"))
        assertEquals(480, QualityUtils.heightOf("SD"))
        assertEquals(2160, QualityUtils.heightOf("4K"))
    }

    @Test fun heightUnknown() {
        assertNull(QualityUtils.heightOf(null))
        assertNull(QualityUtils.heightOf("مباشر"))
        assertNull(QualityUtils.heightOf("HDR10"))
    }

    @Test fun bestSortsHighToLow() =
        assertEquals(listOf(1080, 720, 360, 0), QualityUtils.sortByQuality(listOf(360, 1080, 0, 720), "best") { it })

    @Test fun preferredFirstThenLowerThenHigher() =
        assertEquals(listOf(720, 480, 360, 1080), QualityUtils.sortByQuality(listOf(1080, 360, 720, 480), "720") { it })

    @Test fun preferredMissingFallsToClosestLower() =
        assertEquals(listOf(480, 360, 1080), QualityUtils.sortByQuality(listOf(1080, 360, 480), "720") { it })

    @Test fun domainNormalised() {
        assertEquals("https://a.b", QualityUtils.normalizeDomain("  https://a.b/ ", "https://x.y"))
        assertEquals("https://x.y", QualityUtils.normalizeDomain("a.b", "https://x.y"))
        assertEquals("https://x.y", QualityUtils.normalizeDomain("https://", "https://x.y"))
        assertEquals("https://x.y", QualityUtils.normalizeDomain(null, "https://x.y"))
    }

    @Test fun rebaseKeepsPathAndQuery() {
        assertEquals("https://new.site/anime/x/?a=1#f", QualityUtils.rebase("https://old.site/anime/x/?a=1#f", "https://new.site/"))
        assertEquals("https://new.site", QualityUtils.rebase("https://old.site", "https://new.site"))
        assertEquals("relative/p", QualityUtils.rebase("relative/p", "https://new.site"))
    }

    @Test fun hosterNamesWithUnknownKeepTheirPlace() {
        val names = listOf("Dood SD", "Vidrco", "Dood FHD", "Dood HD")
        val unknownBest = Int.MAX_VALUE
        assertEquals(
            listOf("Vidrco", "Dood FHD", "Dood HD", "Dood SD"),
            QualityUtils.sortByQuality(names, "best") { QualityUtils.heightOf(it) ?: unknownBest },
        )
        assertEquals(
            listOf("Vidrco", "Dood HD", "Dood SD", "Dood FHD"),
            QualityUtils.sortByQuality(names, "720") { QualityUtils.heightOf(it) ?: 720 },
        )
    }
}
