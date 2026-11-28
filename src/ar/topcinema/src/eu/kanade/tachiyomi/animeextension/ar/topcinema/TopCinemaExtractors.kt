package eu.kanade.tachiyomi.animeextension.ar.topcinema

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.get
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLDecoder

/**
 * Resolves TopCinema watch links down to playable streams.
 *
 * Server.php iframes point at embed hosts (vidtube, okstream, doodstream, updown, byse,
 * firestream, streamtape, lulustream). Most carry a Dean Edwards packed script whose
 * decoded "file:"/"sources:" entries hold the real mp4/m3u8. Some links arrive wrapped
 * in a play.php?to= redirector and are unwrapped first.
 */
object TopCinemaExtractors {

    private val PACKED = Regex("""\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)""")

    private val STREAM = Regex("""(https?://[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*)""")

    private val FILE_ENTRY = Regex("""file\s*:\s*"(https?://[^"]+)""")

    private val LABEL_ENTRY = Regex("""label\s*:\s*"([^"]+)"""")

    suspend fun videosFromHoster(
        client: OkHttpClient,
        hoster: Hoster,
        referer: String,
        userAgent: String,
    ): List<Video> {
        val url = unwrapPlayUrl(hoster.hosterUrl)
        if (url.isBlank()) return emptyList()
        return try {
            when {
                DOOD_REGEX.containsMatchIn(url) -> dood(client, url, hoster.hosterName, userAgent)
                "streamtape" in url -> streamtape(client, url, hoster.hosterName, userAgent)
                else -> packed(client, url, hoster.hosterName, userAgent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // Some server links come wrapped: .../play.php?to=<urlencoded embed url>, sometimes
    // double-wrapped. Decode and keep the last real URL.
    private fun unwrapPlayUrl(url: String): String {
        if (!url.contains("play.php?to=")) return url
        return try {
            var decoded = URLDecoder.decode(url.substringAfter("play.php?to="), "UTF-8").trim()
            if (decoded.indexOf("https://") != decoded.lastIndexOf("https://")) {
                decoded = decoded.substring(decoded.lastIndexOf("https://"))
            }
            when {
                decoded.startsWith("http") -> decoded
                decoded.startsWith("//") -> "https:$decoded"
                else -> url
            }
        } catch (_: Throwable) {
            url
        }
    }

    private fun pageHeaders(userAgent: String, referer: String): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Referer", referer)
        .add("Accept", "*/*")
        .build()

    private suspend fun fetch(client: OkHttpClient, url: String, userAgent: String, referer: String): String? {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        return try {
            client.get(fixed.toHttpUrl(), pageHeaders(userAgent, referer)).body.string()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    // vidtube / okstream / updown / byse / firestream / lulustream:
    // the embed page carries a packed script that decodes to file/label entries,
    // or sometimes a plain sources list.
    private suspend fun packed(
        client: OkHttpClient,
        url: String,
        label: String,
        userAgent: String,
    ): List<Video> {
        val host = "https://" + url.toHttpUrl().host
        val html = fetch(client, url, userAgent, host) ?: return emptyList()
        val unpacked = unpackFirst(html)?.replace("\\/", "/") ?: html.replace("\\/", "/")

        val files = FILE_ENTRY.findAll(unpacked).map { it.groupValues[1] }.toList()
        val labels = LABEL_ENTRY.findAll(unpacked).map { it.groupValues[1] }.toList()
        if (files.isNotEmpty()) {
            return files.mapIndexedNotNull { index, fileUrl ->
                val quality = labels.getOrNull(index)?.trim().orEmpty()
                val name = listOf(label, quality).filter { it.isNotBlank() }.joinToString(" ")
                Video(fileUrl, name.ifBlank { host }, headers = pageHeaders(userAgent, "$host/"))
            }.distinctBy { it.videoUrl }
        }

        val stream = STREAM.find(unpacked)?.value
            ?: PROTO_STREAM.find(unpacked)?.value?.let { "https:$it" }
            ?: return emptyList()
        return listOf(Video(stream, label.ifBlank { host }, headers = pageHeaders(userAgent, "$host/")))
    }

    // Protocol-relative stream URLs (e.g. mixdrop's wurl).
    private val PROTO_STREAM = Regex("""(//[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*)""")

    private val STREAMTAPE_PARTS = Regex(
        """"(//[^"]*?get_video\?[^"]*?)"\s*\+\s*'([^']*)'""",
    )

    private suspend fun streamtape(
        client: OkHttpClient,
        url: String,
        label: String,
        userAgent: String,
    ): List<Video> {
        val host = "https://" + url.toHttpUrl().host
        val html = fetch(client, url, userAgent, host) ?: return emptyList()
        val match = STREAMTAPE_PARTS.find(html) ?: return emptyList()
        val stream = "https:" + match.groupValues[1] + match.groupValues[2]
        return listOf(Video(stream, label.ifBlank { "streamtape" }, headers = pageHeaders(userAgent, "$host/")))
    }

    private val DOOD_REGEX = Regex(
        """do*d(?:stream)?\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re|yt|stream)|ds2play|dood\.""",
    )

    private val PASS_MD5 = Regex("""(/pass_md5/[^'"<>]+)""")

    private suspend fun dood(client: OkHttpClient, url: String, label: String, userAgent: String): List<Video> {
        val host = "https://" + url.toHttpUrl().host
        val html = fetch(client, url, userAgent, host) ?: return emptyList()
        val passPath = PASS_MD5.find(html)?.groupValues?.get(1) ?: return emptyList()
        val token = passPath.substringAfterLast("/")
        val body = fetch(client, host + passPath, userAgent, url) ?: return emptyList()
        val expiry = System.currentTimeMillis()
        val random = (1..10).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val stream = "$body$random?token=$token&expiry=$expiry"
        return listOf(Video(stream, label.ifBlank { "doodstream" }, headers = pageHeaders(userAgent, "$host/")))
    }

    // ============================ Packer ==================================

    private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun unpackFirst(html: String): String? {
        val match = PACKED.find(html) ?: return null
        val payload = match.groupValues[1]
        val radix = match.groupValues[2].toIntOrNull() ?: 62
        val count = match.groupValues[3].toIntOrNull() ?: 0
        val keywords = match.groupValues[4].split("|")
        return unpack(payload, radix, count, keywords)
    }

    private fun unpack(payload: String, radix: Int, count: Int, keywords: List<String>): String {
        fun encode(number: Int): String {
            if (number <= 0) return BASE62[0].toString()
            var value = number
            val builder = StringBuilder()
            while (value > 0) {
                builder.append(BASE62[value % radix])
                value /= radix
            }
            return builder.reverse().toString()
        }

        var result = payload
        for (index in (count - 1) downTo 0) {
            val word = keywords.getOrNull(index)
            if (word.isNullOrEmpty()) continue
            val token = Regex("\\b" + Regex.escape(encode(index)) + "\\b")
            result = result.replace(token, Regex.escapeReplacement(word))
        }
        return result
    }
                                    }
