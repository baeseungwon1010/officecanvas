package io.github.baeseungwon1010.officecanvas

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Renders randomly damaged copies of the sample documents and reports any that
 * hang (over 30 s), throw anything but [OfficeCanvasException], or take over 5 s,
 * keeping a copy of each under build/fuzz. Skipped unless -Pofficecanvas.fuzz=<n>
 * is given (n damaged copies per sample; -Pofficecanvas.fuzzSeed picks the seed).
 * Zip formats get one XML part damaged and are packed again, so the damage reaches
 * the parser rather than stopping at a checksum.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FuzzTool {
    private fun res(p: String) = File(javaClass.classLoader!!.getResource(p)!!.toURI())

    private fun mutate(bytes: ByteArray, rnd: Random, textual: Boolean): ByteArray {
        val b = bytes.copyOf()
        repeat(1 + rnd.nextInt(8)) {
            val i = rnd.nextInt(b.size)
            when (rnd.nextInt(4)) {
                0 -> b[i] = rnd.nextInt(256).toByte()
                1 -> b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
                2 -> if (textual) { val d = "0123456789-".random(rnd); b[i] = d.code.toByte() } else b[i] = 0xFF.toByte()
                else -> { val j = rnd.nextInt(b.size); val n = rnd.nextInt(1, 64).coerceAtMost(b.size - maxOf(i, j)); if (n > 0) System.arraycopy(bytes, j, b, i, n) }
            }
        }
        return b
    }

    @Test
    fun fuzz() {
        val runs = System.getProperty("officecanvas.fuzz").orEmpty().toIntOrNull()
        assumeTrue("set -Pofficecanvas.fuzz=<copies per sample> to fuzz", runs != null)
        val seeds = listOf("hwp_samples/sample.hwp", "hwp_samples/sample_hwpml.hwp", "hwpx_samples/sample.hwpx",
            "ppt_samples/sample_deck.ppt", "ppt_samples/feature_test.ppt", "pptx_samples/sample_deck.pptx", "pptx_samples/pptx_features.pptx", "docx_samples/sample.docx")
        val rnd = Random(System.getProperty("officecanvas.fuzzSeed")?.toIntOrNull() ?: 1)
        val keepDir = File("build/fuzz").apply { mkdirs() }
        val out = File.createTempFile("fuzz", ".bin").apply { deleteOnExit() }
        val app = RuntimeEnvironment.getApplication()
        for (seed in seeds) {
            val src = res(seed)
            // Once undamaged first, so loading fonts and classes isn't counted as slow.
            OfficeCanvas.render(app, src, 200) { _, b -> b.recycle() }
            var worst = 0L
            repeat(runs!!) { k ->
                if (src.extension == "hwpx" || src.extension == "pptx" || src.extension == "docx") {
                    ZipFile(src).use { z ->
                        val entries = z.entries().toList()
                        val target = entries.filter { it.name.endsWith(".xml") || it.name.endsWith(".hpf") }.random(rnd)
                        ZipOutputStream(out.outputStream()).use { zo ->
                            for (e in entries) {
                                zo.putNextEntry(ZipEntry(e.name))
                                val data = z.getInputStream(e).readBytes()
                                zo.write(if (e == target) mutate(data, rnd, true) else data)
                                zo.closeEntry()
                            }
                        }
                    }
                } else out.writeBytes(mutate(src.readBytes(), rnd, src.name.contains("hwpml")))
                var problem: Throwable? = null
                val t = Thread(null, {
                    try { OfficeCanvas.render(app, out, 200) { _, b -> b.recycle() } }
                    catch (e: OfficeCanvasException) { }
                    catch (e: Throwable) { problem = e }
                }, "fuzz", 1L shl 20)
                val t0 = System.nanoTime()
                t.start(); t.join(30_000)
                val ms = (System.nanoTime() - t0) / 1_000_000
                worst = maxOf(worst, ms)
                if (t.isAlive) {
                    val keep = File(keepDir, "hang-$k-${src.name}"); out.copyTo(keep, true)
                    println("FUZZ HANG $seed #$k -> $keep"); t.interrupt(); return
                }
                if (problem != null) {
                    val keep = File(keepDir, "error-$k-${src.name}"); out.copyTo(keep, true)
                    println("FUZZ ERROR $seed #$k $problem -> $keep")
                }
                if (ms > 5000) { val keep = File(keepDir, "slow-$k-${src.name}"); out.copyTo(keep, true); println("FUZZ SLOW $seed #$k ${ms}ms -> $keep") }
            }
            println("FUZZ $seed: $runs copies, slowest ${worst}ms")
        }
    }
}
