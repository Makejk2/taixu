package top.wkbin.taixu.ui.chat

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipInputStream

/**
 * DOCX（OOXML）正文文本抽取：定位 word/document.xml 后流式解析，
 * 提取段落文本（w:t）、换行（w:br/w:cr）、制表（w:tab），段落间换行。
 *
 * 用途：附件挂载时为 .docx 生成同名 .txt，Agent 用 read 即可读正文——
 * 否则二进制 OOXML 直接 read 全是乱码。解析失败返回 null（调用方按普通文件挂载）。
 */
object DocxTextExtractor {

    const val MAX_TEXT_CHARS = 512 * 1024

    /** 从 docx 文件抽取正文文本；无 document.xml 或解析失败返回 null。 */
    fun extract(file: File): String? = runCatching {
        var result: String? = null
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && result == null) {
                if (entry.name == "word/document.xml") {
                    result = parseDocumentXml(zip)
                }
                entry = zip.nextEntry
            }
        }
        result?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 把抽取文本写到 docx 旁的同名 .txt（<原名>.txt）；失败返回 null。 */
    fun writeSidecarText(docx: File): File? {
        val text = extract(docx) ?: return null
        val sidecar = File(docx.parentFile, docx.name + ".txt")
        return runCatching {
            sidecar.writeText(text)
            sidecar
        }.getOrNull()
    }

    private fun parseDocumentXml(stream: java.io.InputStream): String? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)
        val paragraphs = StringBuilder()
        val current = StringBuilder()
        var inParagraph = false
        var truncated = false

        fun flushParagraph() {
            if (inParagraph && current.isNotEmpty()) {
                if (paragraphs.isNotEmpty()) paragraphs.append('\n')
                paragraphs.append(current)
                if (paragraphs.length >= MAX_TEXT_CHARS) {
                    paragraphs.append("\n[文本超长已截断]")
                    truncated = true
                }
            }
            current.clear()
        }

        while (truncated.not()) {
            val event = parser.next()
            // document.xml 的标签多带 w: 前缀；关闭命名空间处理后取本地名匹配
            fun localName(): String = parser.name.substringAfterLast(':')
            when (event) {
                XmlPullParser.START_TAG -> when (localName()) {
                    "p" -> { inParagraph = true; current.clear() }
                    "br", "cr" -> current.append('\n')
                    "tab" -> current.append('\t')
                }
                XmlPullParser.TEXT -> if (inParagraph) current.append(parser.text)
                XmlPullParser.END_TAG -> when (localName()) {
                    "p" -> { flushParagraph(); inParagraph = false }
                    "document" -> break
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        flushParagraph()
        return paragraphs.toString().trim().takeIf { it.isNotEmpty() }
    }
}
