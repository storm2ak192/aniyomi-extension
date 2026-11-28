package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.util.Base64
import android.webkit.WebSettings
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AnimePahe (English), rebuilt for animepahe.ws.
 *
 * The .ws site is a WordPress build, not the old animepahe.pw backend:
 * - latest/popular: the homepage grid (/page/N/), cards link to /play/<slug>-<hash>-<ep>/
 *   and the series page is the same slug under /anime/.
 * - search: the WordPress REST API (/wp-json/wp/v2/search, subtype anime_series).
 * - series: /anime/<slug>-<hash>/ carries the details and the full episode grid.
 * - playback: /play/<slug>-<hash>-<ep>/ embeds a megaplay.buzz player. The player page
 *   exposes data-id; /stream/getSourcesNew?id=<data-id> answers with an AES-CBC
 *   encrypted blob that decrypts to the master m3u8 URL (key and iv are constants in
 *   the site's own newclient.min.js).
 *
 * Note: play pages are served with an HTTP 404 status but carry the real body, so the
 * play fetch must not treat 404 as failure.
 */
class AnimePahe : Source() {

    private val userAgent: String by lazy { WebSettings.getDefaultUserAgent(context) }

    override val name = "AnimePahe"

    override val lang = "en"

    override val baseUrl = "https://animepahe.ws"

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // No user-configurable settings.
    }

    // =============================== Network ==============================

    private fun headers(referer: String? = null): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .apply { if (referer != null) add("Referer", referer) }
        .build()

    private suspend fun getDoc(url: String, referer: String? = null): Document {
        val response = client.get(url.toHttpUrl(), headers(referer))
        return Jsoup.parse(response.body.string(), response.request.url.toString())
    }

    private fun abs(path: String): String = if (path.startsWith("http")) path else baseUrl + "/" + path.trimStart('/')

    private fun pathOf(href: String): String = "/" + href.substringAfter("://").substringAfter("/")

    // =============================== Listings =============================

    private fun episodeCardToAnime(href: String, title: String, thumb: String?): SAnime? {
        val match = PLAY_URL_RE.find(href) ?: return null
        if (title.isBlank()) return null
        return SAnime.create().apply {
            url = "/anime/" + match.groupValues[1] + "/"
            this.title = title
            thumbnail_url = thumb?.takeIf { it.startsWith("http") }
        }
    }

    private suspend fun fetchHomeGrid(page: Int): AnimesPage {
        val url = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val doc = getDoc(url)
        val items = doc.select("a.anime-card[href*=/play/]").mapNotNull { a ->
            episodeCardToAnime(
                a.attr("href"),
                a.selectFirst(".card-title")?.text()?.trim().orEmpty(),
                a.selectFirst("img")?.attr("src"),
            )
        }.distinctBy { it.url }
        return AnimesPage(items, items.isNotEmpty())
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchHomeGrid(page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchHomeGrid(page)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val search = query.trim()
        if (search.isEmpty()) return getPopularAnime(page)
        val encoded = URLEncoder.encode(search, "UTF-8")
        val url = "$baseUrl/wp-json/wp/v2/search?search=$encoded&per_page=$SEARCH_PAGE_SIZE&page=$page&subtype=anime_series"
        val response = client.get(url.toHttpUrl(), headers("$baseUrl/"))
        val results = json.parseToJsonElement(response.body.string()).jsonArray
        val items = results.mapNotNull { element ->
            val obj = element.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val link = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (title.isEmpty() || !link.contains("/anime/")) return@mapNotNull null
            SAnime.create().apply {
                this.url = pathOf(link)
                this.title = title
            }
        }.distinctBy { it.url }
        return AnimesPage(items, results.size >= SEARCH_PAGE_SIZE)
    }

    // =============================== Details ==============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDoc(abs(anime.url))
        val pageText = doc.body().text()
        return anime.apply {
            title = doc.selectFirst("h1")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: title
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.startsWith("http") } ?: thumbnail_url
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: description
            status = when {
                pageText.contains("Status: Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                pageText.contains("Status: Finished", ignoreCase = true) -> SAnime.COMPLETED
                pageText.contains("Status: Not yet aired", ignoreCase = true) -> SAnime.UNKNOWN
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = getDoc(abs(anime.url))
        val episodes = mutableListOf<SEpisode>()
        doc.select(".series-episodes-grid article.ep-card").forEach { card ->
            val target = EP_ONCLICK_RE.find(card.attr("onclick"))?.groupValues?.get(1)
                ?: card.selectFirst("a[href*=/play/]")?.attr("href")
                ?: return@forEach
            val playMatch = PLAY_URL_RE.find(target) ?: return@forEach
            val number = playMatch.groupValues[2].toFloatOrNull()
                ?: card.selectFirst(".ep-num")?.text()?.trim()?.toFloatOrNull()
                ?: 0f
            episodes += SEpisode.create().apply {
                url = pathOf(target)
                name = "Episode " + playMatch.groupValues[2]
                episode_number = number
            }
        }
        return episodes.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val playUrl = abs(episode.url)
        // Play pages are served as HTTP 404 with the real body; read it regardless.
        val response = client.get(playUrl.toHttpUrl(), headers("$baseUrl/"))
        val body = response.body.string()
        val hosters = mutableListOf<Hoster>()
        MEGAPLAY_RE.find(body)?.let { match ->
            hosters += Hoster(match.value, "Kwik")
        }
        if (hosters.isEmpty()) {
            throw Exception("AnimePahe: no player found on the episode page (HTTP ${response.code})")
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val iframeUrl = hoster.hosterUrl
        val pageHeaders = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", "$baseUrl/")
            .build()

        val pageResponse = client.get(iframeUrl.toHttpUrl(), pageHeaders)
        val pageHtml = pageResponse.body.string()
        if (!pageResponse.isSuccessful) {
            throw Exception("AnimePahe: player page answered HTTP ${pageResponse.code}")
        }
        val dataId = DATA_ID_RE.find(pageHtml)?.groupValues?.get(1)
            ?: throw Exception("AnimePahe: player page layout changed")

        val ajaxHeaders = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", iframeUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val sourcesResponse = client.get("https://megaplay.buzz/stream/getSourcesNew?id=$dataId".toHttpUrl(), ajaxHeaders)
        val sourcesBody = sourcesResponse.body.string()
        if (!sourcesResponse.isSuccessful) {
            throw Exception("AnimePahe: sources request answered HTTP ${sourcesResponse.code}")
        }
        val enc = try {
            json.parseToJsonElement(sourcesBody).jsonObject["enc"]?.jsonPrimitive?.contentOrNull
        } catch (_: Throwable) {
            null
        } ?: throw Exception("AnimePahe: sources data unreadable")

        val masterUrl = decryptStreamUrl(enc)
            ?: throw Exception("AnimePahe: stream URL decryption failed")

        val videoHeaders = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", "https://megaplay.buzz/")
            .build()
        return listOf(Video(masterUrl, "Kwik (multi quality)", headers = videoHeaders))
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // ============================ megaplay AES ============================

    private fun decryptStreamUrl(enc: String): String? = try {
        val cipherText = Base64.decode(enc, Base64.URL_SAFE or Base64.NO_WRAP)
        val keyBytes = ByteArray(32)
        val rawKey = MEGA_KEY.toByteArray(Charsets.UTF_8)
        System.arraycopy(rawKey, 0, keyBytes, 0, minOf(rawKey.size, 32))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(MEGA_IV.toByteArray(Charsets.UTF_8)),
        )
        val plain = String(cipher.doFinal(cipherText), Charsets.UTF_8)
        json.parseToJsonElement(plain).jsonObject["file"]?.jsonPrimitive?.contentOrNull
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val SEARCH_PAGE_SIZE = 20

        // Constants from the site's own player bundle (newclient.min.js).
        private const val MEGA_KEY = "i?LMTAx0Q6,:}50U"
        private const val MEGA_IV = "W0;27ToaUpl_P%'c"

        private val PLAY_URL_RE = Regex("""/play/(.+)-(\d+(?:\.\d+)?)/?$""")
        private val EP_ONCLICK_RE = Regex("""window\.location='([^']+)'""")
        private val MEGAPLAY_RE = Regex("""https://megaplay\.buzz/videojs/stream/[^"'\s\\]+""")
        private val DATA_ID_RE = Regex("""data-id="(\d+)"""")
    }
                                       }
