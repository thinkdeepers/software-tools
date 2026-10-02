package com.cursor.mobile.android.ui.components

internal sealed class MessagePiece {
    data class Words(val text: String) : MessagePiece()
    data class Picture(val pic: PiecePic) : MessagePiece()
}

internal sealed class PiecePic {
    data class Remote(val url: String) : PiecePic()
    data class Svg(val markup: String) : PiecePic()
    data class Bytes(val base64: String, val mime: String) : PiecePic()
}

internal fun splitMessageBlocks(raw: String, clean: (String) -> String): List<MessagePiece> {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
    val out = mutableListOf<MessagePiece>()
    var index = 0
    while (index < text.length) {
        val hit = nextPicture(text, index) ?: break
        appendWords(out, clean(text.substring(index, hit.start)))
        out += hit.pieces
        index = hit.end
    }
    appendWords(out, clean(text.substring(index)))
    return out
}

private data class Hit(val start: Int, val end: Int, val pieces: List<MessagePiece>)

private fun nextPicture(text: String, from: Int): Hit? {
    val candidates = listOfNotNull(
        findSvg(text, from),
        findImg(text, from),
        findImageFiles(text, from),
        findMarkdownImage(text, from),
        findFence(text, from)
    )
    return candidates.minByOrNull { it.start }
}

private fun findSvg(text: String, from: Int): Hit? {
    var cursor = from
    while (cursor < text.length) {
        val start = text.indexOf("<svg", cursor, ignoreCase = true)
        if (start < 0) return null
        val next = text.getOrNull(start + 4)
        if (next != null && next != '>' && !next.isWhitespace() && next != '/') {
            cursor = start + 4
            continue
        }
        val close = text.indexOf("</svg>", start, ignoreCase = true)
        val end = if (close < 0) (start + 120_000).coerceAtMost(text.length) else close + "</svg>".length
        return Hit(start, end, listOf(MessagePiece.Picture(PiecePic.Svg(text.substring(start, end)))))
    }
    return null
}

private fun findImg(text: String, from: Int): Hit? {
    val start = text.indexOf("<img", from, ignoreCase = true)
    if (start < 0) return null
    val end = text.indexOf('>', start).let { if (it < 0) (start + 4_000).coerceAtMost(text.length) else it + 1 }
    val token = text.substring(start, end)
    val src = Regex("(?i)\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]").find(token)?.groupValues?.get(1).orEmpty()
    val pic = if (src.isBlank()) null else pieceForUrl(src)
    return Hit(start, end, listOfNotNull(pic?.let { MessagePiece.Picture(it) }))
}

private fun findImageFiles(text: String, from: Int): Hit? {
    val start = text.indexOf("<image_files", from, ignoreCase = true)
    if (start < 0) return null
    val close = text.indexOf("</image_files>", start, ignoreCase = true)
    val end = if (close < 0) (start + 80_000).coerceAtMost(text.length) else close + "</image_files>".length
    val body = text.substring(start, end)
        .replace(Regex("(?is)^<image_files\\b[^>]*>"), "")
        .replace(Regex("(?is)</image_files>$"), "")
    return Hit(start, end, picturesInside(body))
}

private fun findMarkdownImage(text: String, from: Int): Hit? {
    var cursor = from
    while (cursor < text.length) {
        val start = text.indexOf("![", cursor)
        if (start < 0) return null
        val open = text.indexOf("](", start + 2)
        val close = if (open < 0) -1 else text.indexOf(')', open + 2)
        if (open < 0 || open - start > 500 || close < 0 || close - open > 1_500_000) {
            cursor = start + 2
            continue
        }
        val url = text.substring(open + 2, close).trim().trim('"').substringBefore(' ').substringBefore('"')
        if (url.isBlank()) {
            cursor = start + 2
            continue
        }
        return Hit(start, close + 1, listOf(MessagePiece.Picture(pieceForUrl(url))))
    }
    return null
}

private fun findFence(text: String, from: Int): Hit? {
    val start = text.indexOf("```", from)
    if (start < 0) return null
    val headerEnd = text.indexOf('\n', start + 3).let { if (it < 0) start + 3 else it }
    val lang = text.substring(start + 3, headerEnd).trim().lowercase()
    val close = text.indexOf("```", headerEnd + 1)
    if (close < 0) return null
    val body = text.substring(headerEnd, close)
    val visual = lang == "svg" || lang == "xml" || lang == "html" || lang == "md" || lang == "markdown" ||
        body.contains("<svg", true) || body.contains("![")
    if (!visual) return null
    val inner = picturesInside(body)
    if (inner.isEmpty() && !body.contains("<svg", true)) return null
    val pieces = if (inner.isEmpty()) listOf(MessagePiece.Picture(PiecePic.Svg(body.trim()))) else inner
    return Hit(start, close + 3, pieces)
}

private fun picturesInside(body: String): List<MessagePiece> {
    val nested = splitMessageBlocks(body) { "" }.filterIsInstance<MessagePiece.Picture>()
    if (nested.isNotEmpty()) return nested
    return emptyList()
}

private fun pieceForUrl(raw: String): PiecePic {
    val url = raw.trim()
    if (url.startsWith("data:image/svg", true)) {
        val payload = url.substringAfter(",", "")
        val markup = if (url.contains(";base64", true)) decode64(payload) else java.net.URLDecoder.decode(payload, Charsets.UTF_8.name())
        return PiecePic.Svg(markup)
    }
    if (url.startsWith("data:image/", true)) {
        val mime = url.substringAfter("data:").substringBefore(";").ifBlank { "image/png" }
        return PiecePic.Bytes(url.substringAfter(",", ""), mime)
    }
    return PiecePic.Remote(url)
}

private fun decode64(payload: String): String = try {
    String(java.util.Base64.getMimeDecoder().decode(payload), Charsets.UTF_8)
} catch (_: Exception) {
    ""
}

private fun appendWords(out: MutableList<MessagePiece>, text: String) {
    val trimmed = text.trim()
    if (trimmed.isNotBlank()) out += MessagePiece.Words(trimmed)
}
