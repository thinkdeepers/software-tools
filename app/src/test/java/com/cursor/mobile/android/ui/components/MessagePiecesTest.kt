package com.cursor.mobile.android.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePiecesTest {
    @Test
    fun markdownAndSvgArePicturesNotSource() {
        val raw = """
            前文
            ![示意图](https://example.com/plot.svg)
            <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12"><rect width="12" height="12"/></svg>
            后文
        """.trimIndent()
        val blocks = splitMessageBlocks(raw) { it.trim() }
        val words = blocks.filterIsInstance<MessagePiece.Words>().joinToString("\n") { it.text }
        assertTrue(blocks.any { it is MessagePiece.Picture })
        assertFalse(words.contains("<svg"))
        assertFalse(words.contains("!["))
        assertTrue(words.contains("前文"))
        assertTrue(words.contains("后文"))
    }

    @Test
    fun markdownFileFenceAndDataImageStayOutOfText() {
        val raw = "```md\n![图](data:image/png;base64,aaaa)\n```\n普通句子"
        val blocks = splitMessageBlocks(raw) { it.trim() }
        val words = blocks.filterIsInstance<MessagePiece.Words>().joinToString("\n") { it.text }
        assertTrue(blocks.any { (it as? MessagePiece.Picture)?.pic is PiecePic.Bytes })
        assertFalse(words.contains("data:image"))
        assertFalse(words.contains("```"))
        assertTrue(words.contains("普通句子"))
    }

    @Test
    fun hugeSvgDoesNotThrowOrRemainInText() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">" + "M".repeat(180_000) + "</svg>"
        val blocks = splitMessageBlocks("开头${svg}结尾") { it }
        val words = blocks.filterIsInstance<MessagePiece.Words>().joinToString("\n") { it.text }
        assertTrue(blocks.any { (it as? MessagePiece.Picture)?.pic is PiecePic.Svg })
        assertFalse(words.contains("<svg"))
        assertTrue(words.contains("开头"))
        assertTrue(words.contains("结尾"))
        assertTrue(words.length < 100)
    }
}
