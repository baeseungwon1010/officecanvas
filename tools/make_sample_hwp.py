"""Builds sample.hwp: a Hangul 5.0 binary document with the same three pages as
sample.hwpx (headings, body text, a table), plus what the .hwp tests check
exactly: a centered bold red title, a table cell with a fill and middle vertical
alignment, a cell that leaves its margins to the table, an embedded PNG
cropped to its right half, a header line ending in a tab and the page number
field, a page number at the bottom center ("- 2 -"), a floating rectangle text
box, an inline line, a paragraph marked as starting a page while its line sits
at the top like the one before it, numbered and bulleted paragraphs, and an
equation in a line of text.

Every line carries its saved position (PARA_LINE_SEG), computed here with fixed
metrics the way make_sample_hwpx.py does. The OLE2 container is written by a
small compound-file writer below; streams are raw-deflated as Hangul does.
Run: python3 make_sample_hwp.py <outdir>"""
import io
import struct
import sys
import zlib

from PIL import Image

out = sys.argv[1] if len(sys.argv) > 1 else "."

# ---- compound file (OLE2) writer --------------------------------------------

SECTOR, MINI, CUTOFF = 512, 64, 4096
FREE, END, FATSECT = 0xFFFFFFFF, 0xFFFFFFFE, 0xFFFFFFFD


def compound_file(streams):
    """streams: {"Name" or "Storage/Name": bytes}. Returns the file's bytes."""
    # Directory tree: root, storages, streams. Siblings are chained through the
    # right-sibling field (a degenerate but valid red-black tree, all black).
    entries = [{"name": "Root Entry", "type": 5, "children": []}]
    storages = {}
    for path, data in streams.items():
        parent = 0
        parts = path.split("/")
        for depth, part in enumerate(parts[:-1]):
            key = "/".join(parts[:depth + 1])
            if key not in storages:
                storages[key] = len(entries)
                entries.append({"name": part, "type": 1, "children": []})
                entries[parent]["children"].append(storages[key])
            parent = storages[key]
        entries.append({"name": parts[-1], "type": 2, "data": data, "children": []})
        entries[parent]["children"].append(len(entries) - 1)

    # Small streams go into the mini stream, large ones into regular sectors.
    mini = bytearray()
    minifat = []
    for e in entries:
        if e["type"] == 2 and len(e["data"]) < CUTOFF:
            e["start"] = len(mini) // MINI if e["data"] else END
            n = (len(e["data"]) + MINI - 1) // MINI
            for k in range(n):
                minifat.append(e["start"] + k + 1 if k < n - 1 else END)
            mini += e["data"] + b"\0" * (n * MINI - len(e["data"]))

    sectors = []  # list of 512-byte blocks after the header
    fat = []

    def put(data):
        if not data:
            return END
        n = (len(data) + SECTOR - 1) // SECTOR
        first = len(sectors)
        for k in range(n):
            sectors.append(data[k * SECTOR:(k + 1) * SECTOR].ljust(SECTOR, b"\0"))
            fat.append(first + k + 1 if k < n - 1 else END)
        return first

    for e in entries:
        if e["type"] == 2 and len(e["data"]) >= CUTOFF:
            e["start"] = put(e["data"])
    entries[0]["start"] = put(bytes(mini)) if mini else END
    entries[0]["size"] = len(mini)
    minifat_bytes = b"".join(struct.pack("<I", v) for v in minifat)
    minifat_start = put(minifat_bytes) if minifat else END

    def dir_entry(e, index):
        name = e["name"].encode("utf-16le") + b"\0\0"
        kids = e["children"]
        right = FREE
        for i, other in enumerate(entries):
            if index in other["children"]:
                sib = other["children"]
                pos = sib.index(index)
                right = sib[pos + 1] if pos + 1 < len(sib) else FREE
        child = kids[0] if kids else FREE
        size = e.get("size", len(e.get("data", b"")))
        return (name.ljust(64, b"\0") + struct.pack("<HBB", len(name), e["type"], 1)
                + struct.pack("<III", FREE, right, child) + b"\0" * 16 + b"\0" * 4 + b"\0" * 16
                + struct.pack("<IQ", e.get("start", END), size))

    directory = b"".join(dir_entry(e, i) for i, e in enumerate(entries))
    directory += b"\0" * ((-len(directory)) % SECTOR)
    dir_start = put(directory)

    # FAT sectors describe themselves too.
    n_fat = 1
    while (len(fat) + n_fat) > n_fat * (SECTOR // 4):
        n_fat += 1
    fat_start = len(sectors)
    fat += [FATSECT] * n_fat
    fat_bytes = b"".join(struct.pack("<I", v) for v in fat)
    fat_bytes += struct.pack("<I", FREE) * ((n_fat * SECTOR - len(fat_bytes)) // 4)
    for k in range(n_fat):
        sectors.append(fat_bytes[k * SECTOR:(k + 1) * SECTOR])

    header = bytearray(SECTOR)
    header[0:8] = bytes.fromhex("D0CF11E0A1B11AE1")
    struct.pack_into("<HHHHH", header, 24, 0x3E, 3, 0xFFFE, 9, 6)
    struct.pack_into("<I", header, 44, n_fat)
    struct.pack_into("<I", header, 48, dir_start)
    struct.pack_into("<I", header, 56, CUTOFF)
    struct.pack_into("<I", header, 60, minifat_start)
    struct.pack_into("<I", header, 64, (len(minifat_bytes) + SECTOR - 1) // SECTOR if minifat else 0)
    struct.pack_into("<I", header, 68, END)
    struct.pack_into("<I", header, 72, 0)
    for i in range(109):
        struct.pack_into("<I", header, 76 + i * 4, fat_start + i if i < n_fat else FREE)
    return bytes(header) + b"".join(sectors)


# ---- HWP records -------------------------------------------------------------

def rec(tag, level, payload):
    size = len(payload)
    if size >= 0xFFF:
        return struct.pack("<II", tag | (level << 10) | (0xFFF << 20), size) + payload
    return struct.pack("<I", tag | (level << 10) | (size << 20)) + payload


def colorref(rgb):
    return struct.pack("<I", ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF))


def utf16(s):
    return struct.pack("<H", len(s)) + s.encode("utf-16le")


def deflate(data):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


# DocInfo: fonts, character shapes, paragraph shapes, border fills, the picture.
FACES = ["함초롬돋움", "함초롬바탕"]
# (size 1/100 pt, color, bold, face index)
CHAR_SHAPES = [(1000, 0x000000, False, 0), (1400, 0x1F3864, True, 0), (1600, 0xC00000, True, 1), (900, 0x595959, False, 0)]
# (alignment, heading kind, level, numbering/bullet id); alignment codes: 0 justify,
# 1 left, 2 right, 3 center; heading kinds: 2 numbered, 3 bullet.
PARA_SHAPES = [(1, 0, 0, 0), (3, 0, 0, 0), (0, 0, 0, 0), (1, 2, 0, 1), (1, 2, 1, 1), (1, 3, 0, 1)]


def char_shape(size, color, bold, face):
    p = struct.pack("<7H", *([face] * 7)) + bytes([100] * 7) + bytes([0] * 7) + bytes([100] * 7) + bytes([0] * 7)
    p += struct.pack("<iI", size, 0x2 if bold else 0) + bytes([10, 10]) + colorref(color)
    p += colorref(0) + colorref(0xFFFFFF) + colorref(0xB2B2B2) + struct.pack("<H", 0) + colorref(0)
    return p


def border_fill(fill=None):
    side = bytes([1, 1]) + colorref(0x000000)
    p = struct.pack("<H", 0) + side * 4 + bytes([0, 0]) + colorref(0)
    if fill is None:
        return p + struct.pack("<I", 0) + struct.pack("<I", 0)
    return p + struct.pack("<I", 1) + colorref(fill) + colorref(0) + struct.pack("<i", -1) + struct.pack("<I", 0)


docinfo = rec(16, 0, struct.pack("<H", 1) + b"\0" * 24)
mappings = [1] + [len(FACES)] * 7 + [2, len(CHAR_SHAPES), 0, 1, 1, len(PARA_SHAPES), 1, 0, 0, 0]
docinfo += rec(17, 0, b"".join(struct.pack("<i", v) for v in mappings))
docinfo += rec(18, 1, struct.pack("<HH", 0x0001, 1) + utf16("png"))
for _ in range(7):
    for f in FACES:
        docinfo += rec(19, 1, bytes([0]) + utf16(f))
docinfo += rec(20, 1, border_fill())
docinfo += rec(20, 1, border_fill(0xDDEBF7))
for cs in CHAR_SHAPES:
    docinfo += rec(21, 1, char_shape(*cs))
# Numbering: seven levels, each a paragraph head (attributes with the number
# shape in bits 5-8, width adjustment, distance from the text in percent, char
# shape) and its format; then the start number and each level's start.
LEVELS = [("^1.", 0), ("^2.", 8), ("^3)", 0), ("^4)", 8), ("(^5)", 0), ("(^6)", 8), ("^7", 1)]
numbering = b"".join(struct.pack("<IHHi", shape << 5, 0, 50, -1) + utf16(fmt) for fmt, shape in LEVELS)
docinfo += rec(23, 1, numbering + struct.pack("<H", 0) + struct.pack("<7I", *([1] * 7)))
# Bullet: a paragraph head and the character (Wingdings' small bullet).
docinfo += rec(24, 1, struct.pack("<IHHi", 0, 0, 50, -1) + struct.pack("<H", 0xF09F) + b"\0" * 11)
for align, kind, level, ref in PARA_SHAPES:
    body = struct.pack("<I", (align << 2) | (kind << 23) | (level << 25)) + b"\0" * 26 + struct.pack("<H", ref) + b"\0" * 26
    docinfo += rec(25, 1, body)
docinfo += rec(26, 1, utf16("바탕글") + utf16("Normal") + struct.pack("<BBhHH", 0, 0, 1042, 0, 0) + b"\0\0")

# Body: positions in HWPUNIT, relative to the body area (as in sample.hwpx).
PAGE_W, PAGE_H = 59528, 84186
LEFT, RIGHT, TOP, BOTTOM, HEAD, FOOT = 8504, 8504, 5668, 4252, 4252, 4252
BODY_W = PAGE_W - LEFT - RIGHT
BODY_H = PAGE_H - TOP - HEAD - BOTTOM - FOOT
CHARS = 40
TAB_WIDTH = 20000  # what Hangul saves as the width a tab took
LINE = {0: 1600, 1: 2240, 2: 2560, 3: 1440}


class Flow:
    def __init__(self):
        self.y = 0
        self.pages = 1

    def place(self, h):
        if self.y + h > BODY_H:
            self.y = 0
            self.pages += 1
        y = self.y
        self.y += h
        return y


def lineseg(start, y, shape, width=BODY_W):
    h = CHAR_SHAPES[shape][0]
    return struct.pack("<IiiiiiiiI", start, y, h, h, h * 85 // 100, h * 6 // 10, 0, width, 0x60000)


def ctrl_chars(*codes):
    return b"".join(struct.pack("<H", c) * 1 + b"\0" * 12 + struct.pack("<H", c) for c in codes)


def paragraph(level, flow, text, shape=0, para_shape=0, controls=b"", ctrl_text=b"", page_break=False, lines_at=None,
              width=BODY_W, ctrl_after=b""):
    """controls: CTRL_HEADER records (already at level+1); ctrl_text: their
    8-unit placeholders, which come first in the text (ctrl_after: after it)."""
    # A tab is an eight-unit control holding the width it took (HWPUNIT) and its type.
    tab = struct.pack("<HIH", 9, TAB_WIDTH, 0x100) + b"\0" * 6 + struct.pack("<H", 9)
    body = ctrl_text + text.encode("utf-16le").replace("\t".encode("utf-16le"), tab) + ctrl_after + struct.pack("<H", 13)
    units = len(body) // 2
    pieces = [text[i:i + CHARS] for i in range(0, len(text), CHARS)] or [""]
    segs = b""
    pos = len(ctrl_text) // 2
    for k, piece in enumerate(pieces):
        y = lines_at[k] if lines_at else flow.place(LINE[shape])
        segs += lineseg(0 if k == 0 else pos, y, shape, width)
        pos += len(piece)
    header = struct.pack("<IIHBBHHHIH", units, 0, para_shape, 0, 0x04 if page_break else 0, 1, 0, len(pieces), 0, 0)
    out = rec(66, level, header)
    out += rec(67, level + 1, body)
    out += rec(68, level + 1, struct.pack("<II", 0, shape))
    out += rec(69, level + 1, segs)
    return out + controls


flow = Flow()
section = b""
# Section definition (page size, margins), a header line and a page number control.
page_def = struct.pack("<9II", PAGE_W, PAGE_H, LEFT, RIGHT, TOP, BOTTOM, HEAD, FOOT, 0, 0)
secd = rec(71, 1, struct.pack("<I", 0x73656364) + b"\0" * 32) + rec(73, 2, page_def)
head_list = rec(72, 2, struct.pack("<HHI", 1, 0, 0) + b"\0" * 26)
# The header line ends in a tab and an auto number field of type 0 (the page number).
atno = rec(71, 3, struct.pack("<I", 0x61746E6F) + struct.pack("<I", 0) + b"\0" * 8)
head_para = paragraph(2, None, "OfficeCanvas 머리말\t", 3, 0, lines_at=[0], ctrl_after=ctrl_chars(18), controls=atno)
head = rec(71, 1, struct.pack("<I", 0x68656164) + struct.pack("<I", 0)) + head_list + head_para
# Bottom center (5), with "-" as the side character: "- 2 -".
pgnp = rec(71, 1, struct.pack("<I", 0x70676E70) + struct.pack("<I", 5 << 8) + struct.pack("<HHHH", 0, 0, 0, ord("-")))
section += paragraph(0, flow, "1. 문서 구조와 줄 위치", 2, 1,
                     controls=secd + head + pgnp, ctrl_text=ctrl_chars(2, 16, 21))

BODY = ("이 문서는 OfficeCanvas의 HWP 렌더러를 시험하기 위해 만든 합성 문서입니다. 한글은 문서를 저장할 때 "
        "각 줄이 놓일 위치와 페이지가 나뉘는 지점을 미리 계산해서 파일 안에 남겨 두기 때문에, 렌더러는 줄바꿈과 "
        "쪽 나눔을 다시 계산하지 않고 저장된 값을 그대로 읽어 그릴 수 있습니다.")
for i in range(8):
    section += paragraph(0, flow, f"({i + 1}) " + BODY, 0, 2)

# A 2 x 2 table: the first cell filled (border fill 2) and vertically centered.
section += paragraph(0, flow, "2. 형식별 비교", 1)
col_w, row_h = BODY_W // 2, 3000
table_y = flow.place(2 * row_h + 600)
cells = b""
for r, row in enumerate([("형식", "저장 방식"), ("HWP", "OLE2 + 레코드")]):
    for c, value in enumerate(row):
        fill_id = 2 if (r, c) == (0, 0) else 1
        flags = (1 << 5) if (r, c) == (0, 0) else 0
        lh = struct.pack("<HHI", 1, 0, flags) + struct.pack("<HHHH", c, r, 1, 1) + struct.pack("<II", col_w, row_h)
        # The last cell leaves its margins to the table's (0xFFFF).
        margins = (0xFFFF,) * 4 if (r, c) == (1, 1) else (510, 510, 141, 141)
        lh += struct.pack("<HHHH", *margins) + struct.pack("<H", fill_id) + struct.pack("<I", col_w)
        cells += rec(72, 2, lh) + paragraph(2, None, value, 0, 0, lines_at=[0], width=col_w - 1020)
tbl_common = struct.pack("<I", 0x74626C20) + struct.pack("<IiiII", 0x1, 0, 0, BODY_W, 2 * row_h) + b"\0" * 22
table = rec(71, 1, tbl_common) + rec(77, 2, struct.pack("<IHHH", 0, 2, 2, 0) + struct.pack("<HHHH", 510, 510, 141, 141)
                                    + struct.pack("<HH", 2, 2) + struct.pack("<HH", 1, 0)) + cells
units = ctrl_chars(11)
section += rec(66, 0, struct.pack("<IIHBBHHHIH", 9, 0, 0, 0, 0, 1, 0, 1, 0, 0))
section += rec(67, 1, units + struct.pack("<H", 13))
section += rec(68, 1, struct.pack("<II", 0, 0))
section += rec(69, 1, struct.pack("<IiiiiiiiI", 0, table_y, 2 * row_h, 2 * row_h, 2 * row_h, 600, 0, BODY_W, 0x60000))
section += table

# An embedded picture (treated as a character) on its own line.
pic_w, pic_h = 14400, 7200
pic_y = flow.place(pic_h + 600)
gso_common = struct.pack("<I", 0x67736F20) + struct.pack("<IiiII", 0x1, 0, 0, pic_w, pic_h) + b"\0" * 22
shape = struct.pack("<II", 0x24706963, 0x24706963) + b"\0" * 188
# Border (12 bytes), the image's corners (4 points), the visible part (left,
# top, right, bottom) within the image's own size, margins and picture info
# (BinData id at 71), border alpha, instance id, effects, and last the image's
# own size (80 x 40 here) and a flag. The visible part is the right half.
corners = struct.pack("<8i", 0, 0, 8000, 0, 8000, 4000, 0, 4000)
picture = (b"\0" * 12 + corners + struct.pack("<4i", 40, 0, 80, 40) + b"\0" * 11 + struct.pack("<H", 1)
           + b"\0" + struct.pack("<II", 0, 0) + struct.pack("<ii", 80, 40) + b"\0")
section += rec(66, 0, struct.pack("<IIHBBHHHIH", 9, 0, 0, 0, 0, 1, 0, 1, 0, 0))
section += rec(67, 1, ctrl_chars(11) + struct.pack("<H", 13))
section += rec(68, 1, struct.pack("<II", 0, 0))
section += rec(69, 1, struct.pack("<IiiiiiiiI", 0, pic_y, pic_h, pic_h, pic_h, 600, 0, BODY_W, 0x60000))
section += rec(71, 1, gso_common) + rec(76, 2, shape) + rec(85, 3, picture)


def gso_header(attr, vert_offset, horz_offset, w, h):
    return rec(71, 1, struct.pack("<I", 0x67736F20) + struct.pack("<IiiII", attr, vert_offset, horz_offset, w, h) + b"\0" * 22)


def shape_component(kind, w, h, line_color, line_width, fill=None):
    """A top-level shape component: its id twice, offsets, group level and version,
    original and current size, flags, rotation; identity rendering matrices (a
    translation and one scale/rotation pair); line info; and the fill."""
    ident = struct.pack("<6d", 1, 0, 0, 0, 1, 0)
    p = struct.pack("<II", kind, kind) + struct.pack("<iiHH", 0, 0, 0, 0) + struct.pack("<IIIII", w, h, w, h, 0)
    p += struct.pack("<Hii", 0, w // 2, h // 2) + struct.pack("<H", 1) + ident * 3
    p += colorref(line_color) + struct.pack("<iI", line_width, 1) + bytes([0])
    p += struct.pack("<I", 1) + colorref(fill) + colorref(0) + struct.pack("<i", -1) if fill is not None else struct.pack("<I", 0)
    return rec(76, 2, p + b"\0" * 5)


# A floating rectangle text box: moves with the paragraph (bit 13), placed by
# offsets from it (vertical and horizontal reference 2), in front of the text (3).
box_w, box_h = 14000, 4000
float_attr = (2 << 3) | (2 << 8) | (1 << 13) | (3 << 21)
box = gso_header(float_attr, 1500, BODY_W - box_w, box_w, box_h)
box += shape_component(0x24726563, box_w, box_h, 0x1F3864, 71, 0xDDEBF7)
box += rec(72, 3, struct.pack("<HHI", 1, 0, 1 << 5) + struct.pack("<HHHH", 283, 283, 283, 283) + struct.pack("<I", box_w))
box += paragraph(3, None, "글상자 안의 글", 0, 0, lines_at=[0], width=box_w - 566)
box += rec(79, 3, bytes([20]) + struct.pack("<8i", 0, 0, box_w, 0, box_w, box_h, 0, box_h))
section += paragraph(0, flow, "(9) " + BODY, 0, 2, controls=box, ctrl_text=ctrl_chars(11))

# A horizontal line, treated as a character, on its own line.
line_y = flow.place(600 + 600)
line = gso_header(0x1, 0, 0, BODY_W, 0) + shape_component(0x246C696E, BODY_W, 0, 0xC00000, 57)
line += rec(78, 3, struct.pack("<4iH", 0, 0, BODY_W, 0, 0))
section += rec(66, 0, struct.pack("<IIHBBHHHIH", 9, 0, 0, 0, 0, 1, 0, 1, 0, 0))
section += rec(67, 1, ctrl_chars(11) + struct.pack("<H", 13))
section += rec(68, 1, struct.pack("<II", 0, 0))
section += rec(69, 1, struct.pack("<IiiiiiiiI", 0, line_y, 600, 600, 600, 600, 0, BODY_W, 0x60000))
section += line

for i in range(9):
    section += paragraph(0, flow, f"({i + 10}) " + BODY, 0, 2)

# A page break whose first line sits at the top just like the previous page's.
flow.y = 0
flow.pages += 1
section += paragraph(0, flow, "3. 맺음말", 1, page_break=True)
section += paragraph(0, flow, "이 샘플은 실제 문서를 대신해 테스트에 쓰입니다.", 3)
# Numbered paragraphs (levels 1 and 2) and a bullet; Hangul adds the numbers.
for text, shape in [("첫째 항목", 3), ("세부 항목", 4), ("둘째 항목", 3), ("점 항목", 5)]:
    section += paragraph(0, flow, text, 0, shape)

# An equation set like a character in a line of text: a control (treated as a
# character, 6000 x 2400) with an EQEDIT record: attributes, the script, the base
# size, color, baseline (percent from the top), version and font.
EQUATION = "{a+b} over {2} TIMES h"
eq_body = (struct.pack("<I", 0) + utf16(EQUATION) + struct.pack("<IIh", 1000, 0, 70)
           + utf16("Equation Version 60") + utf16("HYhwpEQ"))
eq = rec(71, 1, struct.pack("<I", 0x65716564) + struct.pack("<IiiII", 0x1, 0, 0, 6000, 2400) + b"\0" * 22) + rec(88, 2, eq_body)
section += paragraph(0, flow, "사다리꼴의 넓이는 ", 0, 0, controls=eq, ctrl_after=ctrl_chars(11))

img = Image.new("RGB", (80, 40), (0xDC, 0x28, 0x28))
img.paste((0x3A, 0x5B, 0xA0), (40, 0, 80, 40))
buf = io.BytesIO()
img.save(buf, "PNG")

file_header = bytearray(256)
file_header[0:17] = b"HWP Document File"
struct.pack_into("<II", file_header, 32, 0x05010100, 0x1)  # version 5.1.1.0, compressed
streams = {
    "FileHeader": bytes(file_header),
    "DocInfo": deflate(docinfo),
    "BodyText/Section0": deflate(section),
    "BinData/BIN0001.png": deflate(buf.getvalue()),
}
with open(f"{out}/sample.hwp", "wb") as f:
    f.write(compound_file(streams))
print(f"sample.hwp: {flow.pages} pages")
