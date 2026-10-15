package io.github.baeseungwon1010.officecanvas

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: renders every document in the test app's external files `in/`
 * directory on a real device or emulator, into `out/<name>/ours_N.png`, with the
 * time each took to render (not counting saving the PNGs) in `out/TIMES`. Skipped when `in/` is empty. tools/device_render.sh
 * pushes the documents, runs this and pulls the pages back.
 *
 * Robolectric draws with the JVM's Skia; a device has its own fonts, text
 * shaping and memory limits, which only show here.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRenderTool {

    @Test
    fun render() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.getExternalFilesDir(null) ?: return
        val input = File(root, "in")
        val docs = input.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        assumeTrue("push documents into ${input.absolutePath}", docs.isNotEmpty())
        val longSide = InstrumentationRegistry.getArguments().getString("longSide")?.toIntOrNull() ?: OfficeCanvas.DEFAULT_LONG_SIDE_PX
        val out = File(root, "out").apply { deleteRecursively(); mkdirs() }
        val times = StringBuilder()
        for (doc in docs) {
            val dir = File(out, doc.nameWithoutExtension).apply { mkdirs() }
            val started = System.nanoTime()
            // Saving the PNGs takes longer than rendering; it isn't counted.
            var saving = 0L
            val result = try {
                val pages = OfficeCanvas.render(context, doc, longSide) { index, bitmap ->
                    val s = System.nanoTime()
                    File(dir, "ours_${index + 1}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    saving += System.nanoTime() - s
                }
                "$pages pages"
            } catch (e: OfficeCanvasException) {
                "failed: ${e.message}"
            }
            val ms = (System.nanoTime() - started - saving) / 1_000_000
            times.append(doc.name).append('\t').append(result).append('\t').append(ms).append(" ms\n")
            Log.i("DeviceRenderTool", "${doc.name}: $result in $ms ms")
        }
        File(out, "TIMES").writeText(times.toString())
    }
}
