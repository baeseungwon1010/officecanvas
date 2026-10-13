"""Dev tool: dumps a binary .ppt's per-slide shapes (shape type, FOPT properties
incl. complex payload sizes, blip types, PPT9 tags) to check what the file
actually stores before writing parser code. Usage: python3 pptdump.py deck.ppt [slideNo]"""
import struct
import sys
import olefile

data_path = sys.argv[1]
only = int(sys.argv[2]) if len(sys.argv) > 2 else None
ole = olefile.OleFileIO(data_path)
doc = ole.openstream("PowerPoint Document").read()
pics = ole.openstream("Pictures").read() if ole.exists("Pictures") else b""
cu = ole.openstream("Current User").read()


def rec(buf, off):
    vi, t, ln = struct.unpack_from("<HHI", buf, off)
    return dict(off=off, ver=vi & 0xF, inst=vi >> 4, type=t, start=off + 8, end=min(off + 8 + ln, len(buf)))


def kids(buf, r):
    out, o = [], r["start"]
    while o + 8 <= r["end"]:
        k = rec(buf, o)
        out.append(k)
        if k["end"] <= o:
            break
        o = k["end"]
    return out


def walk(buf, r, depth=0):
    yield r, depth
    if r["ver"] == 0xF:
        for k in kids(buf, r):
            yield from walk(buf, k, depth + 1)


# persist directory
persist = {}
edit = struct.unpack_from("<I", cu, 16)[0]
while edit:
    e = rec(doc, edit)
    last, pdoff = struct.unpack_from("<II", doc, e["start"] + 8)
    pd = rec(doc, pdoff)
    o = pd["start"]
    while o + 4 <= pd["end"]:
        h = struct.unpack_from("<I", doc, o)[0]
        o += 4
        for i in range(h >> 20):
            persist.setdefault((h & 0xFFFFF) + i, struct.unpack_from("<I", doc, o)[0])
            o += 4
    edit = last

root = rec(doc, 0)
lists = [k for k in kids(doc, root) if k["type"] == 0x0FF0]
slist = [l for l in lists if l["inst"] == 0]
slides = [k for k in kids(doc, slist[0]) if k["type"] == 0x03F3] if slist else []
for n, sp in enumerate(slides, 1):
    if only and n != only:
        continue
    pid = struct.unpack_from("<I", doc, sp["start"])[0]
    srec = rec(doc, persist[pid])
    print(f"=== slide {n}")
    for r, d in walk(doc, srec):
        if r["type"] == 0xF00A:
            print("  " * d + f"FSP spt={r['inst']} flags=0x{struct.unpack_from('<I', doc, r['start'] + 4)[0]:X}")
        elif r["type"] in (0xF00B, 0xF122):
            o, cnt = r["start"], r["inst"]
            props, cx = [], r["start"] + 6 * cnt
            for i in range(cnt):
                opid, val = struct.unpack_from("<HI", doc, o)
                o += 6
                pidn = opid & 0x3FFF
                if opid & 0x8000:
                    props.append(f"{pidn:#x}=complex[{val}]")
                    cx += val
                else:
                    props.append(f"{pidn:#x}={val:#x}")
            print("  " * d + ("FOPT " if r["type"] == 0xF00B else "TFOPT ") + " ".join(props))
        elif r["type"] == 0x0FBA:
            print("  " * d + "CString " + doc[r["start"]:r["end"]].decode("utf-16le", "replace"))
        elif r["type"] in (0x0FAC, 0x0FA1, 0x0FA0, 0x0FA8, 0x138B, 0x0F9E):
            names = {0x0FAC: "StyleTextProp9", 0x0FA1: "StyleTextProp", 0x0FA0: "TextChars", 0x0FA8: "TextBytes", 0x138B: "BinaryTagData", 0x0F9E: "OutlineTextRef"}
            extra = ""
            if r["type"] == 0x0FA0:
                extra = repr(doc[r["start"]:r["end"]].decode("utf-16le", "replace")[:60])
            if r["type"] == 0x0FA8:
                extra = repr(doc[r["start"]:r["end"]].decode("latin-1")[:60])
            if r["type"] in (0x0FAC, 0x0FA1):
                extra = doc[r["start"]:r["end"]][:80].hex()
            print("  " * d + f"{names[r['type']]} len={r['end'] - r['start']} {extra}")

# blip store types
if pics:
    o, types = 0, []
    while o + 8 <= len(pics):
        b = rec(pics, o)
        types.append(f"{b['type']:#x}/{b['inst']:#x}")
        o = b["end"]
    print("Pictures:", types)
