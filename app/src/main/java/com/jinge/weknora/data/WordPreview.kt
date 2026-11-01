package com.jinge.weknora.data

import org.w3c.dom.Node
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

/** Offline DOCX reading: paragraphs, lists, tables and embedded images; no active content. */
object WordPreview {
    private fun limited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
    fun toHtml(file: File): String = ZipFile(file).use { zip ->
        fun xml(path: String): org.w3c.dom.Document {
            val entry = zip.getEntry(path) ?: error("不是有效的 DOCX 文档")
            val data = zip.getInputStream(entry).use { limited(it, 8 * 1024 * 1024) }
            check(data.size <= 8 * 1024 * 1024) { "Word 文档结构过大" }
            check(!String(data, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) { "不支持包含外部实体的文档" }
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            return builder.parse(data.inputStream())
        }
        val relationships = mutableMapOf<String, String>()
        if (zip.getEntry("word/_rels/document.xml.rels") != null) {
            val rels = xml("word/_rels/document.xml.rels").getElementsByTagName("Relationship")
            for (i in 0 until rels.length) {
                val node = rels.item(i)
                if (node.attributes.getNamedItem("TargetMode")?.nodeValue == "External") continue
                val target = node.attributes.getNamedItem("Target")?.nodeValue.orEmpty()
                if (!target.contains("..") && !target.contains(":") && !target.startsWith("/"))
                    relationships[node.attributes.getNamedItem("Id").nodeValue] = "word/$target"
            }
        }
        var imageBytes = 0
        fun render(node: Node): String {
            val tag = node.localName ?: node.nodeName.substringAfter(':')
            if (tag == "t") return escape(node.textContent)
            if (tag == "br") return "<br>"
            if (tag == "tab") return "&emsp;"
            if (tag == "blip") {
                val id = node.attributes?.getNamedItem("r:embed")?.nodeValue ?: return ""
                val path = relationships[id] ?: return ""
                val entry = zip.getEntry(path) ?: return ""
                val extension = path.substringAfterLast('.').lowercase()
                val mime = when (extension) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; else -> return "<p>（此图片格式暂不支持）</p>" }
                val bytes = zip.getInputStream(entry).use { limited(it, 5 * 1024 * 1024) }
                imageBytes += bytes.size
                if (bytes.size > 5 * 1024 * 1024 || imageBytes > 30 * 1024 * 1024) return "<p>（图片过大，已省略）</p>"
                return "<img alt=\"文档插图\" src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}\">"
            }
            val body = buildString { for (i in 0 until node.childNodes.length) append(render(node.childNodes.item(i))) }
            return when (tag) {
                "p" -> "<p>$body</p>"
                "tbl" -> "<table>$body</table>"
                "tr" -> "<tr>$body</tr>"
                "tc" -> "<td>$body</td>"
                else -> body
            }
        }
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:18px/1.7 sans-serif;padding:16px;color:#202522;background:#faf9f6;overflow-wrap:anywhere}p{white-space:pre-wrap;margin:0 0 12px}table{border-collapse:collapse;max-width:100%;display:block;overflow:auto}td{border:1px solid #bbb;padding:8px;min-width:60px}img{max-width:100%;height:auto}</style></head><body>${render(xml("word/document.xml").documentElement)}</body></html>"""
    }
}
