package com.jinge.weknora.data

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Lightweight offline PPTX text preview. Slides are rendered as stacked pages in WebView. */
object PowerPointPreview {
    fun toHtml(file: File): String {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val slides = ZipFile(file).use { zip -> zip.entries().asSequence()
            .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.name.substringAfter("slide").substringBefore('.').toInt() }
            .map { entry -> zip.getInputStream(entry).use { input -> factory.newDocumentBuilder().parse(input) } }
            .toList() }
        fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val body = slides.mapIndexed { index, doc ->
            val texts = doc.getElementsByTagNameNS("*", "t").let { nodes -> (0 until nodes.length).map { nodes.item(it).textContent }.filter { it.isNotBlank() } }
            "<section class='slide'><div class='num'>${index + 1} / ${slides.size}</div>${texts.joinToString("") { "<p>${esc(it)}</p>" }}</section>"
        }.joinToString("")
        return """<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0;background:#f1f3f5;color:#17201e;font-family:sans-serif}.slide{box-sizing:border-box;min-height:360px;margin:14px 12px;padding:30px 24px;background:white;border-radius:8px;box-shadow:0 2px 8px #0002}.slide p{font-size:20px;line-height:1.55;margin:12px 0}.num{color:#6b7774;font-size:12px;text-align:right}</style></head><body>$body</body></html>"""
    }
}
