package eu.kanade.tachiyomi.animeextension.ar.stardima

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.QualityUtils
import extensions.utils.Source
import extensions.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

class Stardima : Source() {

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = tr(screen, "نطاق الموقع", "Site domain")
            summary = tr(screen, "الحالي: %s (يجب أن يبدأ بـ https://)", "Current: %s (must start with https://)")
            setDefaultValue(DEFAULT_DOMAIN)
            dialogTitle = title
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as? String)?.trim()?.trimEnd('/').orEmpty()
                val ok = QualityUtils.normalizeDomain(value, "") == value && value.isNotEmpty()
                if (!ok) {
                    Toast.makeText(screen.context, tr(screen, "النطاق غير صالح", "Invalid domain"), Toast.LENGTH_SHORT).show()
                }
                ok
            }
        }.also(screen::addPreference)

    }

    private fun tr(screen: PreferenceScreen, ar: String, en: String): String {
        val lang = runCatching { screen.context.resources.configuration.locales.get(0).language }
            .getOrDefault(Locale.getDefault().language)
        return if (lang == "ar") ar else en
    }



    /** The app starts this local server before handing a link to an external player; without one it reports a start failure. */
    override fun createHttpServer(): HttpServer? = HttpServer()

    // ============================ Properties ==============================

    override val name = "Stardima"

    override val lang = "ar"

    override val baseUrl: String
        get() = QualityUtils.normalizeDomain(preferences.getString(PREF_DOMAIN, DEFAULT_DOMAIN), DEFAULT_DOMAIN)

    override val supportsLatest = true

    // =============================== Network ==============================

    private fun pageHeaders(referer: String? = null): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept-Language", "ar,en;q=0.8")
        .apply { if (referer != null) add("Referer", referer) }
        .build()

    private fun jsonHeaders(referer: String): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept", "application/json, text/plain, */*")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .build()

    private suspend fun getDoc(url: String, referer: String? = null): Document? = try {
        val response = client.get(url.toHttpUrl(), pageHeaders(referer))
        Jsoup.parse(response.body.string(), response.request.url.toString())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun getJson(url: String, referer: String): kotlinx.serialization.json.JsonElement? = try {
        val response = client.get(url.toHttpUrl(), jsonHeaders(referer))
        Json.parseToJsonElement(response.body.string())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun abs(path: String): String = QualityUtils.rebase(path, baseUrl).let {
        if (it.startsWith("/")) baseUrl + it else it
    }

    // =============================== Cards ================================

    /** Stored urls are site-relative ("/tvshow/<id>" or "/movie/<id>") so the domain setting applies to the library too. */
    private fun Element.toSAnime(): SAnime? {
        val link = selectFirst("a[href*=/tvshow/], a[href*=/movie/]") ?: return null
        val href = link.attr("href").trim()
        val path = "/" + href.substringAfter("://").substringAfter("/")
        if (!PATH_RE.matches(path)) return null
        val title = link.selectFirst("h3")?.text()?.trim().orEmpty()
            .ifEmpty { selectFirst("img")?.attr("alt")?.removePrefix("Poster for ")?.trim().orEmpty() }
        if (title.isEmpty()) return null
        return SAnime.create().apply {
            url = path
            this.title = title
            thumbnail_url = selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") && "placehold.co" !in it }
        }
    }

    private fun Document.cards(): List<SAnime> {
        val seen = mutableSetOf<String>()
        return select("a[href*=/tvshow/], a[href*=/movie/]")
            .mapNotNull { a -> a.parent()?.toSAnime() }
            .filter { seen.add(it.url) }
    }

    // ============================== Listings ==============================

    private suspend fun browse(path: String, page: Int): AnimesPage {
        val url = abs(path) + if (page > 1) "?page=$page" else ""
        val doc = getDoc(url) ?: return AnimesPage(emptyList(), false)
        val items = doc.cards()
        return AnimesPage(items, items.size >= PAGE_SIZE)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = browse("/mosalsalat", page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        // The latest list is a single page; later pages repeat it.
        if (page > 1) return AnimesPage(emptyList(), false)
        return browse("/newrelases", 1)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SectionFilter(),
    )

    private class SectionFilter : AnimeFilter.Select<String>(
        "القسم / Section",
        arrayOf("مسلسلات / Series", "أفلام / Movies"),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val q = query.trim()
        if (q.isEmpty()) {
            val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.state ?: 0
            return browse(if (section == 1) "/aflam" else "/mosalsalat", page)
        }
        // Search results come on one page.
        if (page > 1) return AnimesPage(emptyList(), false)
        val doc = getDoc(abs("/search/" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")))
            ?: return AnimesPage(emptyList(), false)
        return AnimesPage(doc.cards(), false)
    }

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = getDoc(abs(anime.url)) ?: throw Exception("Stardima: page not found")
        return anime.apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" | ")?.substringBefore(" - ستارديما")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: title
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.startsWith("http") } ?: thumbnail_url
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val detailsUrl = abs(anime.url)
        if (anime.url.startsWith("/movie/")) {
            val id = anime.url.substringAfterLast("/")
            return listOf(
                SEpisode.create().apply {
                    url = "/play/$id"
                    name = anime.title
                    episode_number = 1f
                },
            )
        }

        val details = getDoc(detailsUrl) ?: return emptyList()
        val playHref = details.select("a[href*=/play/]").firstOrNull()?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val play = getDoc(abs("/" + playHref.substringAfter("://").substringAfter("/")), detailsUrl) ?: return emptyList()

        val seasons = play.select("[data-season-id]")
            .map { it.attr("data-season-id") to it.attr("data-season-number").trim() }
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
        val initial = play.selectFirst("#episodes-list-container")?.attr("data-initial-season-id").orEmpty()
        val seasonList = seasons.ifEmpty { if (initial.isNotBlank()) listOf(initial to "") else emptyList() }

        val episodes = mutableListOf<SEpisode>()
        seasonList.forEachIndexed { index, (seasonId, label) ->
            val data = getJson(abs("/series/season/$seasonId?X-Requested-With=XMLHttpRequest"), detailsUrl)
                ?.jsonObject ?: return@forEachIndexed
            val items = data["episodes"] as? JsonArray ?: return@forEachIndexed
            items.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val watch = obj["watch_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
                    ?: return@forEach
                val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val number = obj["episode_number"]?.jsonPrimitive?.intOrNull ?: (episodes.size + 1)
                episodes += SEpisode.create().apply {
                    // The player link is on the video host, not the site domain, so it is stored as is.
                    url = watch
                    name = if (seasonList.size > 1 && label.isNotBlank()) "$label $title" else title.ifBlank { "EP$number" }
                    episode_number = number.toFloat() + if (seasonList.size > 1) (index + 1) * 1000f else 0f
                }
            }
        }
        return episodes.reversed()
    }

    // =============================== Videos ===============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val watchUrl = if (episode.url.startsWith("/")) {
            val page = getDoc(abs(episode.url)) ?: return emptyList()
            page.select("iframe[src]").map { it.attr("abs:src") }
                .firstOrNull { "/watch/" in it } ?: return emptyList()
        } else {
            episode.url
        }
        val parsed = watchUrl.toHttpUrlOrNull() ?: return emptyList()
        val origin = "${parsed.scheme}://${parsed.host}"
        val hash = parsed.pathSegments.lastOrNull().orEmpty()
        val player = getDoc(watchUrl, abs("/")) ?: return emptyList()
        val raw = player.selectFirst("#app[data-page]")?.attr("data-page") ?: return emptyList()
        val servers = runCatching {
            Json.parseToJsonElement(raw).jsonObject["props"]!!.jsonObject["video"]!!.jsonObject["servers"]!!.jsonArray
        }.getOrNull() ?: return emptyList()

        return servers.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            if (obj["is_vip"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "iframe") return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val serverName = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Server"
            // Streamhg builds its player with page scripts, so no direct link can be read from it.
            if (serverName.contains("streamhg", ignoreCase = true)) return@mapNotNull null
            Hoster(hosterUrl = "$origin/embed/$hash/server/$id/url|$watchUrl", hosterName = serverName)
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val endpoint = hoster.hosterUrl.substringBefore("|")
        val referer = hoster.hosterUrl.substringAfter("|", "")
        val json = getJson(endpoint, referer)?.jsonObject ?: return emptyList()
        val watch = json["watch_url"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        // The site wraps the real host in an embed page: ...?id=<url-encoded host link>.
        val inner = watch.toHttpUrlOrNull()?.queryParameter("id")?.takeIf { it.startsWith("http") }
            ?: watch
        return StardimaExtractors.resolve(client, inner, abs("/"), hoster.hosterName, null)
    }
}

private val PATH_RE = Regex("""/(tvshow|movie)/[0-9A-Za-z_-]+""")
private const val PAGE_SIZE = 12
private const val DEFAULT_DOMAIN = "https://watch-jz.stardima.com.im"
private const val PREF_DOMAIN = "domain"
