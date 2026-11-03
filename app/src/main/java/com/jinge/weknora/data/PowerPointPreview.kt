package com.jinge.weknora.data

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Lightweight offline PPTX text preview. Slides are rendered as stacked pages in WebView. */
object PowerPointPreview {
    fun toHtml(file: File): String {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val body = ZipFile(file).use { zip ->
            val pattern = Regex("ppt/slides/slide\\d+\\.xml")
            val slides = zip.entries().asSequence().filter { it.name.matches(pattern) }
                .sortedBy { it.name.substringAfterLast('/').removePrefix("slide").substringBefore('.').toInt() }.toList()
            val builder = factory.newDocumentBuilder()
            buildString {
                slides.forEachIndexed { index, entry ->
                    // Process one DOM at a time, rather than retaining every slide's XML tree.
                    val doc = zip.getInputStream(entry).use { input -> builder.parse(input) }
                    append("<section class='slide'><div class='num'>${index + 1} / ${slides.size}</div>")
                    val texts = doc.getElementsByTagNameNS("*", "t")
                    for (i in 0 until texts.length) {
                        val value = texts.item(i).textContent
                        if (value.isNotBlank()) append("<p>${esc(value)}</p>")
                    }
                    append("</section>")
                }
            }
        }
        return """<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0;background:#f1f3f5;color:#17201e;font-family:sans-serif}.slide{box-sizing:border-box;min-height:360px;margin:14px 12px;padding:30px 24px;background:white;border-radius:8px;box-shadow:0 2px 8px #0002}.slide p{font-size:20px;line-height:1.55;margin:12px 0}.num{color:#6b7774;font-size:12px;text-align:right}</style></head><body>$body</body></html>"""
    }
}
