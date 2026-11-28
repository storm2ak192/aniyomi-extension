package eu.kanade.tachiyomi.animeextension.ar.topcinema

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.get
import extensions.utils.post
import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * TopCinema (Arabic) - movies and series.
 *
 * The site is a WordPress theme ("movies2023"). Lists, details and episode pages are
 * plain HTML. The watch page (page URL + "watch/") shows one default player iframe and
 * a server list whose items resolve through a POST to the theme's Ajaxat Server.php,
 * each returning an embed iframe (vidtube, okstream, doodstream, streamtape, ...).
 * Stream resolution from those embeds lives in TopCinemaExtractors.
 */
class TopCinema : Source() {

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"

    override val name = "TopCinema"

    override val lang = "ar"

    override val baseUrl = "https://topcinemaa.tv"

    override val supportsLatest = true

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // No user-configurable settings.
    }

    // =============================== Network ==============================

    private fun headers(referer: String? = null): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept-Language", "ar-EG,ar;q=0.9,en;q=0.7")
        .apply { referer?.let { add("Referer", it) } }
        .build()

    private suspend fun getDoc(url: String, referer: String? = "$baseUrl/"): Document? = try {
        val response = client.get(url.toHttpUrl(), headers(referer))
        Jsoup.parse(response.body.string(), url)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun abs(path: String): String =
        if (path.startsWith("http")) path else "$baseUrl/" + path.trimStart('/')

    private fun pathOf(href: String): String = "/" + href.substringAfter("://").substringAfter("/")

    // =============================== Lists ================================

    private fun animeFromA(a: Element): SAnime? {
        val href = a.absUrl("href").ifBlank { a.attr("href") }
        if (href.isBlank()) return null
        val title = a.attr("title").ifBlank {
            a.selectFirst("h3.title")?.text().orEmpty()
        }.trim()
        if (title.isEmpty()) return null
        return SAnime.create().apply {
            url = pathOf(href)
            this.title = title
            thumbnail_url = a.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.takeIf { src -> src.startsWith("http") }
        }
    }

    private fun Document.toAnimesPage(): AnimesPage {
        val items = select(".Posts--List .Small--Box a[href], .Small--Box a[href]")
            .mapNotNull { animeFromA(it) }
            .distinctBy { it.url }
        val hasNext = selectFirst("a.next.page-numbers, a:containsOwn(»)") != null
        return AnimesPage(items, hasNext)
    }

    private fun paged(path: String, page: Int): String =
        if (page <= 1) "$baseUrl/$path" else "$baseUrl/${path.trimEnd('/')}/page/$page/"

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val doc = getDoc(paged("top-rating-imdb/", page)) ?: return AnimesPage(emptyList(), false)
        return doc.toAnimesPage()
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val doc = getDoc(paged("recent/", page)) ?: return AnimesPage(emptyList(), false)
        return doc.toAnimesPage()
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val offset = if (page <= 1) "" else "&offset=$page"
            val doc = getDoc("$baseUrl/search/?query=$query&type=all$offset")
                ?: return AnimesPage(emptyList(), false)
            return doc.toAnimesPage()
        }
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()
            ?.takeIf { it.state > 0 }
            ?.let { CATEGORIES[it.state].path }
        if (category == null) return getLatestUpdates(page)
        val doc = getDoc(paged(category, page)) ?: return AnimesPage(emptyList(), false)
        return doc.toAnimesPage()
    }

    // =============================== Details ==============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDoc(abs(anime.url)) ?: return anime
        return anime.apply {
            val pageTitle = doc.selectFirst("h1.post-title a")?.text()?.trim()
                ?: doc.selectFirst("h1.post-title")?.text()?.trim()
            if (!pageTitle.isNullOrEmpty()) title = pageTitle
            thumbnail_url = doc.selectFirst(".MainSingle .left .image img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.startsWith("http") } ?: thumbnail_url
            description = doc.selectFirst(".story p")?.text()?.trim()
                ?.ifEmpty { null } ?: description
            genre = doc.select(".RightTaxContent li:contains(نوع) a")
                .joinToString(", ") { it.text() }.ifBlank { null } ?: genre
            status = if (title.contains("فيلم")) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    // =============================== Episodes =============================

    private fun episodeFromA(a: Element, seasonPrefix: String = ""): SEpisode? {
        val href = a.absUrl("href").ifBlank { a.attr("href") }
        if (href.isBlank()) return null
        val epLabel = a.selectFirst(".epnum")?.text()?.trim().orEmpty()
        val name = epLabel.ifBlank {
            a.selectFirst("h2")?.text()?.trim().orEmpty()
        }.ifBlank { a.text().trim() }
        if (name.isEmpty()) return null
        return SEpisode.create().apply {
            url = pathOf(href)
            this.name = if (seasonPrefix.isNotEmpty()) "$seasonPrefix $name" else name
            episode_number = name.filter { it.isDigit() }.toFloatOrNull() ?: 0f
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = abs(anime.url)
        val doc = getDoc(url) ?: return emptyList()
        val episodes = mutableListOf<SEpisode>()

        val seasons = doc.select("section.allseasonss .Small--Box.Season a")
            .mapNotNull { it.absUrl("href").ifBlank { it.attr("href") }.ifBlank { null } }
            .distinct()

        if (seasons.isNotEmpty()) {
            for (seasonUrl in seasons) {
                val seasonDoc = getDoc(seasonUrl) ?: continue
                val seasonName = seasonDoc.selectFirst("section.allseasonss .Small--Box.Season.active .epnum")
                    ?.text()?.replace("الموسم", "")?.trim()
                    ?: seasonDoc.selectFirst("h1.post-title")?.text()
                        ?.let { Regex("""الموسم\s*(\d+)""").find(it)?.groupValues?.get(1) }
                        .orEmpty()
                val prefix = if (seasonName.isNotEmpty()) "الموسم $seasonName" else ""
                seasonDoc.select(".allepcont .row > a").mapNotNullTo(episodes) {
                    episodeFromA(it, prefix)
                }
            }
        } else {
            doc.select(".allepcont .row > a").mapNotNullTo(episodes) { episodeFromA(it) }
        }

        if (episodes.isEmpty()) {
            // Movie (or single-episode page): one watch entry pointing at the page itself.
            episodes += SEpisode.create().apply {
                this.url = pathOf(url)
                name = "مشاهدة"
                episode_number = 1f
            }
        }

        return episodes.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val watchUrl = abs(episode.url).trimEnd('/') + "/watch/"
        // Headers must be ASCII: episode slugs contain Arabic, and a raw (non-encoded)
        // Referer makes OkHttp throw, which silently dropped every ajax server.
        val encodedWatchUrl = watchUrl.toHttpUrl().toString()
        val doc = getDoc(watchUrl, "$baseUrl/") ?: return emptyList()
        val hosters = mutableListOf<Hoster>()

        doc.selectFirst(".player--iframe iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let { src ->
            val label = doc.selectFirst("li.server--item.active")?.text()?.trim()
                ?.ifEmpty { null } ?: src.substringAfter("://").substringBefore(".")
            hosters += Hoster(src, label)
        }

        val serverUrl = "$baseUrl/wp-content/themes/movies2023/Ajaxat/Single/Server.php"
        for (li in doc.select("li.server--item")) {
            val id = li.attr("data-id")
            val index = li.attr("data-server")
            if (id.isBlank() || index.isBlank()) continue
            val body = FormBody.Builder().add("id", id).add("i", index).build()
            val response = try {
                val ajaxHeaders = headers(encodedWatchUrl).newBuilder()
                    .add("X-Requested-With", "XMLHttpRequest")
                    .add("Accept", "*/*")
                    .build()
                client.post(serverUrl.toHttpUrl(), ajaxHeaders, body).body.string()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                continue
            }
            val src = Jsoup.parse(response).selectFirst("iframe")?.attr("src") ?: continue
            if (src.isBlank()) continue
            val label = li.text().trim().ifBlank { "server $index" }
            if (hosters.none { it.hosterUrl == src }) hosters += Hoster(src, label)
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        TopCinemaExtractors.videosFromHoster(client, hoster, "$baseUrl/", userAgent)

    // =============================== Filters ==============================

    private class CategoryFilter(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)

    private data class Category(val label: String, val path: String)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(CATEGORIES.map { it.label }.toTypedArray()),
    )

    companion object {
        private val CATEGORIES = listOf(
            Category("اختر القسم", ""),
            Category("الافلام", "movies/"),
            Category("افلام اجنبي", "category/افلام-اجنبي-8/"),
            Category("افلام انمي", "category/افلام-انمي-2/"),
            Category("افلام اسيوي", "category/افلام-اسيوي/"),
            Category("افلام نتفليكس", "netflix-movies/"),
            Category("مسلسلات", "series/"),
            Category("مسلسلات اجنبي", "category/مسلسلات-اجنبي/"),
            Category("مسلسلات اسيوية", "category/مسلسلات-اسيوية/"),
            Category("انمي", "category/anime-series/"),
        )
    }
}
