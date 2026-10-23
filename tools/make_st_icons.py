import io, re, sys, glob, os
import cairosvg
from PIL import Image, ImageDraw
SRC, OUT = sys.argv[1], sys.argv[2]
for f in glob.glob(f"{SRC}/*.svg"):
    name = os.path.basename(f)[:-4]
    svg = open(f).read()
    if False:
        svg = re.sub(r'<svg ', '<svg fill="#FF0000" ', svg, count=1)
        png = cairosvg.svg2png(bytestring=svg.encode(), output_width=192, output_height=192)
        logo = Image.open(io.BytesIO(png)).convert("RGBA")
        # white play triangle under the cut-out, as on the official logo
        base = Image.new("RGBA", logo.size, (0, 0, 0, 0))
        ImageDraw.Draw(base).polygon([(75, 66), (75, 126), (128, 96)], fill=(255, 255, 255, 255))
        base.alpha_composite(logo)
        base.save(f"{OUT}/yt_logo.png")
        continue
    svg = re.sub(r'<svg ', '<svg fill="#ffffff" ', svg, count=1)
    svg = re.sub(r'fill="(?!#ffffff)[^"]*"', 'fill="#ffffff"', svg)
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=96, output_height=96)
    Image.open(io.BytesIO(png)).convert("RGBA").save(f"{OUT}/st_{name}.png")
print("ok")
