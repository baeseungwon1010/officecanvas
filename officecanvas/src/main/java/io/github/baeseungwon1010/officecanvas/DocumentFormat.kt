package io.github.baeseungwon1010.officecanvas

import io.github.baeseungwon1010.officecanvas.ole.Ole2Reader
import java.io.File
import java.util.zip.ZipFile

/** The document types OfficeCanvas can render. */
enum class DocumentFormat {
    /** PowerPoint 2007+ (.pptx): a zip of XML parts. */
    PPTX,

    /** PowerPoint 97-2003 (.ppt): binary records in an OLE2 compound file. */
    PPT,

    /** Hangul (OWPML, .hwpx): a zip of XML parts with the layout Hangul saved. */
    HWPX;

    companion object {
        /**
         * Detects the format from the file's contents, not its name, so a mislabeled
         * or extension-less file still works. Returns null for anything else,
         * including the formats these containers are easily confused with (a .doc or
         * HWP 5.0 file is also OLE2; a .docx or .xlsx is also a zip).
         */
        @JvmStatic
        fun detect(file: File): DocumentFormat? {
            val head = ByteArray(8)
            val read = file.inputStream().use { it.read(head) }
            if (read < 4) return null
            return when {
                head.startsWith(ZIP_MAGIC) -> detectZip(file)
                read == 8 && head.startsWith(OLE2_MAGIC) -> detectOle2(file)
                else -> null
            }
        }

        private fun detectZip(file: File): DocumentFormat? = try {
            ZipFile(file).use { zip ->
                when {
                    zip.getEntry("ppt/presentation.xml") != null -> PPTX
                    zip.getEntry("Contents/content.hpf") != null -> HWPX
                    else -> null
                }
            }
        } catch (e: java.io.IOException) {
            null
        }

        private fun detectOle2(file: File): DocumentFormat? = try {
            if (Ole2Reader.open(file).readStream("PowerPoint Document") != null) PPT else null
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }

        private fun ByteArray.startsWith(prefix: ByteArray) = prefix.indices.all { this[it] == prefix[it] }

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val OLE2_MAGIC = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )
    }
}
