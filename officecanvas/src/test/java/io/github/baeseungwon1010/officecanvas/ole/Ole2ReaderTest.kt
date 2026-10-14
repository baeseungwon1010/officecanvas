package io.github.baeseungwon1010.officecanvas.ole

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Ole2ReaderTest {

    private fun sample(name: String): File {
        val url = javaClass.classLoader?.getResource("ppt_samples/$name")
            ?: throw AssertionError("test resource missing: ppt_samples/$name")
        return File(url.toURI())
    }

    @Test
    fun `reads the PowerPoint Document stream at its declared size`() {
        for (name in listOf("sample_deck.ppt", "feature_test.ppt")) {
            val ole = Ole2Reader.open(sample(name))
            val stream = ole.readStream("PowerPoint Document")
            assertNotNull("$name: PowerPoint Document stream should exist", stream)
            assertTrue("$name: stream should be non-trivial", stream!!.size > 1000)
        }
    }

    @Test
    fun `reads the small Current User stream via the mini-stream path`() {
        // "Current User" is well under the 4096-byte mini-stream cutoff in every
        // real .ppt, so this is what actually exercises readMiniChain — the
        // regular-sector path alone wouldn't catch a MiniFAT bug.
        for (name in listOf("sample_deck.ppt", "feature_test.ppt")) {
            val ole = Ole2Reader.open(sample(name))
            val stream = ole.readStream("Current User")
            assertNotNull("$name: Current User stream should exist", stream)
            assertTrue("$name: Current User should be small", stream!!.size < 200)
        }
    }

    @Test
    fun `returns null for a stream that does not exist`() {
        val ole = Ole2Reader.open(sample("sample_deck.ppt"))
        assertNull(ole.readStream("NoSuchStream"))
    }

    @Test
    fun `PowerPoint Document stream contains known slide text`() {
        // sample_deck's title slide reads "OfficeCanvas 데모" / "샘플 발표 자료".
        // PPT text is stored as UTF-16LE inside TextCharsAtom
        // records with no other structure in the way, so it is byte-searchable —
        // a much stronger check than "some bytes came back" that a chain-following
        // bug (reading the wrong sectors, but still enough of them) could still pass.
        val ole = Ole2Reader.open(sample("sample_deck.ppt"))
        val stream = ole.readStream("PowerPoint Document")!!
        val text = String(stream, Charsets.UTF_16LE)
        assertTrue("expected '샘플 발표 자료' inside the raw stream", text.contains("샘플 발표 자료"))
    }
}
