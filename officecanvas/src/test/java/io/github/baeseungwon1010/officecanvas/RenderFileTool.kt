package io.github.baeseungwon1010.officecanvas

import io.github.baeseungwon1010.officecanvas.ppt.PptParser
import io.github.baeseungwon1010.officecanvas.pptx.PptxParser
import io.github.baeseungwon1010.officecanvas.slide.EMU_PER_POINT
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Not a test: renders an arbitrary document to PNGs for tools/compare.sh.
 * Skipped unless -Pofficecanvas.input=<file> is given. Writes ours_N.png (1-based)
 * and, for slide decks, ours_base.tsv (slide, text baseline in pt, line start)
 * into -Pofficecanvas.output (default build/officecanvas-render). When the input
 * is a directory, every document in it is rendered into a subdirectory named
 * after the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderFileTool {

    @Test
    fun render() {
        val input = System.getProperty("officecanvas.input").orEmpty()
        assumeTrue("set -Pofficecanvas.input=<file> to render a document", input.isNotEmpty())
        val out = File(System.getProperty("officecanvas.output").orEmpty().ifEmpty { "build/officecanvas-render" })
        val longSide = System.getProperty("officecanvas.longSide")?.toIntOrNull() ?: OfficeCanvas.DEFAULT_LONG_SIDE_PX
        val inputFile = File(input)
        if (inputFile.isDirectory) {
            inputFile.listFiles().orEmpty().filter { it.isFile && DocumentFormat.detect(it) != null }.sortedBy { it.name }.forEach {
                try {
                    renderOne(it, File(out, it.nameWithoutExtension), longSide)
                } catch (e: Exception) {
                    println("failed ${it.name}: $e")
                }
            }
        } else {
            renderOne(inputFile, out, longSide)
        }
    }

    private fun renderOne(file: File, out: File, longSide: Int) {
        out.mkdirs()
        // Slide width in points, to turn the renderer's pixel baselines into the
        // points compare_baselines.py measures LibreOffice's PDF in.
        val widthPt = when (DocumentFormat.detect(file)) {
            DocumentFormat.PPTX -> PptxParser.parse(file).widthEmu / EMU_PER_POINT
            DocumentFormat.PPT -> PptParser.parse(file).widthEmu / EMU_PER_POINT
            else -> null
        }
        val baselines = StringBuilder()
        val lines = mutableListOf<Pair<String, Float>>()
        SlideRenderer.debugBaselines = lines
        try {
            OfficeCanvas.render(RuntimeEnvironment.getApplication(), file, longSide) { index, bitmap ->
                File(out, "ours_${index + 1}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                if (widthPt != null) {
                    val ptPerPx = widthPt / bitmap.width
                    for ((start, y) in lines) {
                        baselines.append(index + 1).append('\t').append("%.2f".format(y * ptPerPx)).append('\t')
                            .append(start.replace('\t', ' ')).append('\n')
                    }
                }
                lines.clear()
                bitmap.recycle()
            }
        } finally {
            SlideRenderer.debugBaselines = null
        }
        if (baselines.isNotEmpty()) File(out, "ours_base.tsv").writeText(baselines.toString())
        println("rendered ${file.name} into ${out.absolutePath}")
    }
}
