"""Compose the README hero (screenshots/hero.png): four HA100 pages, Media and Zones fanned behind BÆOSOUND.

    python3 scripts/make_hero.py . screenshots/hero.png   (needs Pillow)
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(sys.argv[1])
OUT = Path(sys.argv[2])
SHOTS = REPO / "screenshots"
FONTS = REPO / "app/src/main/res/font"

W, H = 2640, 1160
BG_OUT = (27, 36, 45)
INK, MUTE, LINE = (241, 236, 234), (169, 186, 182), (58, 74, 88)
BODY = (13, 19, 25)


def font(name, size):
    return ImageFont.truetype(str(FONTS / f"{name}.ttf"), size)


def background():
    return Image.new("RGB", (W, H), BG_OUT)


def rounded_mask(size, r):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), r, fill=255)
    return m


def shadow(canvas, box, r, blur=38, alpha=170, dy=26):
    return  # flat look: no drop shadows
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    x0, y0, x1, y1 = box
    ImageDraw.Draw(layer).rounded_rectangle((x0, y0 + dy, x1, y1 + dy), r, fill=(0, 0, 0, alpha))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def phone(canvas, shot, x, y, scale, crop_h=None, fade=False, dim=0.0, bezel=20, speaker=True):
    """An HA100 body with the screenshot inset. Returns the body box."""
    img = Image.open(shot).convert("RGB")
    if crop_h:
        img = img.crop((0, 0, img.width, crop_h))
    sw, sh = int(img.width * scale), int(img.height * scale)
    img = img.resize((sw, sh), Image.LANCZOS)
    if dim:
        img = Image.blend(img, Image.new("RGB", img.size, (0, 0, 0)), dim)
    top = bezel + (16 if speaker else 0)
    bw, bh = sw + bezel * 2, sh + top + bezel
    r_body, r_screen = int(52 * scale + 8), int(22 * scale + 4)
    shadow(canvas, (x, y, x + bw, y + bh), r_body)

    body = Image.new("RGBA", (bw, bh), BODY + (255,))
    d = ImageDraw.Draw(body)
    if speaker:
        d.rounded_rectangle((bw // 2 - 34, 12, bw // 2 + 34, 17), 3, fill=(46, 58, 70))
    body.paste(img, (bezel, top), rounded_mask((sw, sh), r_screen))
    if fade:
        # Cropped cards fade into the body instead of ending on a hard line.
        g = Image.new("L", (1, bh), 0)
        for i in range(bh):
            g.putpixel((0, i), max(0, min(255, int((i - bh * 0.62) / (bh * 0.38) * 255))))
        body = Image.composite(Image.new("RGBA", (bw, bh), BODY + (255,)), body, g.resize((bw, bh)))
    rim = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    ImageDraw.Draw(rim).rounded_rectangle((0, 0, bw - 1, bh - 1), r_body, outline=LINE + (255,), width=2)
    body.alpha_composite(rim)
    canvas.paste(body, (x, y), rounded_mask((bw, bh), r_body))
    canvas.alpha_composite(rim, (x, y))
    return x, y, x + bw, y + bh


def remote(canvas, shot, x, y, scale):
    """The top of a real HA100: silver rim, glossy black front, the screen and the
    first row of keys, fading out. Returns the screen box and body height."""
    img = Image.open(shot).convert("RGB")
    sw, sh = int(img.width * scale), int(img.height * scale)
    img = img.resize((sw, sh), Image.LANCZOS)
    side, top_m, below, rim = int(sw * 0.15), int(sw * 0.12), int(sw * 0.36), 9
    bw, bh = sw + side * 2, top_m + sh + below
    th = bh + 160  # drawn taller and cut, so the bottom corners never show
    pad = 80
    layer = Image.new("RGBA", (bw + pad * 2, th + pad * 2), (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle((pad, pad + 26, pad + bw, pad + th + 26), 46, fill=(0, 0, 0, 0))

    body = Image.new("RGBA", (bw, th), (0, 0, 0, 0))
    # Aluminium rim, flat.
    body.paste(Image.new("RGB", (bw, th), (150, 156, 162)), (0, 0), rounded_mask((bw, th), 48))
    fw, fh = bw - rim * 2, th - rim * 2
    front = Image.new("RGBA", (fw, fh), (9, 11, 14, 255))
    fd = ImageDraw.Draw(front)
    sx, sy = side - rim, top_m - rim
    fd.rounded_rectangle((sx - 4, sy - 4, sx + sw + 4, sy + sh + 4), 10, fill=(3, 4, 6, 255))
    front.paste(img, (sx, sy), rounded_mask((sw, sh), 6))
    # Keys under the screen: a row of three, then the round D-pad.
    key, ring = (20, 24, 29, 255), (44, 51, 59, 255)
    ky = sy + sh + int(sw * 0.075)
    kw, kh, gapk = int(sw * 0.24), int(sw * 0.075), int(sw * 0.07)
    kx = (fw - (kw * 3 + gapk * 2)) // 2
    for i in range(3):
        x0 = kx + i * (kw + gapk)
        fd.rounded_rectangle((x0, ky, x0 + kw, ky + kh), kh // 2, fill=key, outline=ring, width=2)
        fd.rounded_rectangle((x0 + kw // 2 - 10, ky + kh // 2 - 2, x0 + kw // 2 + 10, ky + kh // 2 + 1), 2, fill=(95, 104, 112, 255))
    body.paste(front, (rim, rim), rounded_mask((fw, fh), 40))
    body = body.crop((0, 0, bw, bh))

    # A hard cut: the remote carries on below the picture.
    fade = Image.new("L", (1, bh), 255)
    start = bh
    for i in range(start, bh):
        fade.putpixel((0, i), max(0, int(255 * (1 - (i - start) / (bh - start)))))
    fade = fade.resize((bw, bh))
    a = body.getchannel("A")
    body.putalpha(Image.composite(a, Image.new("L", (bw, bh), 0), fade))
    full = Image.new("L", layer.size, 0)
    full.paste(Image.new("L", (bw + pad * 2, pad + bh - 26), 255), (0, 0))
    full = full.filter(ImageFilter.GaussianBlur(14))
    layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", layer.size, 0), full))
    canvas.alpha_composite(layer, (x - pad, y - pad))
    canvas.alpha_composite(body, (x, y))
    return x + side, y + top_m, x + side + sw, y + top_m + sh, bh


def wordmark(draw, cx, y, rest, size, color, tracking=0.32, bold_prefix="BÆO"):
    """BÆO in Syne Bold, the rest in Syne Regular, letter-spaced and centred on cx."""
    fb, fr = font("syne_700", size), font("syne_400", size)
    parts = [(c, fb) for c in bold_prefix] + [(c, fr) for c in rest]
    gap = size * tracking
    widths = [draw.textlength(c, font=f) for c, f in parts]
    total = sum(widths) + gap * (len(parts) - 1)
    x = cx - total / 2
    for (c, f), w in zip(parts, widths):
        draw.text((x, y), c, font=f, fill=color)
        x += w + gap


def main():
    canvas = background().convert("RGBA")
    d = ImageDraw.Draw(canvas)

    wordmark(d, W / 2, 70, "REMOTE", 118, INK, tracking=0.36)
    sub = "A native Home Assistant dashboard for the Astrion HA100 remote"
    fs = font("manrope_500", 44)
    d.text(((W - d.textlength(sub, font=fs)) / 2, 222), sub, font=fs, fill=MUTE)

    scale, y = 0.76, 300
    sw = int(480 * scale)
    bw = sw + int(sw * 0.15) * 2
    gap, fan = 70, 300
    x0 = (W - (bw * 4 + gap * 3 + fan)) // 2
    xs = [x0, x0 + bw + gap, x0 + (bw + gap) * 2, x0 + (bw + gap) * 3 + fan]
    top = y + int(sw * 0.12)

    # The two cards fanned behind BÆOSOUND: drawn first so the remote covers them.
    mini = 0.7
    sx = xs[2]
    phone(canvas, SHOTS / "sonos-media.png", sx + bw - 110, top - 12, mini, crop_h=500, fade=False, dim=0.10, bezel=12, speaker=False)
    phone(canvas, SHOTS / "sonos-group.png", sx + bw - 50, top - 12 + 372, mini, crop_h=480, fade=False, dim=0.04, bezel=12, speaker=False)

    pages = [
        ("main.png", "Floorplan, scenes and the door"),
        ("tv-page.png", "Plex and the Google TV"),
        ("media-page.png", "Player · Media · Zones"),
        ("climate.png", "Aircon, fan and blinds"),
    ]
    fcap = font("manrope_500", 28)
    for i, ((shot, caption), x) in enumerate(zip(pages, xs)):
        box = remote(canvas, SHOTS / shot, x, y, scale)
        cx = (box[0] + box[2]) / 2 + (fan / 2 - 40 if i == 2 else 0)
        d.text((cx - d.textlength(caption, font=fcap) / 2, y + box[4] + 6), caption, font=fcap, fill=MUTE)

    canvas.convert("RGB").save(OUT, optimize=True)


main()
