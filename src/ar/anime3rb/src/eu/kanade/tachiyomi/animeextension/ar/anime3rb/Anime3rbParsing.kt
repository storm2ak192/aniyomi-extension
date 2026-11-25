package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parsing helpers (no Android classes) so they can be unit tested on the JVM.
 */
internal object Anime3rbParsing {

    data class SourceItem(val url: String, val label: String, val resolution: Int?)

    private val json = Json { ignoreUnknownKeys = true }

    private val VIDEO_URL = Regex("\"video_url\"\\s*:\\s*\"([^\"]+)\"")
    private val PLAYER_URL = Regex("""https?://[^\s"'<>]*/player/[^\s"'<>]+""")
    val INLINE_SOURCES = Regex("""var\s+video_sources\s*=\s*(\[\s*\{.*?\}\s*\]);""", RegexOption.DOT_MATCHES_ALL)

    /** The episode page embeds its data as HTML-escaped JSON with escaped slashes. */
    fun unescapeEmbedded(html: String): String = html
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("\\/", "/")

    /** Address of the player page, or null when the page does not carry one. */
    fun playerUrl(html: String): String? {
        val text = unescapeEmbedded(html)
        return VIDEO_URL.find(text)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
            ?: PLAYER_URL.find(text)?.value
    }

    /** Qualities from the player's sources response. Premium and empty entries are dropped. */
    fun parseSources(raw: String): List<SourceItem> {
        val root = json.parseToJsonElement(raw)
        val array = root as? JsonArray
            ?: (root as? JsonObject)?.values?.firstOrNull { it is JsonArray } as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (item.text("premium") == "true") return@mapNotNull null
            val src = item.text("src").replace("\\/", "/")
            if (src.isEmpty()) return@mapNotNull null
            val res = item.text("res")
            SourceItem(
                url = src,
                label = item.text("label").ifEmpty { "${res}p" },
                resolution = res.toIntOrNull(),
            )
        }
    }

    /**
     * Preferred quality first (exact, then closest lower, then higher). "best" sorts high to low.
     */
    fun <T> sortByQuality(items: List<T>, preferred: String, resolution: (T) -> Int?): List<T> {
        val wanted = preferred.toIntOrNull()
        return if (wanted == null) {
            items.sortedByDescending { resolution(it) ?: 0 }
        } else {
            items.sortedWith(
                compareBy<T> { (resolution(it) ?: 0) > wanted }
                    .thenByDescending { resolution(it) ?: 0 },
            )
        }
    }

    data class LatestItem(
        val slug: String,
        val number: String,
        val label: String,
        val title: String,
        val image: String?,
    )

    private val cardRegex = Regex(
        """<a\s[^>]*href="([^"]*/episode/([^/"?#]+)/(\d+(?:\.\d+)?))"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val imgRegex = Regex("""<img[^>]*\ssrc="([^"]*)"""")
    private val titleRegex = Regex(
        """<h3[^>]*title-name[^>]*>(.*?)</h3>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val numberRegex = Regex(
        """<p[^>]*class="[^"]*number[^"]*"[^>]*>(.*?)</p>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * Episode cards of the site's "latest episodes" feed (the home page grid and the fragment the
     * "load more" button fetches). One item per episode, in site order. An empty list means the
     * feed has ended.
     */
    fun parseLatestFeed(html: String): List<LatestItem> {
        val seen = LinkedHashSet<String>()
        return cardRegex.findAll(html).mapNotNull { m ->
            val body = m.groupValues[4]
            val title = titleRegex.find(body)?.groupValues?.get(1)?.let(::cleanText).orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val number = m.groupValues[3]
            if (!seen.add(m.groupValues[2] + "/" + number)) return@mapNotNull null
            val label = numberRegex.find(body)?.groupValues?.get(1)?.let(::cleanText).orEmpty()
            LatestItem(
                slug = m.groupValues[2],
                number = number,
                label = label.ifEmpty { number },
                title = title,
                image = imgRegex.find(body)?.groupValues?.get(1)?.let(::cleanText)?.ifEmpty { null },
            )
        }.toList()
    }

    private fun cleanText(raw: String): String = raw
        .replace(Regex("<[^>]*>"), "")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                                 }
