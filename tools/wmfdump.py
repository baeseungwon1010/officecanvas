"""Dev tool: lists the WMF/EMF metafile records of a .ppt picture (by 1-based
blip index into the Pictures stream order). Usage: python3 wmfdump.py deck.ppt <pib> [max]"""
import struct
import sys
import zlib

import olefile

ole = olefile.OleFileIO(sys.argv[1])
pics = ole.openstream("Pictures").read()
want = int(sys.argv[2])
limit = int(sys.argv[3]) if len(sys.argv) > 3 else 400
doc = ole.openstream("PowerPoint Document").read()
# pib -> offset in Pictures: Document -> DrawingGroup(0x040B) -> DggContainer -> BStoreContainer -> FBSE*
def kids(buf, start, end):
    out = []
    while start + 8 <= end:
        _, t, ln = struct.unpack_from("<HHI", buf, start)
        out.append((t, start + 8, min(start + 8 + ln, end)))
        start = start + 8 + ln
    return out


fbse = []
for t, s0, e0 in kids(doc, 8, 8 + struct.unpack_from("<I", doc, 4)[0]):
    if t == 0x040B:
        for t1, s1, e1 in kids(doc, s0, e0):
            if t1 == 0xF000:
                for t2, s2, e2 in kids(doc, s1, e1):
                    if t2 == 0xF001:
                        fbse = [struct.unpack_from("<I", doc, s3 + 28)[0] for t3, s3, _ in kids(doc, s2, e2) if t3 == 0xF007]
target = fbse[want - 1]
o, k = 0, 0
WMF = {0x020B: "SetWindowOrg", 0x020C: "SetWindowExt", 0x0103: "SetMapMode", 0x02FC: "CreateBrush", 0x02FA: "CreatePen",
       0x02FB: "CreateFont", 0x012D: "SelectObject", 0x01F0: "DeleteObject", 0x0214: "MoveTo", 0x0213: "LineTo",
       0x0324: "Polygon", 0x0325: "Polyline", 0x0538: "PolyPolygon", 0x041B: "Rectangle", 0x0418: "Ellipse",
       0x061C: "RoundRect", 0x0521: "TextOut", 0x0A32: "ExtTextOut", 0x0209: "SetTextColor", 0x012E: "SetTextAlign",
       0x0106: "SetPolyFillMode", 0x0102: "SetBkMode", 0x0201: "SetBkColor", 0x001E: "SaveDC", 0x0127: "RestoreDC",
       0x0F43: "StretchDIB", 0x0B41: "DibStretchBlt", 0x0626: "Escape", 0x0104: "SetROP2", 0x0416: "IntersectClipRect",
       0x0817: "Arc", 0x081A: "Pie", 0x0830: "Chord", 0x0107: "SetStretchBltMode", 0x0231: "SetMapperFlags",
       0x0211: "SetViewportOrg", 0x020E: "SetViewportExt", 0x0410: "ScaleWindowExt", 0x0412: "ScaleViewportExt",
       0x020F: "OffsetWindowOrg", 0x0211: "OffsetViewportOrg", 0x0000: "EOF"}
while o + 8 <= len(pics):
    vi, t, ln = struct.unpack_from("<HHI", pics, o)
    k += 1
    if o == target:
        inst = vi >> 4
        print(f"blip {k} type {t:#x} inst {inst:#x} len {ln}")
        two = inst in (0x217, 0x3D5, 0x543)
        h = o + 8 + (32 if two else 16)
        size, l, tp, r, b = struct.unpack_from("<Iiiii", pics, h)
        saved = struct.unpack_from("<I", pics, h + 28)[0]
        comp = pics[h + 32]
        data = pics[h + 34:h + 34 + saved]
        if comp == 0:
            data = zlib.decompress(data)
        print("bounds", l, tp, r, b, "size", size)
        if t == 0xF01B:
            p = 18
            n = 0
            while p + 6 <= len(data) and n < limit:
                words, fn = struct.unpack_from("<IH", data, p)
                params = data[p + 6:p + words * 2]
                nm = WMF.get(fn, hex(fn))
                extra = ""
                if fn in (0x0A32, 0x0521):
                    if fn == 0x0A32:
                        ln_ = struct.unpack_from("<H", params, 4)[0]
                        opts = struct.unpack_from("<H", params, 6)[0]
                        sa = 8 + (8 if opts & 6 else 0)
                        extra = repr(params[sa:sa + ln_].decode("latin-1")) + f" y,x={struct.unpack_from('<hh', params, 0)} opts={opts}"
                    else:
                        ln_ = struct.unpack_from("<H", params, 0)[0]
                        extra = repr(params[2:2 + ln_].decode("latin-1"))
                elif fn == 0x02FB:
                    extra = f"h={struct.unpack_from('<h', params, 0)[0]} esc={struct.unpack_from('<h', params, 4)[0]} w={struct.unpack_from('<h', params, 8)[0]} face={params[18:50].split(b'\0')[0]}"
                else:
                    extra = " ".join(str(v) for v in struct.unpack_from(f"<{min(len(params) // 2, 10)}h", params, 0))
                print(f"{nm} {extra}")
                if fn == 0:
                    break
                p += words * 2
                n += 1
        break
    o += 8 + ln
