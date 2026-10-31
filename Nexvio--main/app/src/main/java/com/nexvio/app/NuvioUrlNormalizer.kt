package com.nexvio.app

/**
 * URL character normalization kept free of Android/WebView dependencies so it can
 * be used by diagnostic formatting and plain JVM unit tests.
 */
internal object NuvioUrlNormalizer {
    fun normalize(raw: String): String {
        val input = raw.trim()
        val authorityStart = input.indexOf("://").let { if (it < 0) -1 else it + 3 }
        val authorityEnd = if (authorityStart < 0) -1 else input.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) input.length else it }
        val out = StringBuilder(input.length + 16)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val inAuthority = authorityStart >= 0 && i >= authorityStart && i < authorityEnd
            if (c == '%' && i + 2 < input.length &&
                input[i + 1].digitToIntOrNull(16) != null &&
                input[i + 2].digitToIntOrNull(16) != null
            ) {
                out.append(input, i, i + 3)
                i += 3
                continue
            }
            if (c.code > 127) {
                val cp = input.codePointAt(i)
                String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).forEach { b ->
                    out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", b.toInt() and 0xff))
                }
                i += Character.charCount(cp)
                continue
            }
            val keepBracket = inAuthority && (c == '[' || c == ']')
            if (!keepBracket && (
                c.isWhitespace() || c == '"' || c == '<' || c == '>' ||
                c == '^' || c == '`' || c == '{' || c == '}' ||
                c == '|' || c == '\\' ||
                (!inAuthority && (c == '[' || c == ']'))
            )) {
                out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", c.code))
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
