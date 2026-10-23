import io, re, sys
import cairosvg
from PIL import Image, ImageFilter, ImageDraw
SRC = sys.argv[1]; OUT = sys.argv[2]
S = 216
icons = {
    # key: (svg file, top-left colour, bottom-right colour, glyph scale)
    "importapk":   ("build.svg",          "#35c98a", "#1b7fa0", 0.50),
    "opengames":   ("sports_esports.svg", "#8a5cff", "#e040c8", 0.56),
    "youtube":     ("brand_youtube.svg",  "#ff2a1f", "#c20010", 0.58),
    "emulators":   ("videogame_asset.svg","#ffa23c", "#ff4e50", 0.56),
    "androidapps": ("android.svg",        "#5be38f", "#0f9d58", 0.56),
    "handlab":     ("back_hand.svg",      "#3ad0e0", "#5677e8", 0.52),
    "appworld":    ("view_in_ar.svg",     "#40c9a2", "#7a52ff", 0.52),
    "assistant":   ("auto_awesome.svg",   "#f45aa8", "#7a4dff", 0.52),
    "screenshare": ("screen_share.svg",   "#4fb0ff", "#2b6be0", 0.52),
    "workspaces":  ("dashboard.svg",      "#71808f", "#3a4452", 0.50),
    "reality":     ("visibility.svg",     "#f8b733", "#f2552a", 0.54),
    "catgame":     ("pets.svg",           "#ffb88c", "#d95f6c", 0.52),
    "twitch":      ("brand_twitch.svg",   "#a06bff", "#7a3cf0", 0.50),
    "tiktok":      ("brand_tiktok.svg",   "#2c2c2e", "#0c0c0d", 0.50),
    "hyperx_package": ("extension.svg",   "#8e9eab", "#566373", 0.50),
}
def hexc(h): return tuple(int(h[i:i+2], 16) for i in (1, 3, 5))
def gradient(a, b):
    a = hexc(a); b = hexc(b)
    im = Image.new("RGBA", (S, S))
    px = im.load()
    for y in range(S):
        for x in range(S):
            t = (x + y) / (2 * (S - 1))
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)
    return im
def glyph(svgfile, scale):
    svg = open(f"{SRC}/{svgfile}").read()
    # white fill (Material Symbols and Simple Icons are single-colour paths)
    svg = re.sub(r'<svg ', '<svg fill="#ffffff" ', svg, count=1)
    svg = re.sub(r'fill="(?!#ffffff)[^"]*"', 'fill="#ffffff"', svg)
    size = int(S * scale)
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    g = Image.open(io.BytesIO(png)).convert("RGBA")
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # soft shadow under the glyph, like the provided icons
    sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    a = g.split()[3].point(lambda v: v * 0.28)
    shadow = Image.new("RGBA", g.size, (0, 0, 0, 255)); shadow.putalpha(a)
    sh.paste(shadow, ((S - size) // 2, (S - size) // 2 + 5), shadow)
    sh = sh.filter(ImageFilter.GaussianBlur(5))
    out.alpha_composite(sh)
    out.alpha_composite(g, ((S - size) // 2, (S - size) // 2))
    return out
preview = Image.new("RGBA", (len(icons) * (S + 10), S), (40, 40, 40, 255))
for i, (key, (f, c1, c2, sc)) in enumerate(icons.items()):
    bg = gradient(c1, c2); fg = glyph(f, sc)
    bg.save(f"{OUT}/ic_app_{key}_bg.png"); fg.save(f"{OUT}/ic_app_{key}_fg.png")
    comp = bg.copy(); comp.alpha_composite(fg)
    m = Image.new("L", (S, S), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, S - 1, S - 1), radius=48, fill=255)
    preview.paste(comp, (i * (S + 10), 0), m)
preview.resize((preview.width // 2, preview.height // 2)).save("icons_new_preview.png")
print("done", len(icons))
