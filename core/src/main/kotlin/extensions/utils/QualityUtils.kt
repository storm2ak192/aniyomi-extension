package extensions.utils

/** Pure helpers for the preferred-quality and site-domain settings. No Android types. */
object QualityUtils {
    const val BEST = "best"

    private val P_SUFFIX = Regex("""(\d{3,4})\s*[pP]\b""")
    private val DIMENSIONS = Regex("""\d{3,4}\s*[xX×]\s*(\d{3,4})""")
    private val BARE = Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240)(?!\d)""")

    /** Video height in pixels from a label such as "1080p", "1920x1080", "FHD" or "HD". Null when unknown. */
    fun heightOf(label: String?): Int? {
        val text = label?.trim().orEmpty()
        if (text.isEmpty()) return null
        P_SUFFIX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        DIMENSIONS.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        BARE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val upper = text.uppercase()
        return when {
            "4K" in upper || "UHD" in upper -> 2160
            Regex("""\bFHD\b""").containsMatchIn(upper) || "FULL HD" in upper -> 1080
            Regex("""\bHD\b""").containsMatchIn(upper) -> 720
            Regex("""\bSD\b""").containsMatchIn(upper) -> 480
            else -> null
        }
    }

    /**
     * Preferred quality first, then the closest lower ones, then higher ones.
     * "best" (or anything not a number) sorts high to low. Unknown height counts as 0.
     */
    fun <T> sortByQuality(items: List<T>, preferred: String, height: (T) -> Int?): List<T> {
        val wanted = preferred.toIntOrNull()
        return if (wanted == null) {
            items.sortedByDescending { height(it) ?: 0 }
        } else {
            items.sortedWith(
                compareBy<T> { (height(it) ?: 0) > wanted }
                    .thenByDescending { height(it) ?: 0 },
            )
        }
    }

    /** A valid http(s) origin without a trailing slash, or [default] when the input is not usable. */
    fun normalizeDomain(input: String?, default: String): String {
        val value = input?.trim()?.trimEnd('/').orEmpty()
        val ok = (value.startsWith("https://") || value.startsWith("http://")) &&
            value.substringAfter("://").isNotEmpty() && ' ' !in value
        return if (ok) value else default
    }

    /** Replaces the scheme and host of [url] with those of [base], keeping path, query and fragment. */
    fun rebase(url: String, base: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val afterHost = url.indexOf('/', schemeEnd + 3)
        val rest = if (afterHost < 0) "" else url.substring(afterHost)
        return base.trimEnd('/') + rest
    }
}
