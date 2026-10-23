#!/usr/bin/env python3
"""
Pre-downloads a varied set of Poly Haven 360 panoramas (all CC0, https://polyhaven.com/license)
into the APK's assets, with a preview thumbnail each and a manifest (name, authors, licence,
source, ambient colour). Usage: fetch_environments.py <hdris.json> <assets/environments dir> [count]
"""
import io, json, os, re, sys, time, urllib.request
from PIL import Image

UA = "HyperXR-build (+https://github.com/mt-VR/HyperXR)"
# (category, count, tags that disqualify): photo studios only count once, so "indoor" brings real rooms
WANT = [("nature", 3, ()), ("urban", 3, ("indoor",)), ("indoor", 3, ("studio",)), ("night", 2, ()),
        ("sunrise-sunset", 2, ()), ("studio", 1, ()), ("skies", 2, ())]

def family(k):
    """Variants of one shoot (satara_night / satara_night_no_lamps, studio_small_03 / _08) share a family."""
    k = re.sub(r"_(no_lamps|puresky|clear|cloudy)$", "", k)
    return re.sub(r"_\d+[a-z]?$", "", k).split("_")[0]

def get(url, tries=4):
    for t in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.read()
        except Exception as e:
            if t == tries - 1: raise
            time.sleep(3 * (t + 1))

def main():
    catalog = json.load(open(sys.argv[1])); out = sys.argv[2]
    os.makedirs(os.path.join(out, "thumbs"), exist_ok=True)
    ranked = sorted(catalog.items(), key=lambda kv: -kv[1].get("download_count", 0))
    chosen = []
    for cat, n, bad in WANT:
        for k, v in ranked:
            if n == 0: break
            cats = v.get("categories", [])
            if cat in cats and not any(b in cats for b in bad) and family(k) not in [family(c[0]) for c in chosen] \
                    and not (cat != "studio" and "studio" in cats):
                chosen.append((k, v)); n -= 1
    keep = {f"ph_{k}.jpg" for k, _ in chosen}
    for d in (out, os.path.join(out, "thumbs")):
        for f in os.listdir(d):
            if f.startswith("ph_") and f not in keep: os.remove(os.path.join(d, f))
    manifest = []
    for k, v in chosen:
        dst = os.path.join(out, f"ph_{k}.jpg")
        if os.path.isfile(dst):  # already fetched by an earlier run
            img = Image.open(dst).convert("RGB")
        else:
            files = json.loads(get(f"https://api.polyhaven.com/files/{k}"))
            img = Image.open(io.BytesIO(get(files["tonemapped"]["url"]))).convert("RGB")
            img.resize((4096, 2048), Image.LANCZOS).save(dst, quality=86, optimize=True, progressive=False)
        img.resize((512, 256), Image.LANCZOS).save(os.path.join(out, "thumbs", f"ph_{k}.jpg"), quality=82)
        small = img.resize((32, 16), Image.BILINEAR); px = list(small.getdata())
        avg = [round(sum(p[i] for p in px) / len(px) / 255, 3) for i in range(3)]
        manifest.append({"id": k, "name": v.get("name", k), "authors": list(v.get("authors", {}).keys()) or ["Poly Haven"],
                         "categories": v.get("categories", []), "license": "CC0-1.0", "source": f"https://polyhaven.com/a/{k}", "ambient": avg})
        print("ok", k, flush=True)
    json.dump(manifest, open(os.path.join(out, "polyhaven.json"), "w"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    main()
