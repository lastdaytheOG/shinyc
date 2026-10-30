"""Builds the indexing benchmark's picture corpus from public COCO images (no private content).

  photos12/  camera-sized JPEGs  (4080x3060, about 12.5 MP, quality 92, film-grain-like noise so the
             file size and decode work are like a real camera's rather than an upscale's)
  photos2/   chat-app-sized JPEGs (1600x1200, quality 80)
  text12/    camera-sized COCO-Text images (scene text), to make the reader do real work
  screens/   1080x2400 PNGs of synthetic text screens with invented text (dark and light)

Deterministic: same seed, same files. For timing only: PIL does not shape Hindi, so these screens
are no use as a test of what is read; `PictureOcrMeasureDeviceTest -e ocr draw` draws those.

    make_corpus.py <out> <coco images> <coco-text images> [count]
"""
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

OUT = Path(sys.argv[1])
COCO = Path(sys.argv[2])
COCOTEXT = Path(sys.argv[3])
N = int(sys.argv[4]) if len(sys.argv) > 4 else 60


def images_under(root: Path):
    return sorted(p for p in root.rglob("*") if p.suffix.lower() in (".jpg", ".jpeg", ".png"))


def camera(src: Path, dst: Path, size, quality, rng):
    img = Image.open(src).convert("RGB")
    # Crop to the target's shape rather than squash it, then enlarge.
    w, h = img.size
    tw, th = size
    if w / h > tw / th:
        nw = int(h * tw / th)
        img = img.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    else:
        nh = int(w * th / tw)
        img = img.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
    img = img.resize(size, Image.BICUBIC)
    a = np.asarray(img, dtype=np.int16)
    noise = rng.normal(0, 3.0, a.shape[:2] + (1,)).astype(np.int16)
    a = np.clip(a + noise, 0, 255).astype(np.uint8)
    dst.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(a).save(dst, quality=quality, subsampling=2)


WORDS = (
    "the invoice number payment received order total amount account balance meeting tomorrow "
    "please send report schedule project review update status office travel ticket booking train "
    "hotel address phone email reply thanks message group photo shared link document policy date "
    "time customer service refund delivery tracking package courier electricity water bill rent "
    "salary bank statement transfer credit debit card expiry insurance renewal premium doctor "
    "appointment prescription medicine result sample test laboratory school college exam marks "
    "result admission fee library notice holiday festival family friend birthday wedding market"
).split()
HINDI = "नमस्ते धन्यवाद बैठक कल भुगतान खाता राशि पता फोन संदेश तस्वीर दस्तावेज़ बिल बैंक तारीख समय सेवा".split()


def font(size, hindi=False):
    for name in (["NirmalaUI.ttf", "Nirmala.ttc", "mangal.ttf"] if hindi else ["segoeui.ttf", "arial.ttf"]):
        try:
            return ImageFont.truetype(f"C:/Windows/Fonts/{name}", size)
        except OSError:
            continue
    return ImageFont.load_default()


def screen(dst: Path, seed: int):
    r = random.Random(seed)
    dark = seed % 3 == 0
    bg, fg, sub = ((18, 18, 20), (235, 235, 240), (150, 150, 160)) if dark else ((250, 250, 250), (20, 20, 25), (110, 110, 120))
    img = Image.new("RGB", (1080, 2400), bg)
    d = ImageDraw.Draw(img)
    # The same app chrome on every screen: header, avatar, bottom bar.
    d.rectangle([0, 0, 1080, 210], fill=(30, 110, 100))
    d.text((48, 90), "Messages", fill=(255, 255, 255), font=font(64))
    d.rectangle([0, 2240, 1080, 2400], fill=(40, 40, 46) if dark else (235, 235, 238))
    y = 260
    f = font(44)
    fh = font(46, hindi=True)
    while y < 2150:
        left = r.random() < 0.5
        hindi = r.random() < 0.2
        words = [r.choice(HINDI if hindi else WORDS) for _ in range(r.randint(3, 9))]
        line = " ".join(words)
        box = d.textbbox((0, 0), line, font=fh if hindi else f)
        w = box[2] - box[0]
        x = 40 if left else max(40, 1040 - w - 40)
        d.rounded_rectangle([x - 20, y - 12, x + w + 20, y + 76], 24, fill=(44, 44, 52) if dark and left else (32, 80, 72) if dark else (232, 232, 236) if left else (205, 235, 225))
        d.text((x, y), line, fill=fg, font=fh if hindi else f)
        d.text((x, y + 80), f"{r.randint(1, 12)}:{r.randint(10, 59)} pm", fill=sub, font=font(26))
        y += 150
    dst.parent.mkdir(parents=True, exist_ok=True)
    img.save(dst, optimize=False)


def main():
    rng = np.random.default_rng(20261030)
    coco = images_under(COCO)
    random.Random(1).shuffle(coco)
    text = images_under(COCOTEXT)
    random.Random(2).shuffle(text)
    print(f"sources: coco {len(coco)}, cocotext {len(text)}")
    for i, src in enumerate(coco[:N]):
        camera(src, OUT / "photos12" / f"cam_{i:04d}.jpg", (4080, 3060), 92, rng)
    for i, src in enumerate(coco[N:2 * N]):
        camera(src, OUT / "photos2" / f"chat_{i:04d}.jpg", (1600, 1200), 80, rng)
    for i, src in enumerate(text[:N // 2]):
        camera(src, OUT / "text12" / f"txt_{i:04d}.jpg", (4080, 3060), 92, rng)
    for i in range(N):
        screen(OUT / "screens" / f"Screenshot_{i:04d}.png", i)
    for sub in ("photos12", "photos2", "text12", "screens"):
        files = list((OUT / sub).iterdir())
        print(sub, len(files), "files, mean", sum(f.stat().st_size for f in files) // max(1, len(files)) // 1024, "KB")


if __name__ == "__main__":
    main()
