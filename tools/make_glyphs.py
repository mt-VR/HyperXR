# White Material Symbols (Apache-2.0) glyphs for Canvas-drawn panels: python3 make_glyphs.py SVG_DIR OUT_DIR
import io, re, sys, os
import cairosvg
SRC, OUT = sys.argv[1], sys.argv[2]
S = 96
for f in sorted(os.listdir(SRC)):
    if not f.endswith(".svg"): continue
    svg = open(os.path.join(SRC, f)).read()
    svg = re.sub(r'<svg ', '<svg fill="#ffffff" ', svg, count=1)
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=S, output_height=S)
    open(os.path.join(OUT, "glyph_" + f[:-4] + ".png"), "wb").write(png)
    print(f)
