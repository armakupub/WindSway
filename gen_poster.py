from PIL import Image, ImageDraw, ImageFont
import math
import os

SIZE = 512
SS = 4  # supersample factor; drawing happens at SIZE*SS, LANCZOS down
BG = (30, 30, 35)
GOLD = (220, 160, 40)
WHITE = (255, 255, 255)
TRUNK = (124, 84, 52)
TILT = 13  # degrees, tree tips right, away from the gusts

# Motif quotes the WMO "Windy" weather icon (worldweather.wmo.int icon 26),
# recolored to the house palette and deliberately not a 1:1 copy: gusts
# angled upward instead of horizontal, five-lobe crown, branch forking
# left instead of right. Icon is its own composition: the two gusts alone.


def rot(p, bx, by, deg):
    t = math.radians(deg)
    c, s = math.cos(t), math.sin(t)
    dx, dy = p[0] - bx, p[1] - by
    return (bx + dx * c - dy * s, by + dx * s + dy * c)


def disc(draw, p, r, color):
    draw.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=color)


def stamp(draw, pts, widths, color):
    # Dense disc-stamped stroke: round caps for free, none of the joint
    # artifacts PIL's draw.line produces on thick polylines.
    for (x, y), w in zip(pts, widths):
        disc(draw, (x, y), w / 2, color)


def qbez(p0, p1, p2, n):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                    u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))
    return out


def draw_gust(draw, tail, angle_deg, length, curl_r, width, color, s=1.0):
    # Straight run flowing tangentially into an open overhead curl; the tip
    # tapers over the last third and stops clear of the run (WMO anatomy).
    length, curl_r, width = length * s, curl_r * s, width * s
    pts, prog = [], []
    n1 = max(2, int(length / 2))
    for i in range(n1 + 1):
        pts.append((length * i / n1, 0.0))
        prog.append(0.0)
    n2 = 340
    sweep = math.radians(300)
    for i in range(1, n2 + 1):
        t = i / n2
        ang = math.radians(90) - sweep * t
        r = curl_r * (1.0 - 0.24 * t)
        pts.append((length + r * math.cos(ang), -curl_r + r * math.sin(ang)))
        prog.append(t)
    widths = [width if t < 0.68 else
              width * (1.0 - 0.55 * (t - 0.68) / 0.32) for t in prog]
    a = math.radians(angle_deg)
    c, sn = math.cos(a), math.sin(a)
    pts = [(tail[0] + x * c - y * sn, tail[1] + x * sn + y * c) for x, y in pts]
    stamp(draw, pts, widths, color)


def draw_tree(draw, bx, by, s, tilt):
    # Clover crown: five visible lobes + center filler (no background slivers).
    lobes = [
        ((0, -262), 66),
        ((-74, -216), 56),
        ((72, -220), 58),
        ((-42, -156), 54),
        ((48, -158), 56),
        ((0, -205), 58),
    ]
    for (dx, dy), r in lobes:
        c = rot((bx + dx * s, by + dy * s), bx, by, tilt)
        disc(draw, c, r * s, GOLD)

    # Curved trunk stamped along the bow, tip vanishing pointed in the crown.
    Ht, bow = 215.0, 14.0
    n = 220
    pts, widths = [], []
    for i in range(n + 1):
        t = i / n
        x, y = bow * t * t, -Ht * t
        w = -30.0 * t * t + t + 34.0  # smooth 34 -> 5, no kink
        pts.append(rot((bx + x * s, by + y * s), bx, by, tilt))
        widths.append(w * s)
    stamp(draw, pts, widths, TRUNK)

    # Long sickle branch up-left, crotch inside the crown, tapering to a
    # point; short pointed stub up-right.
    bpts = [rot((bx + x * s, by + y * s), bx, by, tilt)
            for x, y in qbez((4, -145), (-68, -150), (-100, -206), 150)]
    bw = [(19 - (19 - 3) * (i / 150)) * s for i in range(151)]
    stamp(draw, bpts, bw, TRUNK)
    qpts = [rot((bx + x * s, by + y * s), bx, by, tilt)
            for x, y in qbez((10, -168), (42, -176), (60, -200), 90)]
    qw = [(13 - (13 - 2.5) * (i / 90)) * s for i in range(91)]
    stamp(draw, qpts, qw, TRUNK)


def draw_title(draw, scale):
    try:
        font = ImageFont.truetype("arial.ttf", 44 * scale)
    except Exception:
        font = ImageFont.load_default()
    t1, t2 = "Wind", "Sway"
    b1 = draw.textbbox((0, 0), t1, font=font)
    b2 = draw.textbbox((0, 0), t2, font=font)
    th = b1[3] - b1[1]
    W = SIZE * scale
    draw.text(((W - (b1[2] - b1[0])) / 2, 410 * scale), t1, fill=WHITE, font=font)
    draw.text(((W - (b2[2] - b2[0])) / 2, 410 * scale + th + 12 * scale),
              t2, fill=WHITE, font=font)


out_dir = os.path.dirname(os.path.abspath(__file__))

img = Image.new("RGB", (SIZE * SS, SIZE * SS), BG)
d = ImageDraw.Draw(img)
draw_gust(d, (36 * SS, 162 * SS), -9, 138, 30, 19, WHITE, s=SS)
draw_gust(d, (26 * SS, 256 * SS), -9, 114, 26, 17, WHITE, s=SS)
draw_tree(d, 295 * SS, 380 * SS, SS, TILT)
draw_title(d, SS)
img.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(out_dir, "poster.png"))
print("Saved poster.png")

# Icon: the two gusts alone, white over gold, rendered at 512 -> 32 LANCZOS.
ICON_RENDER, ICON_OUT = 512, 32
icon = Image.new("RGB", (ICON_RENDER, ICON_RENDER), BG)
di = ImageDraw.Draw(icon)
draw_gust(di, (140, 175), -9, 200, 64, 54, WHITE)
draw_gust(di, (26, 425), -9, 290, 78, 58, GOLD)
icon.resize((ICON_OUT, ICON_OUT), Image.LANCZOS).save(
    os.path.join(out_dir, "icon.png"))
print("Saved icon.png")
