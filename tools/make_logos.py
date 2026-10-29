# Logos, icons and store covers of HyperMagic's own games, drawn here (Inter font, OFL).
# python3 make_logos.py OUT_DIR  -> ic_app_qg_<id>_bg/fg.png (216) and cover_qg_<id>.png (1280x720)
import sys, random, math
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

OUT = sys.argv[1]
BLACK = "/usr/share/fonts/opentype/inter/Inter-BlackItalic.otf"
BOLD = "/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf"

def grad(w, h, top, bottom):
    im = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(im)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    return im

def outlined_text(size, text, font, fill, outline, stroke, depth=0, depth_color=None, angle=0):
    f = ImageFont.truetype(font, size)
    l, t, r, b = f.getbbox(text, stroke_width=stroke)
    w, h = r - l + depth + 20, b - t + depth + 20
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for k in range(depth, 0, -1):  # chunky 3D extrusion
        d.text((10 - l + k, 10 - t + k), text, font=f, fill=depth_color, stroke_width=stroke, stroke_fill=depth_color)
    d.text((10 - l, 10 - t), text, font=f, fill=fill, stroke_width=stroke, stroke_fill=outline)
    return im.rotate(angle, resample=Image.BICUBIC, expand=True) if angle else im

def leaves(im, n, seed, colors):
    rnd = random.Random(seed)
    d = ImageDraw.Draw(im)
    w, h = im.size
    for _ in range(n):
        x = rnd.choice([rnd.uniform(-0.1, 0.25), rnd.uniform(0.75, 1.1)]) * w
        y = rnd.uniform(-0.1, 1.1) * h
        rx, ry = rnd.uniform(0.04, 0.09) * w, rnd.uniform(0.015, 0.03) * w
        a = rnd.uniform(0, math.pi)
        pts = [(x + math.cos(a) * rx * math.cos(t) - math.sin(a) * ry * math.sin(t), y + math.sin(a) * rx * math.cos(t) + math.cos(a) * ry * math.sin(t))
               for t in [i / 20 * 2 * math.pi for i in range(20)]]
        d.polygon(pts, fill=rnd.choice(colors))

def paste_center(base, img, cy=0.5, scale=1.0):
    if scale != 1.0:
        img = img.resize((int(img.width * scale), int(img.height * scale)), Image.LANCZOS)
    base.alpha_composite(img, ((base.width - img.width) // 2, int(base.height * cy - img.height / 2)))

def fit(img, maxw):
    s = min(1.0, maxw / img.width)
    return img.resize((int(img.width * s), int(img.height * s)), Image.LANCZOS)

# ---------------------------------------------------------------- Macacada
def macacada_bg(w, h):
    im = grad(w, h, (64, 150, 60), (14, 60, 30))
    leaves(im, int(w / 12), 3, [(30, 110, 40, 255), (46, 140, 50, 255), (20, 80, 30, 255)])
    return im.filter(ImageFilter.GaussianBlur(w / 400))

def macacada_logo(size, two_lines=False):
    lines = ["MACA", "CADA"] if two_lines else ["MACACADA"]
    parts = [outlined_text(size, t, BLACK, (190, 255, 70), (25, 40, 15), max(4, size // 14), depth=max(3, size // 12), depth_color=(20, 70, 20), angle=4) for t in lines]
    w = max(p.width for p in parts); h = sum(p.height for p in parts) - (len(parts) - 1) * size // 5
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0)); y = 0
    for p in parts: im.alpha_composite(p, ((w - p.width) // 2, y)); y += p.height - size // 5
    return im

# ---------------------------------------------------------------- Babuíno
def babuino_bg(w, h):
    im = grad(w, h, (10, 12, 26), (2, 3, 6))
    d = ImageDraw.Draw(im)
    # blood moon behind fog
    r = int(h * 0.22); cx, cy = int(w * 0.72), int(h * 0.3)
    moon = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(moon).ellipse([cx - r, cy - r, cx + r, cy + r], fill=(170, 30, 25, 255))
    im.alpha_composite(moon.filter(ImageFilter.GaussianBlur(r / 10)))
    # dead trees
    rnd = random.Random(9)
    for i in range(14):
        x = rnd.uniform(0, w); tw = rnd.uniform(0.01, 0.025) * w
        d.rectangle([x, h * rnd.uniform(0.15, 0.4), x + tw, h], fill=(4, 5, 8, 255))
        for _ in range(3):
            y = rnd.uniform(0.3, 0.7) * h; dx = rnd.choice([-1, 1]) * rnd.uniform(0.05, 0.12) * w
            d.line([(x + tw / 2, y), (x + tw / 2 + dx, y - abs(dx) * 0.6)], fill=(4, 5, 8, 255), width=max(2, int(tw / 3)))
    fog = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    fd = ImageDraw.Draw(fog)
    for i in range(30):
        y = rnd.uniform(0.6, 1.0) * h
        x0 = rnd.uniform(-0.2, 0.6) * w; fd.ellipse([x0, y, x0 + rnd.uniform(0.3, 0.8) * w, y + h * 0.12], fill=(90, 100, 120, 25))
    im.alpha_composite(fog.filter(ImageFilter.GaussianBlur(h / 40)))
    return im

def babuino_logo(size, sub=True):
    main = outlined_text(size, "BABUÍNO", BLACK, (232, 226, 214), (60, 0, 0), max(3, size // 18))
    # scratches through the letters
    sc = ImageDraw.Draw(main); rnd = random.Random(4)
    for _ in range(9):
        x = rnd.uniform(0, main.width); y = rnd.uniform(0, main.height)
        sc.line([(x, y), (x + rnd.uniform(-60, 60), y + rnd.uniform(20, 70))], fill=(0, 0, 0, 0), width=max(2, size // 30))
    glow = Image.new("RGBA", (main.width + 80, main.height + 80), (0, 0, 0, 0))
    red = Image.new("RGBA", main.size, (220, 20, 20, 255)); red.putalpha(main.split()[3])
    glow.alpha_composite(red, (40, 40)); glow = glow.filter(ImageFilter.GaussianBlur(size / 8))
    glow.alpha_composite(main, (40, 40))
    if not sub: return glow
    s = outlined_text(int(size * 0.36), "DA MEIA-NOITE", BOLD, (220, 40, 35), (20, 0, 0), max(2, size // 40))
    im = Image.new("RGBA", (max(glow.width, s.width), glow.height + s.height - size // 6), (0, 0, 0, 0))
    im.alpha_composite(glow, ((im.width - glow.width) // 2, 0))
    im.alpha_composite(s, ((im.width - s.width) // 2, glow.height - size // 6))
    return im

# ---------------------------------------------------------------- Expediente
def expediente_bg(w, h):
    im = grad(w, h, (255, 196, 70), (240, 110, 60))
    d = ImageDraw.Draw(im)
    # office tiles in perspective
    for i in range(-10, 11):
        d.line([(w / 2 + i * w * 0.02, h * 0.62), (w / 2 + i * w * 0.18, h)], fill=(255, 235, 200, 90), width=max(1, w // 300))
    for k in range(6):
        y = h * 0.62 + (h * 0.38) * (k / 6) ** 1.6
        d.line([(0, y), (w, y)], fill=(255, 235, 200, 90), width=max(1, w // 300))
    return im

def expediente_logo(size):
    t = outlined_text(size, "EXPEDIENTE", BLACK, (255, 255, 255), (60, 30, 90), max(4, size // 12), depth=max(3, size // 14), depth_color=(80, 40, 120), angle=-3)
    s = outlined_text(int(size * 0.3), "CHEFE ROBÔ · TURNO DAS 9H", BOLD, (60, 30, 90), (255, 255, 255), max(2, size // 40))
    im = Image.new("RGBA", (max(t.width, s.width), t.height + s.height), (0, 0, 0, 0))
    im.alpha_composite(t, ((im.width - t.width) // 2, 0)); im.alpha_composite(s, ((im.width - s.width) // 2, t.height - size // 10))
    return im

for key, bg, logo, icon_logo in [
    ("expediente", expediente_bg, lambda: expediente_logo(170), lambda: outlined_text(70, "EXP", BLACK, (255, 255, 255), (60, 30, 90), 6, depth=5, depth_color=(80, 40, 120), angle=-3)),
    ("macacada", macacada_bg, lambda: macacada_logo(170), lambda: macacada_logo(96, two_lines=True)),
    ("babuino", babuino_bg, lambda: babuino_logo(170), lambda: babuino_logo(80, sub=False)),
]:
    S = 216
    bg(S, S).save(f"{OUT}/ic_app_qg_{key}_bg.png")
    fg = Image.new("RGBA", (S, S), (0, 0, 0, 0)); paste_center(fg, fit(icon_logo(), S * 0.92))
    fg.save(f"{OUT}/ic_app_qg_{key}_fg.png")
    cover = bg(1280, 720); paste_center(cover, fit(logo(), 1280 * 0.86), cy=0.48)
    cover.convert("RGB").save(f"{OUT}/cover_qg_{key}.png", optimize=True)
    print("made", key)
