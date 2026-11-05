package io.github.baeseungwon1010.officecanvas.pdf

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: writes the text read from each PDF in -Pofficecanvas.input (a file
 * or a directory) to -Pofficecanvas.output/<name>.txt, pages separated by
 * [PAGE_BREAK], and the time taken to <output>/TIMES, for comparing with another reader.
 */
class PdfTextTool {

    @Test
    fun extract() {
        val input = System.getProperty("officecanvas.input").orEmpty()
        assumeTrue("set -Pofficecanvas.input=<pdf or dir> to extract text", input.isNotEmpty())
        val out = File(System.getProperty("officecanvas.output").orEmpty().ifEmpty { "build/pdf-text" }).apply { mkdirs() }
        val files = File(input).let { f -> if (f.isDirectory) f.listFiles().orEmpty().filter { it.name.endsWith(".pdf", true) }.sortedBy { it.name } else listOf(f) }
        val times = StringBuilder()
        for (f in files) {
            val t0 = System.nanoTime()
            val text = StringBuilder()
            var status = "ok"
            try {
                PdfDocument.open(f).use { doc ->
                    val reader = PdfPageReader(doc)
                    for (page in doc.pages) {
                        val content = reader.read(page)
                        for (c in content.chars) text.append(c.text)
                        text.append(PAGE_BREAK)
                    }
                }
            } catch (e: Throwable) {
                status = e.javaClass.simpleName + ": " + e.message
            }
            File(out, f.nameWithoutExtension + ".txt").writeText(text.toString())
            times.append("${f.name}\t${(System.nanoTime() - t0) / 1_000_000}\t$status\n")
        }
        File(out, "TIMES").writeText(times.toString())
    }

    private companion object {
        const val PAGE_BREAK = "\n<<<PAGE>>>\n"
    }
}
