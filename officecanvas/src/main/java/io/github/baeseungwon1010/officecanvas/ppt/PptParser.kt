package io.github.baeseungwon1010.officecanvas.ppt

import io.github.baeseungwon1010.officecanvas.ole.Ole2Reader
import io.github.baeseungwon1010.officecanvas.util.Limits
import io.github.baeseungwon1010.officecanvas.slide.Box
import io.github.baeseungwon1010.officecanvas.slide.Deck
import io.github.baeseungwon1010.officecanvas.slide.Element
import io.github.baeseungwon1010.officecanvas.slide.Slide
import java.io.File

/**
 * Reads a legacy binary .ppt (PowerPoint 97-2003) into the same [Deck]/[Slide]
 * model the .pptx parser produces, so [io.github.baeseungwon1010.officecanvas.slide.SlideRenderer]
 * draws both.
 *
 * Like .pptx, every shape's position is stored absolutely, so no layout engine
 * is needed. The container differs: an OLE2 file ([Ole2Reader]) holding binary
 * records — MS-PPT for the slide outline, MS-ODRAW ("Escher") for shapes.
 * Coordinates are master units (1/576 inch; 1587.5 EMU each).
 *
 * What a slide looks like is spread across three places, all resolved here:
 * the slide's own shapes; its master (color scheme, background, decorative
 * objects like logos and bars, and the text styles that give titles and
 * bullets their size, color and alignment), which the slide follows per the
 * flags in its SlideAtom; and the document (default shape fill/line, fonts,
 * the picture store). Hidden slides are skipped, as PowerPoint's own export does.
 *
 * Not reproduced: freeform outlines, gradients (drawn as their first color),
 * WMF/EMF/PICT pictures, tables beyond their constituent shapes, animations.
 */
internal object PptParser {

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val EMU_PER_MASTER_UNIT = 1587.5

    /** One SlidePersistAtom from a SlideListWithText, with the text groups that
     *  follow it (used by placeholders that reference outline text). */
    private class ListEntry(val persistId: Int, val slideId: Int, val texts: List<List<Rec>>)

    private class Master(val rec: Rec, val scheme: ColorScheme?, val styles: TextStyles, val parent: Master?, val flags: Int)

    private class Doc(
        val r: RecordReader,
        val persist: Map<Int, Int>,
        val defaults: Map<Int, Int>,
        val blips: BlipStore,
        val fonts: List<String>,
        val baseStyles: TextStyles,
        val masters: Map<Int, ListEntry>,
        val masterCache: MutableMap<Int, Master?> = mutableMapOf()
    )

    fun parse(file: File): Deck {
        val ole = try {
            Ole2Reader.open(file)
        } catch (e: Ole2Reader.FormatException) {
            throw ParseException("not a .ppt: ${e.message}", e)
        }
        // The file stays open while the deck's pictures may be drawn (they are read
        // from it then); OfficeCanvas closes it through Deck.resources.
        var keep = false
        try {
            return parse(ole).also { keep = it.resources != null }
        } finally {
            if (!keep) ole.close()
        }
    }

    private fun parse(ole: Ole2Reader): Deck {
        val data = ole.readStream("PowerPoint Document")
            ?: throw ParseException("not a .ppt: missing PowerPoint Document stream")
        val currentUser = ole.readStream("Current User")
            ?: throw ParseException("not a .ppt: missing Current User stream")
        val r = RecordReader(data)

        val documentRec = r.record(0)?.takeIf { it.type == RT.DOCUMENT }
            ?: throw ParseException("PowerPoint Document stream doesn't start with a Document record")
        val documentAtom = r.child(documentRec, RT.DOCUMENT_ATOM)?.takeIf { it.length >= 8 }
            ?: throw ParseException("missing DocumentAtom")
        val widthEmu = (r.i32(documentAtom.start) * EMU_PER_MASTER_UNIT).toLong()
        val heightEmu = (r.i32(documentAtom.start + 4) * EMU_PER_MASTER_UNIT).toLong()

        val docChildren = r.children(documentRec)
        val environment = docChildren.firstOrNull { it.type == RT.ENVIRONMENT }
        val dgg = docChildren.firstOrNull { it.type == RT.DRAWING_GROUP }?.let { r.child(it, RT.DGG_CONTAINER) }
        val lists = docChildren.filter { it.type == RT.SLIDE_LIST_WITH_TEXT }

        val doc = Doc(
            r = r,
            persist = readPersistDirectory(r, currentUser),
            defaults = dgg?.let { g -> ShapeProps.read(r, r.children(g).filter { it.type == RT.FOPT }) }.orEmpty(),
            blips = readBlipStore(r, dgg, ole),
            fonts = environment?.let { readFonts(r, it) }.orEmpty(),
            baseStyles = readBaseStyles(r, environment),
            masters = lists.firstOrNull { it.instance == 1 }?.let { readList(r, it) }.orEmpty().associateBy { it.slideId }
        )

        // Instance 0 lists the slides in order (1 = masters, 2 = notes pages).
        // A slide listed twice is drawn once; a crafted list can repeat one costly
        // slide many thousands of times.
        val entries = lists.firstOrNull { it.instance == 0 }?.let { readList(r, it) }.orEmpty()
            .distinctBy { it.persistId }.take(Limits.MAX_PAGES)
        val slides = entries.mapIndexedNotNull { index, entry ->
            val slideRec = doc.persist[entry.persistId]?.let { r.record(it) }?.takeIf { it.type == RT.SLIDE }
                ?: return@mapIndexedNotNull Slide(null, emptyList())
            if (isHidden(r, slideRec)) return@mapIndexedNotNull null
            try {
                readSlide(doc, slideRec, entry.texts, slideNumber = index + 1, widthEmu, heightEmu)
            } catch (e: Exception) {
                Slide(null, emptyList()) // one damaged slide shouldn't cost the rest of the deck
            }
        }
        return Deck(widthEmu, heightEmu, slides, resources = ole.takeIf { doc.blips !== BlipStore.EMPTY })
    }

    private fun readSlide(doc: Doc, slideRec: Rec, texts: List<List<Rec>>, slideNumber: Int, widthEmu: Long, heightEmu: Long): Slide {
        val r = doc.r
        val kids = r.children(slideRec)
        val atom = kids.firstOrNull { it.type == RT.SLIDE_ATOM && it.length >= 22 }
        val flags = atom?.let { r.u16(it.start + 20) } ?: 0
        val master = atom?.let { masterFor(doc, r.i32(it.start + 12), depth = 0) }

        val ownScheme = ownScheme(r, slideRec)
        val scheme = (if (flags and FOLLOW_SCHEME != 0) null else ownScheme)
            ?: master?.effectiveScheme() ?: ownScheme ?: ColorScheme.DEFAULT
        val styles = master?.styles ?: doc.baseStyles
        val context = TextContext(styles, scheme, doc.fonts, slideNumber)

        val own = drawingOf(r, slideRec)?.let { DrawingReader(r, doc.defaults, doc.blips, context, texts).read(it, includePlaceholders = true) }

        // Walk up the master chain for decorative objects and the background,
        // stopping wherever a level says not to follow its own master.
        val inherited = mutableListOf<Element>()
        var background = if (flags and FOLLOW_BACKGROUND != 0) null else own?.background
        var followObjects = flags and FOLLOW_OBJECTS != 0
        var followBackground = background == null
        var level = master
        while (level != null && (followObjects || followBackground)) {
            val current: Master = level
            val drawing = drawingOf(r, current.rec)?.let {
                DrawingReader(r, doc.defaults, doc.blips, TextContext(current.styles, scheme, doc.fonts, slideNumber), emptyList())
                    .read(it, includePlaceholders = false)
            }
            if (followObjects) drawing?.elements?.let { inherited.addAll(0, it) }
            if (followBackground && drawing?.background != null) {
                background = drawing.background
                followBackground = false
            }
            followObjects = followObjects && current.flags and FOLLOW_OBJECTS != 0
            followBackground = followBackground && current.flags and FOLLOW_BACKGROUND != 0
            level = current.parent
        }

        val elements = mutableListOf<Element>()
        var backgroundArgb: Int? = null
        when (val bg = background) {
            is Background.Solid -> backgroundArgb = bg.argb
            is Background.Picture -> elements += bg.blip.toPicture(Box(0, 0, widthEmu, heightEmu))
            is Background.Shaded -> elements += Element.Shape(
                box = Box(0, 0, widthEmu, heightEmu), geometry = "rect", fillArgb = null, strokeArgb = null,
                strokeWidthEmu = 0, text = null, gradient = bg.gradient
            )
            null -> {}
        }
        elements += inherited
        own?.elements?.let { elements += it }
        return Slide(backgroundArgb, elements)
    }

    private fun Master.effectiveScheme(): ColorScheme? {
        var m: Master? = this
        while (m != null) {
            m.scheme?.let { return it }
            m = m.parent
        }
        return null
    }

    /** A slide's master may itself be a title master (a Slide record) that
     *  follows a main master; text styles always come from the main master. */
    private fun masterFor(doc: Doc, masterId: Int, depth: Int): Master? {
        if (masterId == 0 || depth > 4) return null
        return doc.masterCache.getOrPut(masterId) {
            val r = doc.r
            val entry = doc.masters[masterId] ?: return@getOrPut null
            val rec = doc.persist[entry.persistId]?.let { r.record(it) } ?: return@getOrPut null
            val atom = r.child(rec, RT.SLIDE_ATOM)?.takeIf { it.length >= 22 }
            val flags = atom?.let { r.u16(it.start + 20) } ?: 0
            val parent = atom?.let { r.i32(it.start + 12) }?.takeIf { it != masterId }?.let { masterFor(doc, it, depth + 1) }
            val styles = TextStyles.read(r, rec, doc.baseStyles.at(TextStyles.TYPE_OTHER, 0))
                .withFallback(parent?.styles ?: doc.baseStyles)
            Master(rec, ownScheme(r, rec), styles, parent, flags)
        }
    }

    /** The active scheme is the ColorSchemeAtom with instance 1 (instance 6
     *  entries are the alternatives offered in PowerPoint's scheme picker). */
    private fun ownScheme(r: RecordReader, slideOrMaster: Rec): ColorScheme? =
        r.children(slideOrMaster).firstOrNull { it.type == RT.COLOR_SCHEME_ATOM && it.instance == 1 }
            ?.let { ColorScheme.read(r, it) }

    private fun drawingOf(r: RecordReader, slideOrMaster: Rec): Rec? =
        r.child(slideOrMaster, RT.DRAWING)?.let { r.child(it, RT.DG_CONTAINER) }

    private fun isHidden(r: RecordReader, slideRec: Rec): Boolean {
        val info = r.child(slideRec, RT.SLIDE_SHOW_INFO_ATOM)?.takeIf { it.length >= 12 } ?: return false
        return r.u16(info.start + 10) and 0x4 != 0 // fHidden
    }

    /** SlidePersistAtoms and, after each, its outline text groups (each starting
     *  at a TextHeaderAtom). */
    private fun readList(r: RecordReader, list: Rec): List<ListEntry> {
        val out = mutableListOf<ListEntry>()
        var persistId = -1
        var slideId = 0
        var texts = mutableListOf<MutableList<Rec>>()
        fun flush() {
            if (persistId >= 0) out += ListEntry(persistId, slideId, texts)
        }
        for (rec in r.children(list)) {
            when {
                rec.type == RT.SLIDE_PERSIST_ATOM && rec.length >= 16 -> {
                    flush()
                    persistId = r.i32(rec.start)
                    slideId = r.i32(rec.start + 12)
                    texts = mutableListOf()
                }
                rec.type == RT.TEXT_HEADER_ATOM -> texts += mutableListOf(rec)
                else -> texts.lastOrNull()?.add(rec)
            }
        }
        flush()
        return out
    }

    /**
     * A .ppt saves incrementally: each save appends a UserEditAtom pointing at a
     * PersistDirectoryAtom for what changed and at the previous UserEditAtom.
     * Walking from the newest (Current User's offsetToCurrentEdit) and keeping
     * the first offset seen for each persist id gives the current version.
     */
    private fun readPersistDirectory(r: RecordReader, currentUser: ByteArray): Map<Int, Int> {
        val cu = RecordReader(currentUser)
        if (currentUser.size < 20) return emptyMap()
        var editOffset = cu.i32(16)
        val map = mutableMapOf<Int, Int>()
        val seen = HashSet<Int>() // each save is read once, however the chain loops
        while (editOffset != 0 && seen.add(editOffset)) {
            val edit = r.record(editOffset)?.takeIf { it.type == RT.USER_EDIT_ATOM && it.length >= 16 } ?: break
            r.record(r.i32(edit.start + 12))?.takeIf { it.type == RT.PERSIST_DIRECTORY_ATOM }?.let { dir ->
                var off = dir.start
                while (off + 4 <= dir.end) {
                    val header = r.i32(off)
                    val first = header and 0xFFFFF
                    off += 4
                    for (i in 0 until (header ushr 20)) {
                        if (off + 4 > dir.end) break
                        map.putIfAbsent(first + i, r.i32(off))
                        off += 4
                    }
                }
            }
            editOffset = r.i32(edit.start + 8)
        }
        return map
    }

    private fun readBlipStore(r: RecordReader, dgg: Rec?, ole: Ole2Reader): BlipStore {
        if (dgg == null || !ole.hasStream("Pictures")) return BlipStore.EMPTY
        val offsets = r.child(dgg, RT.BSTORE_CONTAINER)?.let { r.children(it) }.orEmpty()
            .filter { it.type == RT.FBSE && it.length >= 32 }
            .map { r.i32(it.start + 28) } // foDelay: offset into the Pictures stream
        return if (offsets.isEmpty()) BlipStore.EMPTY else BlipStore({ offset, length -> ole.readStream("Pictures", offset, length) }, offsets)
    }

    /** Font names in FontCollection order; text refers to fonts by index. */
    private fun readFonts(r: RecordReader, environment: Rec): List<String> {
        val collection = r.child(environment, RT.FONT_COLLECTION) ?: return emptyList()
        return r.children(collection).filter { it.type == RT.FONT_ENTITY_ATOM && it.length >= 64 }.map { atom ->
            String(r.data, atom.start, 64, Charsets.UTF_16LE).substringBefore('\u0000')
        }
    }

    /** Document-wide text defaults, the base every master style builds on. */
    private fun readBaseStyles(r: RecordReader, environment: Rec?): TextStyles {
        val kids = environment?.let { r.children(it) }.orEmpty()
        val cf = kids.firstOrNull { it.type == RT.TEXT_CF_EXCEPTION_ATOM }?.let { readCf(r, it.start, it.end)?.first } ?: Cf()
        val pf = kids.firstOrNull { it.type == RT.TEXT_PF_EXCEPTION_ATOM }?.let { readPf(r, it.start + 2, it.end)?.first } ?: Pf()
        return if (environment != null) TextStyles.read(r, environment, pf to cf) else TextStyles(pf to cf, emptyMap())
    }

    private const val FOLLOW_OBJECTS = 0x1
    private const val FOLLOW_SCHEME = 0x2
    private const val FOLLOW_BACKGROUND = 0x4
}
