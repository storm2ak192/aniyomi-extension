package eu.kanade.tachiyomi.animeextension.ar.egydead

import android.webkit.WebSettings
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * EgyDead (Arabic) - movies and series.
 *
 * The site is a WordPress theme. Lists and details are plain HTML. The watch page hides
 * its server list behind a POST with form field View=1; that list points at a MegaMax
 * aggregator iframe whose mirror links load through its InertiaJS "streams" prop.
 * Stream resolution from the mirror hosts lives in EgyDeadExtractors.
 */
class EgyDead : Source() {

    // The device's real WebView UA, so any solved cf_clearance is accepted.
    private val userAgent: String by lazy { WebSettings.getDefaultUserAgent(context) }

    override val name = "EgyDead"

    override val lang = "ar"

    override val baseUrl = "https://egweg1.sbs"

    override val supportsLatest = true

    // The app's network.client already carries its Cloudflare interceptor; adding a second
    // one doubles the bypass stall, so this source relies on the stock client.

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // No user-configurable settings.
    }

    // =============================== Network ==============================

    private fun headers(referer: String? = null): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept-Language", "ar,en;q=0.9")
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

    private fun abs(path: String): String = if (path.startsWith("http")) path else baseUrl + "/" + path.trimStart('/')

    private fun pathOf(href: String): String = "/" + href.substringAfter("://").substringAfter("/")

    // =============================== Lists ================================

    private fun Document.toAnimesPage(selector: String): AnimesPage {
        val items = select(selector).mapNotNull { el ->
            val a = el.selectFirst("a[href]") ?: return@mapNotNull null
            val title = el.selectFirst("h1.BottomTitle")?.text()?.trim()
                ?.ifEmpty { null } ?: a.attr("title").ifBlank { a.text() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            SAnime.create().apply {
                url = pathOf(a.absUrl("href").ifBlank { a.attr("href") })
                this.title = title
                thumbnail_url = el.selectFirst("img")?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }?.takeIf { src -> src.startsWith("http") }
            }
        }.distinctBy { it.url }
        val hasNext = select("div.pagination a.next, ul.page-numbers a.next, a:contains(›)").isNotEmpty()
        return AnimesPage(items, hasNext)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val doc = getDoc(baseUrl) ?: return AnimesPage(emptyList(), false)
        return doc.toAnimesPage("div.pin-posts-list li.movieItem")
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val doc = getDoc("$baseUrl/?page=$page/") ?: return AnimesPage(emptyList(), false)
        return doc.toAnimesPage("section.main-section li.movieItem")
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val doc = getDoc("$baseUrl/page/$page/?s=$query") ?: return AnimesPage(emptyList(), false)
            return doc.toAnimesPage("li.movieItem")
        }
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()
            ?.takeIf { it.state > 0 }
            ?.let { CATEGORIES[it.state].path }
        if (category == null) return getLatestUpdates(page)
        val doc = getDoc("$baseUrl/$category?page=$page/") ?: return AnimesPage(emptyList(), false)
        return doc.toAnimesPage("li.movieItem")
    }

    // =============================== Details ==============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDoc(abs(anime.url)) ?: return anime
        return anime.apply {
            doc.selectFirst("div.infoBox div.singleTitle")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { title = it }
            thumbnail_url = doc.selectFirst("div.single-thumbnail img")?.attr("src")
                ?.takeIf { it.startsWith("http") } ?: thumbnail_url
            author = doc.select("div.LeftBox li:contains(البلد) a").text().ifBlank { null } ?: author
            genre = doc.select("div.LeftBox li:contains(النوع) a, div.LeftBox li:contains(القسم) a, div.LeftBox li:contains(السنه) a")
                .joinToString(", ") { it.text() }.ifBlank { null } ?: genre
            description = doc.selectFirst("div.infoBox div.extra-content p")?.text()?.trim()
                ?.ifEmpty { null } ?: description
            status = if (title.contains("فيلم") || title.contains("كامل")) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    // =============================== Episodes =============================

    private fun episodeFromA(a: org.jsoup.nodes.Element, seasonPrefix: String = ""): SEpisode? {
        val href = a.absUrl("href").ifBlank { a.attr("href") }
        if (href.isBlank()) return null
        val name = a.text().trim().ifBlank { a.attr("title").trim() }
        if (name.isEmpty()) return null
        return SEpisode.create().apply {
            url = pathOf(href)
            this.name = if (seasonPrefix.isNotEmpty()) "$seasonPrefix $name" else name
            episode_number = name.filter { it.isDigit() }.toFloatOrNull() ?: 0f
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = abs(anime.url)
        val episodes = mutableListOf<SEpisode>()

        suspend fun episodesFromSeason(seasonUrl: String, prefix: String = "") {
            val doc = getDoc(seasonUrl) ?: return
            doc.select("div.EpsList li a").mapNotNullTo(episodes) { episodeFromA(it, prefix) }
        }

        when {
            "/episode/" in url -> {
                // Episode page: jump to its season page from the breadcrumbs.
                val doc = getDoc(url)
                val season = doc?.selectFirst("a[href*='/season/']")?.let {
                    it.absUrl("href").ifBlank { it.attr("href") }
                }
                if (season != null) {
                    episodesFromSeason(season)
                } else {
                    episodes += SEpisode.create().apply {
                        this.url = pathOf(url)
                        name = "مشاهدة"
                        episode_number = 0f
                    }
                }
            }
            "/season/" in url -> episodesFromSeason(url)
            "/serie/" in url -> {
                val doc = getDoc(url)
                val seasons = doc?.select("div.seasons-list li.movieItem a").orEmpty()
                    .mapNotNull { it.absUrl("href").ifBlank { it.attr("href") }.ifBlank { null } }
                    .filter { "/season/" in it }
                    .distinct()
                for (seasonUrl in seasons) {
                    val seasonName = getDoc(seasonUrl)
                        ?.selectFirst("div.infoBox div.singleTitle")?.text()
                        ?.substringAfter("الموسم")?.trim().orEmpty()
                    val prefix = if (seasonName.isNotEmpty()) "الموسم $seasonName" else ""
                    episodesFromSeason(seasonUrl, prefix)
                }
                if (episodes.isEmpty()) episodesFromSeason(url)
            }
            "/assembly" in url -> {
                // Movie collection: each entry is a movie with its own watch page.
                val doc = getDoc(url)
                doc?.select("div.salery-list li.movieItem a, li.movieItem a").orEmpty()
                    .mapNotNullTo(episodes) { episodeFromA(it) }
            }
            else -> {
                // Movie: one watch entry pointing at the page itself.
                episodes += SEpisode.create().apply {
                    this.url = pathOf(url)
                    name = "مشاهدة"
                    episode_number = 1f
                }
            }
        }

        return episodes.distinctBy { it.url }.sortedBy { it.episode_number }
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val watchUrl = abs(episode.url)
        val body = FormBody.Builder().add("View", "1").build()
        // Let network errors (including the app's Cloudflare bypass failure) propagate so the
        // app shows the real cause instead of a silent "no available video".
        val response = client.post(watchUrl.toHttpUrl(), headers("$baseUrl/"), body)
        val bodyText = response.body.string()
        if (!response.isSuccessful) {
            throw Exception("EgyDead: watch page answered HTTP ${response.code}")
        }
        val doc = Jsoup.parse(bodyText, watchUrl)
        val hosters = doc.select("ul.serversList li").mapNotNull { li ->
            val link = li.attr("data-link").ifBlank {
                li.selectFirst("[data-link]")?.attr("data-link")
            }.orEmpty()
            if (link.isBlank()) return@mapNotNull null
            val label = li.text().trim().ifBlank { "server" }
            Hoster(link, label)
        }.distinctBy { it.hosterUrl }
        if (hosters.isEmpty()) {
            throw Exception("EgyDead: no servers found on the watch page")
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        EgyDeadExtractors.videosFromHoster(client, hoster, "$baseUrl/", userAgent, json)

    // =============================== Filters ==============================

    private class CategoryFilter(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)

    private data class Category(val label: String, val path: String)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(CATEGORIES.map { it.label }.toTypedArray()),
    )

    companion object {
        private val CATEGORIES = listOf(
            Category("اختر القسم", ""),
            Category("افلام اجنبي", "category/english-movies/"),
            Category("افلام اجنبية مدبلجة", "category/english-movies/english-movies-dubbed/"),
            Category("افلام اسيوية", "category/افلام-اسيوية/"),
            Category("افلام انمي", "category/افلام-انمي/"),
            Category("افلام تركية", "category/افلام-تركية/"),
            Category("افلام تركية مدبلجة", "category/افلام-تركية/افلام-تركية-مدبلجة/"),
            Category("افلام عربي", "category/افلام-عربي/"),
            Category("افلام مصرية", "category/افلام-عربي/افلام-مصرية/"),
            Category("افلام كرتون", "category/افلام-كرتون/"),
            Category("افلام هندية", "category/hindi-movies/"),
            Category("افلام وثائقية", "category/افلام-وثائقية/"),
            Category("سلاسل الافلام", "assembly/"),
            Category("مسلسلات اجنبي", "series-category/english-series/"),
            Category("مسلسلات اجنبي مدبلجة", "series-category/english-series-dubbed/"),
            Category("مسلسلات اسيوية", "series-category/asian-series/"),
            Category("مسلسلات انمي", "series-category/anime-series/"),
            Category("مسلسلات تركية", "series-category/turkish-series/"),
            Category("مسلسلات عربي", "series-category/arabic-series/"),
            Category("مسلسلات كرتون", "series-category/cartoon-series/"),
            Category("مسلسلات لاتينية", "series-category/latino-series/"),
            Category("مسلسلات هندية", "series-category/indian-series/"),
            Category("برامج تلفزيونية", "series-category/tv-shows/"),
        )
    }
}
