package com.jinge.weknora.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PowerPointPreviewTest {
    @Test fun rendersSlidesInNumericOrderAndEscapesText() {
        val file = File.createTempFile("slides", ".pptx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                for (number in listOf(10, 2, 1)) {
                    zip.putNextEntry(ZipEntry("ppt/slides/slide$number.xml"))
                    zip.write("<s xmlns:a='urn:test'><a:t>PAGE_$number &amp; &lt;ok&gt;</a:t></s>".toByteArray())
                    zip.closeEntry()
                }
            }
            val html = PowerPointPreview.toHtml(file)
            assertTrue(html.indexOf("PAGE_1 ") < html.indexOf("PAGE_2 "))
            assertTrue(html.indexOf("PAGE_2 ") < html.indexOf("PAGE_10 "))
            assertTrue(html.contains("&amp; &lt;ok&gt;"))
        } finally { file.delete() }
    }
}
