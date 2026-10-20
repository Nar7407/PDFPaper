package com.pdfpaper.engine.links

import com.pdfpaper.engine.io.FileSource
import com.pdfpaper.engine.model.PdfDocument
import com.pdfpaper.engine.page.pages
import com.pdfpaper.engine.text.layout
import org.junit.Test
import java.io.File

class SampleDebugTest {
    @Test
    fun debugSample() {
        val path = "C:/Users/hp/AppData/Local/Temp/opencode/t61-academic-paper.pdf"
        val doc = PdfDocument.open(FileSource(File(path))).getOrThrow()
        println("pages=" + doc.pages().size)
        for (p in doc.pages()) println("PAGE ${p.index + 1}: " + p.layout().text.replace("\n", " | "))
        val index = LinkResolver(doc).resolve()
        for (page in 0 until doc.pages().size) {
            for (link in index.linksOnPage(page)) {
                println("L${page + 1} ${link.kind} '${link.text}' rect=${link.rect.left},${link.rect.bottom},${link.rect.right},${link.rect.top} targets=${link.targets.size} uri=${link.uri} file=${link.file} note=${link.note}")
            }
        }
    }
}
