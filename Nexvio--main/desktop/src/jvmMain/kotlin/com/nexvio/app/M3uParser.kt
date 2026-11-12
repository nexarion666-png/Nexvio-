package com.nexvio.app

import java.net.URI

data class TvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val category: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val isFavorite: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap()
)

data class ParsedPlaylist(val channels: List<TvChannel>, val error: String? = null)

object M3uParser {
    private val attributeRegex = Regex("""([\\w-]+)\\s*=\\s*"([^"]*)"""")

    fun parse(text: String): ParsedPlaylist {
        if (text.isBlank()) return ParsedPlaylist(emptyList(), "The playlist is empty.")
        val lines = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.toList()
        if (lines.none { it.equals("#EXTM3U", true) }) {
            return ParsedPlaylist(emptyList(), "This file does not appear to be an M3U playlist.")
        }

        val channels = mutableListOf<TvChannel>()
        var pending: Pending? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:", true) -> {
                    val comma = findSeparatorComma(line)
                    val meta = if (comma >= 0) line.substring(0, comma) else line
                    val label = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    val attrs = attributeRegex.findAll(meta)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val duration = meta.substringAfter("#EXTINF:", "").substringBefore(' ').trim()
                    pending = Pending(
                        name = attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: label,
                        label = label,
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        category = attrs["group-title"]?.takeIf { it.isNotBlank() },
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                    ).takeIf { duration.isNotEmpty() }
                }
                line.startsWith("#") -> Unit
                pending != null -> {
                    val candidate = pending!!
                    val valid = try {
                        val uri = URI(line)
                        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                            !uri.host.isNullOrBlank()
                    } catch (_: Exception) { false }
                    if (valid && (candidate.name.isNotBlank() || candidate.label.isNotBlank())) {
                        val name = candidate.name.ifBlank { candidate.label }
                        channels += TvChannel(
                            id = "${candidate.tvgId.orEmpty()}|${name.lowercase()}|$line",
                            name = name,
                            streamUrl = line,
                            logoUrl = candidate.logo,
                            category = candidate.category,
                            tvgId = candidate.tvgId,
                            tvgName = candidate.tvgName
                        )
                    }
                    pending = null
                }
            }
        }
        return if (channels.isEmpty()) {
            ParsedPlaylist(emptyList(), "No valid channels with stream URLs were found.")
        } else {
            ParsedPlaylist(channels.distinctBy { it.id })
        }
    }

    private fun findSeparatorComma(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            if (line[i] == '"') quoted = !quoted
            else if (line[i] == ',' && !quoted) return i
        }
        return -1
    }

    private data class Pending(
        val name: String,
        val label: String,
        val logo: String?,
        val category: String?,
        val tvgId: String?,
        val tvgName: String?
    )
}
