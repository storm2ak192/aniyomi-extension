package eu.kanade.tachiyomi.animeextension.en.animepahe

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * AnimePahe (English).
 *
 * The site serves its lists through a JSON API and its episode pages as plain HTML:
 * - lists: /api?m=airing and /api?m=search
 * - episodes: /api?m=release
 * - playback: /play/<anime session>/<episode session>, whose resolution menu holds
 *   kwik links that have to be unpacked to a stream URL.
 */
class AnimePahe : Source() {

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    override val name = "AnimePahe"

    override val lang = "en"

    override val baseUrl = "https://animepahe.pw"

    override val supportsLatest = true

    // =============================== Network ==============================

    private fun headers(referer: String? = null): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .apply { if (referer != null) add("Referer", referer) }
        .build()

    private suspend fun getDoc(url: String, referer: String? = null): Document? = try {
        val response = client.get(url.toHttpUrl(), headers(referer))
        Jsoup.parse(response.body.string(), response.request.url.toString())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun getJson(url: String, referer: String = "$baseUrl/"): JsonObject? = try {
        val response = client.get(url.toHttpUrl(), headers(referer))
        Json.parseToJsonElement(response.body.string()).jsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun abs(path: String): String = if (path.startsWith("http")) path else baseUrl + "/" + path.trimStart('/')

    private fun pathOf(href: String): String = "/" + href.substringAfter("://").substringAfter("/")

    // =============================== Listings =============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val doc = getDoc("$baseUrl/anime?page=$page") ?: return AnimesPage(emptyList(), false)
        val items = doc.select("a[href*=anime/]").mapNotNull { a ->
            val path = pathOf(a.attr("href").trim())
            if (!PATH_RE.matches(path)) return@mapNotNull null
            val title = a.text().trim().ifEmpty { a.attr("title").trim() }
            if (title.isEmpty()) return@mapNotNull null
            SAnime.create().apply {
                url = path
                this.title = title
                thumbnail_url = a.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }
            }
        }.distinctBy { it.url }
        return AnimesPage(items, items.isNotEmpty())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val json = getJson("$baseUrl/api?m=airing&page=$page") ?: return AnimesPage(emptyList(), false)
        val data = json["data"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        val items = data.mapNotNull { element ->
            val item = element.jsonObject
            val session = item["anime_session"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = item["anime_title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val episode = item["episode"]?.jsonPrimitive?.contentOrNull.orEmpty()
            SAnime.create().apply {
                url = "/anime/$session"
                this.title = title
                thumbnail_url = item["snapshot"]?.jsonPrimitive?.contentOrNull
                description = "Latest episode: $episode"
            }
        }.distinctBy { it.url }
        val lastPage = json["last_page"]?.jsonPrimitive?.intOrNull ?: 1
        return AnimesPage(items, page < lastPage)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val search = query.trim()
        if (search.isEmpty()) return getPopularAnime(page)
        val encoded = URLEncoder.encode(search, "UTF-8")
        val json = getJson("$baseUrl/api?m=search&q=$encoded&page=$page") ?: return AnimesPage(emptyList(), false)
        val data = json["data"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        val items = data.mapNotNull { element ->
            val item = element.jsonObject
            val session = item["session"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = item["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val year = item["year"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val type = item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val score = item["score"]?.jsonPrimitive?.contentOrNull.orEmpty()
            SAnime.create().apply {
                url = "/anime/$session"
                this.title = title
                thumbnail_url = item["poster"]?.jsonPrimitive?.contentOrNull
                description = listOf(type, year, if (score.isNotEmpty()) "★ $score" else "")
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { null }
            }
        }.distinctBy { it.url }
        val lastPage = json["last_page"]?.jsonPrimitive?.intOrNull ?: 1
        return AnimesPage(items, page < lastPage)
    }

    // =============================== Details ==============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDoc(abs(anime.url)) ?: return anime
        val info = doc.select(".anime-info").text()
        return anime.apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: title
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.startsWith("http") } ?: thumbnail_url
            val summary = doc.selectFirst(".anime-summary")?.text()?.trim().orEmpty()
            description = summary.ifEmpty {
                doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
            }
            genre = doc.select(".anime-genre a, a[href*=genre]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
            status = when {
                info.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                info.contains("Finished", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val session = anime.url.trimEnd('/').substringAfterLast("/")
        if (session.isEmpty()) return emptyList()
        val detailsUrl = abs(anime.url)
        val episodes = mutableListOf<SEpisode>()
        var page = 1
        var lastPage = 1
        do {
            val json = getJson("$baseUrl/api?m=release&id=$session&sort=episode_asc&page=$page", detailsUrl) ?: break
            val data = json["data"]?.jsonArray ?: break
            data.forEach { element ->
                val item = element.jsonObject
                val episodeSession = item["session"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val number = item["episode"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                val edition = item["edition"]?.jsonPrimitive?.contentOrNull.orEmpty()
                episodes += SEpisode.create().apply {
                    url = "/play/$session/$episodeSession"
                    name = buildString {
                        append("Episode ")
                        append(item["episode"]?.jsonPrimitive?.contentOrNull ?: number.toString())
                        if (edition.isNotBlank()) append(" $edition")
                    }
                    episode_number = number
                    thumbnail_url = item["snapshot"]?.jsonPrimitive?.contentOrNull
                }
            }
            lastPage = json["last_page"]?.jsonPrimitive?.intOrNull ?: 1
            page++
        } while (page <= lastPage && page <= MAX_EPISODE_PAGES)

        return episodes.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val playUrl = abs(episode.url)
        val doc = getDoc(playUrl, "$baseUrl/") ?: return emptyList()
        return doc.select("button[data-src], button[data-url]").mapNotNull { button ->
            val source = button.attr("data-src").ifBlank { button.attr("data-url") }
            if (source.isBlank()) return@mapNotNull null
            val resolution = button.attr("data-resolution").ifBlank { "auto" }
            val fansub = button.attr("data-fansub")
            val audio = button.attr("data-audio")
            val label = listOf(resolution + "p", audio, fansub)
                .filter { it.isNotBlank() && it != "auto" }
                .joinToString(" ")
                .ifBlank { "kwik" }
            Hoster("$source|$playUrl|$label", label)
        }.distinctBy { it.hosterUrl }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        val source = parts.firstOrNull().orEmpty()
        val referer = parts.getOrNull(1) ?: "$baseUrl/"
        val label = parts.getOrNull(2) ?: hoster.hosterName
        if (source.isEmpty()) return emptyList()
        return KwikExtractor.videos(client, source, referer, label, userAgent)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()
}

private val PATH_RE = Regex("""/anime/[0-9a-fA-F-]{8,}""")
private const val MAX_EPISODE_PAGES = 40
