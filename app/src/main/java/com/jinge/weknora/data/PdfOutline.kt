package com.jinge.weknora.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

data class PdfBookmark(val title: String, val page: Int?, val depth: Int)

object PdfOutline {
    private val outlines = android.util.LruCache<String, List<PdfBookmark>>(8)
    fun read(context: Context, file: File): List<PdfBookmark> {
        val key = "${file.canonicalPath}:${file.length()}:${file.lastModified()}"
        outlines.get(key)?.let { return it }
        PDFBoxResourceLoader.init(context.applicationContext)
        val outline = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { document ->
            val result = mutableListOf<PdfBookmark>()
            val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            fun visit(first: PDOutlineItem?, depth: Int) {
                if (depth > 12) return
                var node = first
                while (node != null && result.size < 3000 && visited.add(node.cosObject)) {
                    val current = node
                    val page = runCatching { current.findDestinationPage(document)?.let { document.pages.indexOf(it) } }
                        .getOrNull()?.takeIf { it in 0 until document.numberOfPages }
                    result += PdfBookmark(current.title.orEmpty().ifBlank { "未命名章节" }, page, depth)
                    visit(current.firstChild, depth + 1)
                    node = current.nextSibling
                }
            }
            visit(document.documentCatalog.documentOutline?.firstChild, 0)
            result
        }
        outlines.put(key, outline)
        return outline
    }
}
