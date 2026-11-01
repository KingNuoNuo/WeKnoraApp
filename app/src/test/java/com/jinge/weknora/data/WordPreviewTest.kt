package com.jinge.weknora.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class WordPreviewTest {
    private fun fixture(xml: String): File = File.createTempFile("word-preview", ".docx").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
    }
    @Test fun paragraphsAndTablesAreEscapedAndRendered() {
        val file = fixture("""<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:t>&lt;script&gt;中文正文</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>单元格</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""")
        try {
            val html = WordPreview.toHtml(file)
            assertTrue(html.contains("&lt;script&gt;中文正文"))
            assertTrue(html.contains("<table><tr><td>"))
            assertFalse(html.contains("<script>"))
        } finally { file.delete() }
    }
    @Test fun externalEntitiesAreRejected() {
        val file = fixture("""<!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]><document>&secret;</document>""")
        try { assertThrows(IllegalStateException::class.java) { WordPreview.toHtml(file) } }
        finally { file.delete() }
    }
}
