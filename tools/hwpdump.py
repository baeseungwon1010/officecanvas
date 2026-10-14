"""Dev tool: dumps an HWP 5.0 file's records (DocInfo and BodyText sections) to
check what the file actually stores before writing parser code.
Usage: python3 hwpdump.py file.hwp [docinfo|section N] [max records]"""
import struct
import sys
import zlib

import olefile

TAGS = {16: "DOCUMENT_PROPERTIES", 17: "ID_MAPPINGS", 18: "BIN_DATA", 19: "FACE_NAME", 20: "BORDER_FILL",
        21: "CHAR_SHAPE", 22: "TAB_DEF", 23: "NUMBERING", 24: "BULLET", 25: "PARA_SHAPE", 26: "STYLE",
        66: "PARA_HEADER", 67: "PARA_TEXT", 68: "PARA_CHAR_SHAPE", 69: "PARA_LINE_SEG", 70: "PARA_RANGE_TAG",
        71: "CTRL_HEADER", 72: "LIST_HEADER", 73: "PAGE_DEF", 74: "FOOTNOTE_SHAPE", 75: "PAGE_BORDER_FILL",
        76: "SHAPE_COMPONENT", 77: "TABLE", 78: "SC_LINE", 79: "SC_RECTANGLE", 80: "SC_ELLIPSE", 81: "SC_ARC",
        82: "SC_POLYGON", 83: "SC_CURVE", 84: "SC_OLE", 85: "SC_PICTURE", 86: "SC_CONTAINER", 87: "CTRL_DATA",
        88: "EQEDIT", 99: "SC_TEXTART"}


def records(data):
    off = 0
    while off + 4 <= len(data):
        h = struct.unpack_from("<I", data, off)[0]
        tag, level, size = h & 0x3FF, (h >> 10) & 0x3FF, h >> 20
        off += 4
        if size == 0xFFF:
            size = struct.unpack_from("<I", data, off)[0]
            off += 4
        yield tag, level, data[off:off + size]
        off += size


def text(payload):
    out, i = [], 0
    while i + 2 <= len(payload):
        c = struct.unpack_from("<H", payload, i)[0]
        if c < 32 and c not in (0, 10, 13, 24, 25, 26, 27, 28, 29, 30, 31):
            out.append("{%d}" % c)  # extended/inline control: 8 WCHARs
            i += 16
            continue
        out.append("\\n" if c == 10 else "¶" if c == 13 else chr(c) if c >= 32 else "{%d}" % c)
        i += 2
    return "".join(out)


def describe(tag, p):
    if tag == 67:
        return text(p)[:120]
    if tag == 69:
        segs = [struct.unpack_from("<IiiiiiiiI", p, i) for i in range(0, len(p) - 35, 36)]
        return " | ".join("pos%d y%d x%d w%d h%d base%d" % (s[0], s[1], s[6], s[7], s[2], s[4]) for s in segs[:6])
    if tag == 68:
        return " ".join("%d:%d" % struct.unpack_from("<II", p, i) for i in range(0, len(p) - 7, 8))
    if tag == 71:
        return repr(p[:4][::-1].decode("latin1"))
    if tag == 73:
        v = struct.unpack_from("<IIIIIIIII", p, 0)
        return "w%d h%d L%d R%d T%d B%d head%d foot%d gutter%d" % v
    if tag == 21:
        size, attr = struct.unpack_from("<iI", p, 42)
        color = struct.unpack_from("<I", p, 52)[0]
        return "faces%s size%d attr%#x color%06X" % (struct.unpack_from("<7H", p, 0), size, attr, color)
    if tag == 19:
        n = struct.unpack_from("<H", p, 1)[0]
        return p[3:3 + 2 * n].decode("utf-16le")
    if tag == 72:
        return p[:40].hex()
    if tag == 77:
        return p[:40].hex()
    return p[:24].hex()


def main():
    ole = olefile.OleFileIO(sys.argv[1])
    header = ole.openstream("FileHeader").read()
    flags = struct.unpack_from("<I", header, 36)[0]
    version = struct.unpack_from("<I", header, 32)[0]
    print("version %d.%d.%d.%d flags %#x" % (version >> 24, (version >> 16) & 255, (version >> 8) & 255, version & 255, flags))
    print("streams:", ["/".join(e) for e in ole.listdir()])
    which = sys.argv[2] if len(sys.argv) > 2 else "section"
    name = "DocInfo" if which == "docinfo" else "BodyText/Section%s" % (sys.argv[3] if len(sys.argv) > 3 else "0")
    data = ole.openstream(name).read()
    if flags & 1:
        data = zlib.decompress(data, -15)
    limit = int(sys.argv[4] if which != "docinfo" and len(sys.argv) > 4 else sys.argv[3] if which == "docinfo" and len(sys.argv) > 3 else 400)
    for k, (tag, level, payload) in enumerate(records(data)):
        if k >= limit:
            break
        print("%s%-18s %5d  %s" % ("  " * level, TAGS.get(tag, tag), len(payload), describe(tag, payload)))


main()
