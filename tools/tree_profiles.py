"""Measure vanilla tree sprites and write resources/pzmod/windsway/tree_profiles.txt.

Reads the PZ texture packs directly (v0 sentinel format and v1 "PZPK"),
extracts every e_<species>[JUMBO|JUMBOXL|JUMBOXXL]_1_<n> sprite and derives
per sprite, from the alpha > 96 silhouette:
  base/top row      content extent in frame pixels
  crown fraction    lowest row whose span reaches half the max width
  leaf band         rows that are wide and dense (leaves, needles, blossom)
  leafy             foliage overlay by sheet index; evergreens (needles in
                    the base sprite): leaf band covers > 50% of its rows
                    and > 25% of the height
  rigid             XL/XXL cutaway trunk and burned-stump pieces (index >= 12)
  stub top          lowest row whose span exceeds a fifth of the max width
                    (rows below are the trunk stub and never flutter)
  wind type         WindType from the tile definitions (1 when absent)

Usage: python tools/tree_profiles.py [PZ_DIR]
Requires Pillow and numpy.
"""
import io
import os
import re
import struct
import sys

import numpy as np
from PIL import Image

PZ_DIR = sys.argv[1] if len(sys.argv) > 1 else r"C:\Games\Steam\steamapps\common\ProjectZomboid"
PACKS = ["Tiles2x.pack", "JumboTrees2x.pack", "JumboTreesBigs2x.pack"]
TILEDEFS = ["tiledefinitions_erosion.tiles.txt", "jumbo_trees.tiles.txt", "jumbo_trees_big.tiles.txt"]
TREE_RE = re.compile(r"^e_(?!newgrass)[a-z]+(JUMBO(XL|XXL)?)?_1_\d+$")
EVERGREEN = ("americanholly", "canadianhemlock", "virginiapine")
ALPHA_THR = 96
OUT = os.path.join(os.path.dirname(__file__), "..", "resources", "pzmod", "windsway", "tree_profiles.txt")


def read_int(f):
    return struct.unpack("<i", f.read(4))[0]


def read_str(f):
    return f.read(read_int(f)).decode("latin1")


def png_end(f, start):
    f.seek(start)
    assert f.read(8) == b"\x89PNG\r\n\x1a\n"
    while True:
        ln = struct.unpack(">I", f.read(4))[0]
        typ = f.read(4)
        f.seek(ln + 4, 1)
        if typ == b"IEND":
            return f.tell()


def iter_sprites():
    for pack in PACKS:
        path = os.path.join(PZ_DIR, "media", "texturepacks", pack)
        with open(path, "rb") as f:
            version = 0
            if f.read(4) == b"PZPK":
                version = read_int(f)
            else:
                f.seek(0)
            for _ in range(read_int(f)):
                read_str(f)
                n = read_int(f)
                read_int(f)
                entries = []
                for _ in range(n):
                    name = read_str(f)
                    entries.append((name, struct.unpack("<8i", f.read(32))))
                if version >= 1:
                    png_len = read_int(f)
                    png_start = f.tell()
                    end = png_start + png_len
                else:
                    png_start = f.tell()
                    end = png_end(f, png_start)
                wanted = [e for e in entries if TREE_RE.match(e[0])]
                if wanted:
                    f.seek(png_start)
                    img = Image.open(io.BytesIO(f.read(end - png_start))).convert("RGBA")
                    for name, (x, y, w, h, ox, oy, fx, fy) in wanted:
                        yield name, np.array(img.crop((x, y, x + w, y + h)))[:, :, 3], ox, oy, fx, fy
                f.seek(end)
                if version == 0:
                    buf = f.read(64)
                    idx = buf.find(b"\xef\xbe\xad\xde")
                    assert idx >= 0
                    f.seek(end + idx + 4)


def measure(alpha):
    mask = alpha > ALPHA_THR
    cnt = mask.sum(axis=1)
    ys = np.where(cnt > 0)[0]
    if len(ys) == 0:
        return None
    top, base = int(ys.min()), int(ys.max())
    height = base - top + 1
    span = np.zeros(mask.shape[0], dtype=int)
    dens = np.zeros(mask.shape[0])
    for y in ys:
        xs = np.where(mask[y])[0]
        span[y] = xs.max() - xs.min() + 1
        dens[y] = cnt[y] / span[y]
    max_w = int(span[top:base + 1].max())
    crown_start = base
    for y in range(base, top - 1, -1):
        if span[y] >= 0.5 * max_w:
            crown_start = y
            break
    crown_frac = (base - crown_start) / height
    stub_top = base
    for y in range(base, top - 1, -1):
        if span[y] > 0.2 * max_w:
            stub_top = y
            break
    leaf_rows = [y for y in range(top, base + 1) if span[y] > 0.4 * max_w and dens[y] > 0.55]
    if leaf_rows:
        leaf_bottom, leaf_top = max(leaf_rows), min(leaf_rows)
        leaf_frac = len(leaf_rows) / (leaf_bottom - leaf_top + 1)
        band_frac = (leaf_bottom - leaf_top + 1) / height
        leafy = leaf_frac > 0.5 and band_frac > 0.25
    else:
        leaf_top = leaf_bottom = -1
        leafy = False
    return base, top, crown_frac, leafy, leaf_top, leaf_bottom, stub_top


# Sheet layout: small bases 0-7 (bare, snow), foliage 8-23; JUMBO bases 0-3,
# foliage 4-11; XL/XXL base 0, snow 1, foliage 2-5, treetop halves 6-11
# (bare, snow, four seasons), trunk pieces 12-14, burned 15-16.
def overlay_index(kind, idx):
    if kind == "JUMBO":
        return 4 <= idx <= 11
    if kind:
        return 2 <= idx <= 5 or 8 <= idx <= 11
    return 8 <= idx <= 23


def wind_types():
    types = {}
    for fn in TILEDEFS:
        path = os.path.join(PZ_DIR, "media", fn)
        txt = open(path, encoding="utf-8", errors="ignore").read()
        for m in re.finditer(r"// ([a-zA-Z_0-9]+)\s*\n\s*tile\s*\{(.*?)\}", txt, re.S):
            wt = re.search(r"WindType = (\d)", m.group(2))
            if wt:
                types[m.group(1)] = int(wt.group(1))
    return types


def main():
    types = wind_types()
    lines = []
    for name, alpha, ox, oy, fx, fy in iter_sprites():
        m = measure(alpha)
        if m is None:
            continue
        base, top, crown_frac, leafy, leaf_top, leaf_bottom, stub_top = m
        fam = re.match(r"^e_([a-z]+)(JUMBO(?:XL|XXL)?)?_1_(\d+)$", name)
        species, kind, idx = fam.group(1), fam.group(2) or "", int(fam.group(3))
        rigid = kind in ("JUMBOXL", "JUMBOXXL") and idx >= 12
        if species not in EVERGREEN:
            leafy = overlay_index(kind, idx)
        lt = oy + leaf_top if leaf_top >= 0 else -1
        lb = oy + leaf_bottom if leaf_bottom >= 0 else -1
        lines.append(f"{name} {oy + base} {oy + top} {crown_frac:.3f} {int(leafy)} {lt} {lb} {int(rigid)} {oy + stub_top} {types.get(name, 1)}")
    lines.sort()
    with open(OUT, "w", newline="\n") as f:
        f.write("# name baseRow topRow crownHeightFrac leafy leafTopRow leafBottomRow rigid stubTopRow windType (frame rows, alpha>96)\n")
        f.write("\n".join(lines) + "\n")
    print(f"{len(lines)} sprites -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
