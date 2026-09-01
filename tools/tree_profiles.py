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
and, over the leaf band of leafy non-rigid sprites, the painted leaf structure:
  r50 r20           radii in px where the luminance autocorrelation drops
                    below 0.5 / 0.2
  blob              coherent shape size in px: luminance quantised to five
                    levels, 4-connected patches of at least 2 px, the
                    area-weighted median of sqrt(area)

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
                        yield name, np.array(img.crop((x, y, x + w, y + h))), ox, oy, fx, fy
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


def _rings():
    out = []
    for r in range(48):
        dy, dx = [], []
        for y in range(-r, r + 1):
            for x in range(-r, r + 1):
                if round((x * x + y * y) ** 0.5) == r:
                    dy.append(y)
                    dx.append(x)
        out.append((np.array(dy), np.array(dx)))
    return out


RINGS = _rings()


# Radially averaged autocorrelation of the mean-free luminance, normalised by
# the overlap count per shift (Sandbox/pz-tree-inventory/measure_plants.py).
def autocorr_radii(lum, mask):
    lum = np.where(mask, lum - lum[mask].mean(), 0.0)
    h, w = lum.shape
    f = np.fft.rfft2(lum, s=(2 * h, 2 * w))
    ac = np.fft.fftshift(np.fft.irfft2(f * np.conj(f), s=(2 * h, 2 * w)))
    fm = np.fft.rfft2(mask.astype(np.float32), s=(2 * h, 2 * w))
    cnt = np.fft.fftshift(np.fft.irfft2(fm * np.conj(fm), s=(2 * h, 2 * w)))
    cy, cx = h, w
    ac0 = ac[cy, cx] / max(cnt[cy, cx], 1.0)
    if ac0 <= 0:
        return 0.0, 0.0
    radial = []
    for dy, dx in RINGS:
        y, x = cy + dy, cx + dx
        ok = (y >= 0) & (y < 2 * h) & (x >= 0) & (x < 2 * w)
        y, x = y[ok], x[ok]
        c = cnt[y, x]
        ring = c > 20
        radial.append(np.mean(ac[y[ring], x[ring]] / c[ring]) / ac0 if ring.any() else 0.0)
    radial = np.array(radial)

    def first_below(t):
        idx = np.where(radial < t)[0]
        return float(idx[0]) if len(idx) else 48.0

    return first_below(0.5), first_below(0.2)


# Runs of equal level per row, unioned with the overlapping runs of the row
# above (4-connected labelling without scipy).
def _components(level, w):
    pad = np.full((level.shape[0], w + 1), -1, dtype=np.int32)
    pad[:, :w] = level
    flat = pad.ravel()
    cut = np.flatnonzero(flat[1:] != flat[:-1]) + 1
    start = np.concatenate(([0], cut))
    end = np.concatenate((cut, [flat.size]))
    keep = flat[start] >= 0
    start, end = start[keep], end[keep]
    if not len(start):
        return []
    lvl = flat[start].tolist()
    row = (start // (w + 1)).tolist()
    x0 = (start % (w + 1)).tolist()
    size = (end - start).tolist()
    x1 = [a + n for a, n in zip(x0, size)]
    parent = list(range(len(x0)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    n = len(x0)
    i = 0
    prev = (0, 0)
    while i < n:
        j = i
        while j < n and row[j] == row[i]:
            j += 1
        p, pe = prev if i and row[i - 1] == row[i] - 1 else (0, 0)
        c = i
        while p < pe and c < j:
            if x1[p] <= x0[c]:
                p += 1
            elif x1[c] <= x0[p]:
                c += 1
            else:
                if lvl[p] == lvl[c]:
                    a, b = find(p), find(c)
                    if a != b:
                        parent[max(a, b)] = min(a, b)
                if x1[p] < x1[c]:
                    p += 1
                else:
                    c += 1
        prev = (i, j)
        i = j
    area = {}
    for i in range(n):
        r = find(i)
        area[r] = area.get(r, 0) + size[i]
    return list(area.values())


# Area-weighted median edge length of the coherent luminance patches.
def blob_size(lum, mask):
    v = lum[mask]
    lo, hi = float(v.min()), float(v.max())
    if hi <= lo:
        return 0.0
    level = np.where(mask, np.clip((lum - lo) / (hi - lo) * 5, 0, 4.999).astype(np.int32), -1)
    areas = np.array([a for a in _components(level, lum.shape[1]) if a >= 2], dtype=np.float64)
    if not len(areas):
        return 0.0
    order = np.argsort(areas)
    areas = areas[order]
    cum = np.cumsum(areas)
    return float(np.sqrt(areas[int(np.searchsorted(cum, cum[-1] / 2.0))]))


def texture(rgba, leaf_top, leaf_bottom):
    if leaf_top < 0 or leaf_bottom < leaf_top:
        return 0.0, 0.0, 0.0
    a = rgba[leaf_top:leaf_bottom + 1].astype(np.float32)
    m = a[..., 3] > ALPHA_THR
    ys = np.where(m.any(axis=1))[0]
    xs = np.where(m.any(axis=0))[0]
    if len(ys) < 8 or len(xs) < 8:
        return 0.0, 0.0, 0.0
    a = a[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    m = a[..., 3] > ALPHA_THR
    lum = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    r50, r20 = autocorr_radii(lum, m)
    return r50, r20, blob_size(lum, m)


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


# Summer sprite of the same tree for a seasonal overlay (density reference);
# bases, evergreens and pieces reference themselves.
def summer_ref(species, kind, idx):
    if species in EVERGREEN:
        return idx
    if kind == "JUMBO":
        return 6 + idx % 2 if idx >= 4 else idx
    if kind:
        return 3 if 2 <= idx <= 5 else 9 if 8 <= idx <= 11 else idx
    return 12 + idx % 4 if idx >= 8 else idx


def main():
    types = wind_types()
    rows = []
    pixels = {}
    for name, rgba, ox, oy, fx, fy in iter_sprites():
        alpha = rgba[:, :, 3]
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
        pixels[name] = int((alpha > ALPHA_THR).sum())
        ref = f"e_{species}{kind}_1_{summer_ref(species, kind, idx)}"
        r50, r20, blob = texture(rgba, leaf_top, leaf_bottom) if leafy and not rigid else (0.0, 0.0, 0.0)
        rows.append((name, ref, f"{name} {oy + base} {oy + top} {crown_frac:.3f} {int(leafy)} {lt} {lb} {int(rigid)} {oy + stub_top} {types.get(name, 1)}",
                     f"{r50:.1f} {r20:.1f} {blob:.1f}"))
    lines = []
    for name, ref, line, tex in rows:
        density = pixels[name] / pixels[ref] if pixels.get(ref) else 1.0
        lines.append(f"{line} {min(density, 1.0):.3f} {tex}")
    lines.sort()
    with open(OUT, "w", newline="\n") as f:
        f.write("# name baseRow topRow crownHeightFrac leafy leafTopRow leafBottomRow rigid stubTopRow windType density r50 r20 blob (frame rows, alpha>96; density = opaque area / the tree's summer sprite; r50/r20 = luminance autocorrelation radii, blob = area-weighted median patch size, px over the leaf band, 0 unless leafy and not rigid)\n")
        f.write("\n".join(lines) + "\n")
    print(f"{len(lines)} sprites -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
