package io.github.baeseungwon1010.officecanvas.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

/** The family names a converted document asks for, from the names PDFs give their fonts. */
class PdfFontNamesTest {

    @Test
    fun `names become the families documents know`() {
        for ((base, family) in listOf(
            "ABCDEF+TimesNewRomanPSMT" to "Times New Roman",
            "ArialMT" to "Arial",
            "ABCDEF+NimbusRomNo9L-Regu" to "Times New Roman",
            "ABCDEF+MalgunGothic-Bold" to "맑은 고딕",
            // Hangul's PDF export names the CMap after the font.
            "맑은고딕-WinCharSetFFFF-H" to "맑은 고딕",
            "ABCDEF+DejaVuSansMono-Bold" to "DejaVu Sans Mono",
            "DejaVuSans" to "DejaVu Sans",
            "ABCDEF+LinLibertineTB" to "Linux Libertine G",
            "ABCDEF+NotoSansCJKjp-Regular" to "Noto Sans CJK JP",
            "ABCDEF+SourceCodePro-Regular" to "Source Code Pro",
            "ABCDEF+ComicSansMS" to "Comic Sans MS",
            // Numbers, not names: the document's own font.
            "TT153F42o00" to "",
            "F4" to "",
            "C2_0" to ""
        )) assertEquals(base, family, PdfFont.familyOf(base))
    }
}
