package eu.kanade.tachiyomi.animeextension.ar.anime3rb

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.Source
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Anime3rb source, written from scratch from the public pages of the site.
 *
 * Pages used:
 *  - home page: "latest episodes" cards (a[href*=/episode/])
 *  - /titles/list: catalogue, 20 items per page, filters as query parameters
 *  - /titles/<slug>: details and episode list
 *  - /episode/<slug>/<n>: contains the player iframe; the player page (requested with the
 *    site as referer) carries a `video_sources` list with one direct file per quality.
 */
class Anime3rb : Source() {

    override val name = "Anime3rb"

    override val lang = "ar"

    override val supportsLatest = true

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN, DEFAULT_DOMAIN)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: DEFAULT_DOMAIN

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val isArabic: Boolean
        get() = runCatching {
            context.resources.configuration.locales.get(0).language == "ar"
        }.getOrDefault(Locale.getDefault().language == "ar")

    private fun tr(ar: String, en: String) = if (isArabic) ar else en

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = tr("نطاق الموقع", "Site domain")
            summary = tr(
                "الحالي: %s (يجب أن يبدأ بـ https://)",
                "Current: %s (must start with https://)",
            )
            setDefaultValue(DEFAULT_DOMAIN)
            dialogTitle = title
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as? String)?.trim()?.trimEnd('/').orEmpty()
                val ok = value.startsWith("https://") || value.startsWith("http://")
                if (!ok) {
                    displayToast(tr("النطاق غير صالح", "Invalid domain"))
                }
                ok
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = tr("الجودة المفضلة", "Preferred quality")
            entries = arrayOf(
                tr("الأعلى المتاحة", "Highest available"),
                "1080p",
                "720p",
                "480p",
            )
            entryValues = arrayOf(QUALITY_BEST, "1080", "720", "480")
            setDefaultValue(QUALITY_BEST)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY, QUALITY_BEST) ?: QUALITY_BEST

    // ============================== Helpers ===============================

    /** Keeps only the path so stored absolute URLs keep working after a domain change. */
    private fun toPath(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val parsed = trimmed.toHttpUrl()
            val query = parsed.encodedQuery?.let { "?$it" }.orEmpty()
            parsed.encodedPath + query
        } else {
            trimmed
        }
    }

    private fun absolute(path: String) = if (path.startsWith("http")) path else baseUrl + path

    private suspend fun getDocument(url: String, headers: Headers = this.headers): Document {
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        return response.use { Jsoup.parse(it.body.string(), url) }
    }

    private fun seriesFromCard(card: Element): SAnime? {
        val href = card.attr("href").takeIf { it.contains("/titles/") } ?: return null
        val title = card.selectFirst(".title-name")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        return SAnime.create().apply {
            url = toPath(href)
            this.title = title
            thumbnail_url = card.selectFirst("img")?.let { it.absUrl("src").ifEmpty { it.attr("src") } }
        }
    }

    private fun parseCatalogue(document: Document): AnimesPage {
        val animes = document.select("a[href*=/titles/]:has(.title-name)")
            .mapNotNull(::seriesFromCard)
            .filterNot { it.url.startsWith("/titles/list") }
            .distinctBy { it.url }
        return AnimesPage(animes, animes.size >= PAGE_SIZE)
    }

    // =============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/titles/list?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseCatalogue(Jsoup.parse(response.use { it.body.string() }, baseUrl))

    // =============================== Latest ===============================

    /**
     * The "latest episodes" cards on the home page are episode-level, so every episode that
     * is newly published appears here under its series.
     */
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.use { it.body.string() }, baseUrl)
        val seen = LinkedHashSet<String>()
        val animes = document.select("a[href*=/episode/]:has(.title-name)").mapNotNull { card ->
            val match = EPISODE_URL.find(card.attr("href")) ?: return@mapNotNull null
            val path = "/titles/${match.groupValues[1]}"
            if (!seen.add(path)) return@mapNotNull null
            val title = card.selectFirst(".title-name")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            SAnime.create().apply {
                url = path
                this.title = title
                thumbnail_url = card.selectFirst("img")?.let { it.absUrl("src").ifEmpty { it.attr("src") } }
            }
        }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val fromFilter = filters.filterIsInstance<YearFromFilter>().firstOrNull()
        val toFilter = filters.filterIsInstance<YearToFilter>().firstOrNull()

        // A text query uses the site's own /search page, which takes only the query and
        // the page number. Filters apply to the catalogue browse (empty query).
        if (query.isNotBlank()) {
            val searchUrl = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim())
                .addQueryParameter("page", page.toString())
                .build()
            return GET(searchUrl, headers)
        }

        val typePath = typeFilter?.selectedValue().orEmpty()
        val builder = "$baseUrl/titles/list${if (typePath.isEmpty()) "" else "/$typePath"}".toHttpUrl().newBuilder()

        genreFilter?.state?.filter { it.state }?.forEachIndexed { index, genre ->
            builder.addQueryParameter("genres[$index]", genre.slug)
        }
        statusFilter?.selectedValue()?.takeIf { it.isNotEmpty() }?.let {
            builder.addQueryParameter("status[0]", it)
        }

        val from = fromFilter?.selectedValue().orEmpty()
        val to = toFilter?.selectedValue().orEmpty()
        if (from.isNotEmpty() || to.isNotEmpty()) {
            val start = from.ifEmpty { to }
            val end = to.ifEmpty { from }
            builder.addQueryParameter("year", "$start-$end")
        }

        builder.addQueryParameter("page", page.toString())
        return GET(builder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseCatalogue(Jsoup.parse(response.use { it.body.string() }, baseUrl))

    // =============================== Filters ==============================

    private class Genre(val slug: String, val arabic: String, val english: String)

    private inner class GenreCheckBox(genre: Genre) :
        AnimeFilter.CheckBox(if (isArabic) genre.arabic else genre.english) {
        val slug = genre.slug
    }

    private inner class GenreFilter :
        AnimeFilter.Group<GenreCheckBox>(
            tr("التصنيفات", "Genres"),
            GENRES.map { GenreCheckBox(it) },
        )

    private abstract class ValueSelect(name: String, private val items: List<Pair<String, String>>) :
        AnimeFilter.Select<String>(name, items.map { it.first }.toTypedArray()) {
        fun selectedValue(): String = items[state].second
    }

    private inner class TypeFilter : ValueSelect(
        tr("النوع", "Type"),
        listOf(
            tr("الكل", "All") to "",
            tr("مسلسل", "Series") to "tv",
            tr("فيلم", "Movie") to "movie",
            "ONA" to "ona",
            "OVA" to "ova",
            tr("حلقة خاصة", "Special") to "special",
        ),
    )

    private inner class StatusFilter : ValueSelect(
        tr("الحالة", "Status"),
        listOf(
            tr("الكل", "All") to "",
            tr("قيد البث", "Ongoing") to "running",
            tr("مكتمل", "Completed") to "finished",
        ),
    )

    private fun yearItems(): List<Pair<String, String>> =
        listOf(tr("أي سنة", "Any") to "") + (2027 downTo 1980).map { it.toString() to it.toString() }

    private inner class YearFromFilter : ValueSelect(tr("من سنة", "Year from"), yearItems())

    private inner class YearToFilter : ValueSelect(tr("إلى سنة", "Year to"), yearItems())

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        StatusFilter(),
        YearFromFilter(),
        YearToFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
    )

    // =============================== Details ==============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = getDocument(absolute(anime.url))
        return SAnime.create().apply {
            url = anime.url
            title = anime.title
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: anime.thumbnail_url
            description = document.selectFirst("p.leading-loose")?.text()?.trim()
            val genres = document.select("a.genre").map { it.ownText().trim() }.filter { it.isNotEmpty() }
            genre = genres.distinct().joinToString().ifEmpty { null }

            val rows = document.select("tr").associate { row ->
                val cells = row.select("td")
                (cells.getOrNull(0)?.text()?.trim().orEmpty()) to (cells.getOrNull(1)?.text()?.trim().orEmpty())
            }
            status = when {
                rows["الحالة:"]?.contains("بث") == true -> SAnime.ONGOING
                rows["الحالة:"]?.let { it.contains("مكتمل") || it.contains("منته") } == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            author = rows["الاستديو:"]?.ifEmpty { null }
            artist = rows["المخرج:"]?.ifEmpty { null }
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = getDocument(absolute(anime.url))
        val episodes = document.select("a[href*=/episode/]:has(.video-data)").mapNotNull { link ->
            val href = link.attr("href")
            val match = EPISODE_URL.find(href) ?: return@mapNotNull null
            val number = match.groupValues[2].toFloatOrNull() ?: return@mapNotNull null
            val subtitle = link.selectFirst(".video-data p")?.text()?.trim().orEmpty()
            SEpisode.create().apply {
                url = toPath(href)
                episode_number = number
                val label = tr("الحلقة", "Episode") + " " + match.groupValues[2]
                name = if (subtitle.isEmpty()) label else "$label - $subtitle"
                preview_url = link.selectFirst("img")?.absUrl("src")?.ifEmpty { null }
            }
        }
        return episodes.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    // ================================ Videos ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeDocument = getDocument(absolute(episode.url))
        val playerUrl = episodeDocument.selectFirst("iframe[src*=/player/]")?.attr("src")
            ?: PLAYER_URL.find(episodeDocument.html().replace("\\/", "/").replace("&amp;", "&"))?.value
            ?: throw Exception(tr("لم يتم العثور على المشغل", "Player not found"))

        val playerOrigin = playerUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val playerHeaders = headersBuilder().set("Referer", "$baseUrl/").build()
        val html = client.newCall(GET(playerUrl, playerHeaders)).awaitSuccess()
            .use { it.body.string() }

        val raw = SOURCES.find(html)?.groupValues?.get(1)
            ?: throw Exception(tr("لا توجد جودات متاحة", "No qualities available"))

        val videoHeaders = Headers.Builder()
            .add("Referer", "$playerOrigin/")
            .add("User-Agent", headers["User-Agent"] ?: "")
            .build()

        val videos = (json.parseToJsonElement(raw) as JsonArray).mapNotNull { element ->
            val item = element.jsonObject
            if (item.bool("premium")) return@mapNotNull null
            val src = item.str("src").replace("\\/", "/")
            if (src.isEmpty()) return@mapNotNull null
            val label = item.str("label").ifEmpty { item.str("res") + "p" }
            Video(
                videoUrl = src,
                videoTitle = "Anime3rb - $label",
                resolution = item.str("res").toIntOrNull(),
                headers = videoHeaders,
            )
        }

        if (videos.isEmpty()) {
            throw Exception(tr("الحلقة متاحة للمشتركين فقط", "This episode is premium only"))
        }

        return listOf(Hoster(hosterName = "Anime3rb", videoList = videos.sortedByPreference()))
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.contentOrNull == "true"

    private fun List<Video>.sortedByPreference(): List<Video> {
        val wanted = preferredQuality.toIntOrNull()
        return if (wanted == null) {
            sortedByDescending { it.resolution ?: 0 }
        } else {
            // Exact match first, then the closest lower qualities, then higher ones.
            sortedWith(
                compareBy<Video> { (it.resolution ?: 0) > wanted }
                    .thenByDescending { it.resolution ?: 0 },
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> = sortedByPreference()

    companion object {
        private const val DEFAULT_DOMAIN = "https://anime3rb.com"
        private const val PREF_DOMAIN = "domain"
        private const val PREF_QUALITY = "preferred_quality"
        private const val QUALITY_BEST = "best"
        private const val PAGE_SIZE = 20

        private val EPISODE_URL = Regex("""/episode/([^/?#]+)/(\d+(?:\.\d+)?)""")
        private val PLAYER_URL = Regex("""https?://[^\s"'<>]*/player/[^\s"'<>]+""")
        private val SOURCES = Regex("""var\s+video_sources\s*=\s*(\[\s*\{.*?\}\s*\]);""", RegexOption.DOT_MATCHES_ALL)

        private val GENRES = listOf(
        Genre("action", "أكشن", "Action"),
        Genre("comedy", "كوميدي", "Comedy"),
        Genre("fantasy", "خيال", "Fantasy"),
        Genre("adventure", "مغامرة", "Adventure"),
        Genre("drama", "دراما", "Drama"),
        Genre("shounen", "شونين", "Shounen"),
        Genre("romance", "رومانسي", "Romance"),
        Genre("school", "مدرسي", "School"),
        Genre("sci-fi", "خيال علمي", "Sci Fi"),
        Genre("supernatural", "خارق للطبيعة", "Supernatural"),
        Genre("seinen", "سينين", "Seinen"),
        Genre("mystery", "غموض", "Mystery"),
        Genre("adult-cast", "بطولة راشدين", "Adult Cast"),
        Genre("ecchi", "إيتشي", "Ecchi"),
        Genre("historical", "تاريخي", "Historical"),
        Genre("slice-of-life", "الحياة اليومية", "Slice Of Life"),
        Genre("super-power", "قوى خارقة", "Super Power"),
        Genre("mecha", "ميكا", "Mecha"),
        Genre("harem", "حريم", "Harem"),
        Genre("military", "عسكري", "Military"),
        Genre("sports", "رياضي", "Sports"),
        Genre("isekai", "إيسيكاي", "Isekai"),
        Genre("suspense", "تشويق", "Suspense"),
        Genre("shoujo", "شوچو", "Shoujo"),
        Genre("mythology", "أساطير", "Mythology"),
        Genre("psychological", "نفسي", "Psychological"),
        Genre("horror", "رعب", "Horror"),
        Genre("music", "موسيقى", "Music"),
        Genre("gore", "دموي", "Gore"),
        Genre("parody", "ساخر", "Parody"),
        Genre("martial-arts", "قتالي", "Martial Arts"),
        Genre("detective", "بوليسي", "Detective"),
        Genre("space", "فضاء", "Space"),
        Genre("cgdct", "كيوت", "Cgdct"),
        Genre("award-winning", "حائز على جوائز", "Award Winning"),
        Genre("team-sports", "رياضات جماعية", "Team Sports"),
        Genre("gag-humor", "كوميديا حركية", "Gag Humor"),
        Genre("kids", "للأطفال", "Kids"),
        Genre("urban-fantasy", "خيال حضري", "Urban Fantasy"),
        Genre("iyashikei", "إياشيكي", "Iyashikei"),
        Genre("reincarnation", "تناسخ و إعادة إحياء", "Reincarnation"),
        Genre("mahou-shoujo", "فتاة ساحرة", "Mahou Shoujo"),
        Genre("workplace", "عمل", "Workplace"),
        Genre("anthropomorphic", "أنثروبولوجي", "Anthropomorphic"),
        Genre("vampire", "مصاصي دماء", "Vampire"),
        Genre("samurai", "ساموراي", "Samurai"),
        Genre("time-travel", "سفر عبر الزمن", "Time Travel"),
        Genre("josei", "چوسي", "Josei"),
        Genre("strategy-game", "استراتيجي", "Strategy Game"),
        Genre("love-polygon", "حب متعدد الأطراف", "Love Polygon"),
        Genre("otaku-culture", "ثقافة الأوتاكو", "Otaku Culture"),
        Genre("idols-female", "أيدول إناث", "Idols Female"),
        Genre("organized-crime", "جريمة منظمة", "Organized Crime"),
        Genre("video-game", "ألعاب فيديو", "Video Game"),
        Genre("gourmet", "طعام", "Gourmet"),
        Genre("survival", "نجاة", "Survival"),
        Genre("performing-arts", "فنون استعراضية", "Performing Arts"),
        Genre("racing", "سباق", "Racing"),
        Genre("girls-love", "حب فتيات", "Girls Love"),
        Genre("avant-garde", "ابتكاري", "Avant Garde"),
        Genre("reverse-harem", "عكس حريم", "Reverse Harem"),
        Genre("combat-sports", "رياضات قتالية", "Combat Sports"),
        Genre("childcare", "رعاية أطفال", "Childcare"),
        Genre("visual-arts", "فنون بصرية", "Visual Arts"),
        Genre("high-stakes-game", "ألعاب عالية المخاطر", "High Stakes Game"),
        Genre("love-status-quo", "حالة حب", "Love Status Quo"),
        Genre("delinquents", "جانحون", "Delinquents"),
        Genre("idols-male", "أيدول ذكور", "Idols Male"),
        Genre("pets", "حيوانات أليفة", "Pets"),
        Genre("crossdressing", "تنكر في ملابس الجنس الآخر", "Crossdressing"),
        Genre("medical", "طبي", "Medical"),
        Genre("boys-love", "حب فتيان", "Boys Love"),
        Genre("magical-sex-shift", "تبديل جنسي سحري", "Magical Sex Shift"),
        Genre("showbiz", "صناعة الترفيه", "Showbiz"),
        Genre("villainess", "شريرة", "Villainess"),
        Genre("erotica", "ايروتيكا", "Erotica"),
        Genre("educational", "تعليمية", "Educational")
        )
    }
}
