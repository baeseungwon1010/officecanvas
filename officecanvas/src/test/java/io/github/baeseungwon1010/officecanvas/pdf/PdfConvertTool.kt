package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.ConvertFormat
import io.github.baeseungwon1010.officecanvas.PdfConverter
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Not a test: converts each PDF in -Pofficecanvas.input (a file or a directory)
 * to -Pofficecanvas.output/<name>.docx (or the -Pofficecanvas.format given, e.g.
 * hwpx), with the time each took in <output>/TIMES. Run with Android's graphics, as
 * on a device: drawings and CMYK JPEG are drawn with them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfConvertTool {

    @Test
    fun convert() {
        val input = System.getProperty("officecanvas.input").orEmpty()
        assumeTrue("set -Pofficecanvas.input=<pdf or dir> to convert", input.isNotEmpty())
        val out = File(System.getProperty("officecanvas.output").orEmpty().ifEmpty { "build/pdf-convert" }).apply { mkdirs() }
        val files = File(input).let { f -> if (f.isDirectory) f.listFiles().orEmpty().filter { it.name.endsWith(".pdf", true) }.sortedBy { it.name } else listOf(f) }
        val format = ConvertFormat.valueOf((System.getProperty("officecanvas.format") ?: "docx").uppercase())
        val times = StringBuilder()
        for (f in files) {
            val t0 = System.nanoTime()
            val status = try { "ok ${PdfConverter.convert(f, File(out, f.nameWithoutExtension + "." + format.name.lowercase()), format)}" } catch (e: Throwable) { e.toString() }
            times.append("${f.name}\t${(System.nanoTime() - t0) / 1_000_000}\t$status\n")
        }
        File(out, "TIMES").writeText(times.toString())
    }
}
