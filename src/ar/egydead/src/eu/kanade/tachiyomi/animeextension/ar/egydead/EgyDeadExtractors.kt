package eu.kanade.tachiyomi.animeextension.ar.egydead

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Resolves EgyDead watch links down to playable streams.
 *
 * The site's server list points at a MegaMax aggregator iframe (host rotates, currently
 * q7v3k8m2p1.top). That page is an InertiaJS app; its mirror list loads as a partial
 * reload of the "streams" prop, which returns per-quality mirror links to known hosts
 * (doodstream, mixdrop, streamwish/streamhg, uqload, vidmoly, vidara, byse, streamruby).
 */
object EgyDeadExtractors {

    private val PACKED = Regex("""\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)""")

    private val STREAM = Regex("""(https?://[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*)""")

    suspend fun videosFromHoster(
        client: OkHttpClient,
        hoster: Hoster,
        referer: String,
        userAgent: String,
        json: Json,
    ): List<Video> {
        val url = hoster.hosterUrl
        return when {
            "/iframe/" in url -> megamax(client, url, referer, userAgent, json)
            else -> fromMirror(client, url, hoster.hosterName, url, userAgent)
        }
    }

    // ============================ MegaMax iframe ==========================

    private suspend fun megamax(
        client: OkHttpClient,
        iframeUrl: String,
        referer: String,
        userAgent: String,
        json: Json,
    ): List<Video> {
        val pageHeaders = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", referer)
            .build()

        val html = try {
            client.get(iframeUrl.toHttpUrl(), pageHeaders).body.string()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }

        val pageJson = findInertiaPage(html) ?: return emptyList()
        val page = try {
            json.parseToJsonElement(pageJson).jsonObject
        } catch (_: Throwable) {
            return emptyList()
        }
        val version = page["version"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val component = page["component"]?.jsonPrimitive?.contentOrNull ?: "files/mirror/video"

        val inertiaHeaders = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", referer)
            .add("X-Inertia", "true")
            .add("X-Inertia-Partial-Data", "streams")
            .add("X-Inertia-Partial-Component", component)
            .add("X-Inertia-Version", version)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Accept", "text/html, application/xhtml+xml")
            .build()

        val streamsBody = try {
            client.get(iframeUrl.toHttpUrl(), inertiaHeaders).body.string()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }

        val data = try {
            json.parseToJsonElement(streamsBody).jsonObject
                .getValue("props").jsonObject
                .getValue("streams").jsonObject
                .getValue("data").jsonArray
        } catch (_: Throwable) {
            return emptyList()
        }

        val videos = mutableListOf<Video>()
        for (quality in data) {
            val qualityObj = quality.jsonObject
            val label = qualityObj["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
            for (mirror in qualityObj["mirrors"]?.jsonArray.orEmpty()) {
                val mirrorObj = mirror.jsonObject
                val driver = mirrorObj["driver"]?.jsonPrimitive?.contentOrNull.orEmpty()
                var link = mirrorObj["link"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (link.startsWith("//")) link = "https:$link"
                if (link.isBlank()) continue
                videos += fromMirror(client, link, "$driver $label", iframeUrl, userAgent)
            }
        }
        return videos.distinctBy { it.videoUrl }
    }

    private fun findInertiaPage(html: String): String? {
        // The page JSON starts {"component":"files/mirror/video" but the site escapes
        // slashes (files\/mirror\/video), so anchor on the part before the first slash.
        val compIdx = html.indexOf("\"component\":\"files")
        if (compIdx < 0) return null
        val start = html.lastIndexOf('{', compIdx)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1).replace("\\/", "/")
                }
            }
        }
        return null
    }

    // ============================ Mirror hosts ============================

    private suspend fun fromMirror(
        client: OkHttpClient,
        url: String,
        label: String,
        referer: String,
        userAgent: String,
    ): List<Video> {
        if (url.isBlank()) return emptyList()
        return try {
            when {
                DOOD_REGEX.containsMatchIn(url) -> dood(client, url, label, userAgent)
                "mixdrop" in url -> packed(client, url, label, userAgent, MIXDROP_STREAM)
                // uqload and the rest are packed pages; the generic unpacker handles them.
                else -> packed(client, url, label, userAgent, STREAM)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
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

    // streamwish / streamhg / vidmoly / vidara / byse / streamruby / mixdrop:
    // the embed page carries a packed script that decodes to the stream URL.
    private suspend fun packed(
        client: OkHttpClient,
        url: String,
        label: String,
        userAgent: String,
        streamRegex: Regex,
    ): List<Video> {
        val host = "https://" + url.toHttpUrl().host
        val html = fetch(client, url, userAgent, host) ?: return emptyList()
        val unpacked = unpackFirst(html) ?: html
        val text = unpacked.replace("\\/", "/")
        val stream = streamRegex.find(text)?.let { match ->
            if (match.groupValues.size > 1) match.groupValues[1] else match.value
        } ?: PROTO_STREAM.find(text)?.value ?: return emptyList()
        val fixed = if (stream.startsWith("//")) "https:$stream" else stream
        return listOf(Video(fixed, label.ifBlank { host }, headers = pageHeaders(userAgent, url)))
    }

    private val MIXDROP_STREAM = Regex("""MDCore\.wurl\s*=\s*"([^"]+)""")

    private val PROTO_STREAM = Regex("""(//[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*)""")

    private suspend fun uqload(client: OkHttpClient, url: String, label: String, userAgent: String): List<Video> {
        val fixed = url.replace("https://uqload.co/", "https://www.uqload.co/")
        val html = fetch(client, fixed, userAgent, fixed) ?: return emptyList()
        val stream = html.substringAfter("sources: [\"", "")
            .substringBefore("\"")
            .ifBlank { null } ?: STREAM.find(html.replace("\\/", "/"))?.value ?: return emptyList()
        return listOf(Video(stream, label.ifBlank { "uqload" }, headers = pageHeaders(userAgent, fixed)))
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
        return listOf(Video(stream, label.ifBlank { "doodstream" }, headers = pageHeaders(userAgent, host)))
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
