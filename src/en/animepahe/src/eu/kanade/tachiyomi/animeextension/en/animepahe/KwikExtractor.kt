package eu.kanade.tachiyomi.animeextension.en.animepahe

import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.get
import kotlinx.coroutines.CancellationException
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * kwik hides the stream behind a packed script on the embed page: the page carries an
 * eval(...) block whose payload decodes to the real m3u8/mp4 source. Unpack it here.
 */
object KwikExtractor {

    private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val PACKED = Regex("""\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)""")

    private val STREAM = Regex("""(https?://[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*)""")

    suspend fun videos(
        client: OkHttpClient,
        url: String,
        referer: String,
        label: String,
        userAgent: String,
    ): List<Video> {
        val headers = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", referer)
            .add("Accept", "*/*")
            .build()

        val html = try {
            val response = client.get(url.toHttpUrl(), headers)
            response.body.string()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }

        val unpacked = PACKED.find(html)?.let { match ->
            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: 62
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val keywords = match.groupValues[4].split("|")
            unpack(payload, radix, count, keywords)
        } ?: html

        val text = unpacked.replace("\\/", "/")
        val stream = STREAM.find(text)?.groupValues?.get(1) ?: return emptyList()
        return listOf(Video(stream, label.ifBlank { "kwik" }, headers = headers))
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
