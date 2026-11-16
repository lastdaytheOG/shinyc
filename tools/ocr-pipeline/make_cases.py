import gzip
import math
import struct
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
import pyclipper
import yaml
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"
OUT = HERE.parent.parent / "app" / "src" / "test" / "resources" / "ppocr"

LONG_SIDE = 640
THRESH = 0.2
BOX_THRESH = 0.4
UNCLIP = 1.4
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
REC_HEIGHT = 48
REC_WIDTH = 320
REC_BATCH = 6


def draw_screen():
    image = Image.new("RGB", (720, 960), (250, 250, 250))
    draw = ImageDraw.Draw(image)
    fonts = "C:/Windows/Fonts/"
    title = ImageFont.truetype(fonts + "arial.ttf", 54)
    body = ImageFont.truetype(fonts + "segoeui.ttf", 30)
    small = ImageFont.truetype(fonts + "arial.ttf", 18)
    mono = ImageFont.truetype(fonts + "consola.ttf", 28)
    draw.text((40, 40), "Garden notice", font=title, fill=(20, 20, 20))
    draw.text((40, 140), "The gate opens at seven each morning", font=body, fill=(40, 40, 40))
    draw.text((40, 190), "and closes again before the light goes", font=body, fill=(40, 40, 40))
    draw.text((40, 270), "Plot 4821", font=mono, fill=(30, 30, 30))
    draw.text((420, 270), "Rs 1,250.00", font=mono, fill=(30, 30, 30))
    draw.text((40, 320), "Row 17 of 63", font=mono, fill=(30, 30, 30))
    draw.text((420, 320), "12/08/2031", font=mono, fill=(30, 30, 30))
    draw.rectangle((0, 400, 720, 520), fill=(24, 28, 40))
    draw.text((40, 425), "Water is shared on market days", font=body, fill=(235, 235, 240))
    draw.text((40, 470), "bring your own can and hose", font=body, fill=(150, 150, 160))
    draw.text((40, 560), "small print: tools left by the shed are taken to the office at week's end", font=small, fill=(90, 90, 90))
    draw.text((40, 590), "seed packets may be swapped at the green table near the river path", font=small, fill=(130, 130, 130))
    patch = Image.new("RGBA", (460, 60), (0, 0, 0, 0))
    ImageDraw.Draw(patch).text((6, 8), "Harvest fair on the hill", font=body, fill=(30, 60, 30, 255))
    turned = patch.rotate(14, expand=True, resample=Image.BICUBIC)
    image.paste(turned, (120, 640), turned)
    tall = Image.new("RGBA", (300, 50), (0, 0, 0, 0))
    ImageDraw.Draw(tall).text((4, 6), "Mountain village", font=body, fill=(60, 30, 30, 255))
    upright = tall.rotate(90, expand=True, resample=Image.BICUBIC)
    image.paste(upright, (640, 620), upright)
    return image


def resize_for_det(img, limit):
    h, w = img.shape[:2]
    ratio = float(limit) / max(h, w) if max(h, w) > limit else 1.0
    rh = max(int(round(int(h * ratio) / 32) * 32), 32)
    rw = max(int(round(int(w * ratio) / 32) * 32), 32)
    return cv2.resize(img, (rw, rh))


def mini_box(contour):
    rect = cv2.minAreaRect(contour)
    pts = sorted(list(cv2.boxPoints(rect)), key=lambda p: p[0])
    i1, i4 = (0, 1) if pts[1][1] > pts[0][1] else (1, 0)
    i2, i3 = (2, 3) if pts[3][1] > pts[2][1] else (3, 2)
    return [pts[i1], pts[i2], pts[i3], pts[i4]], min(rect[1])


def box_score_fast(bitmap, _box):
    h, w = bitmap.shape[:2]
    box = _box.copy()
    xmin = np.clip(np.floor(box[:, 0].min()).astype("int32"), 0, w - 1)
    xmax = np.clip(np.ceil(box[:, 0].max()).astype("int32"), 0, w - 1)
    ymin = np.clip(np.floor(box[:, 1].min()).astype("int32"), 0, h - 1)
    ymax = np.clip(np.ceil(box[:, 1].max()).astype("int32"), 0, h - 1)
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    box[:, 0] = box[:, 0] - xmin
    box[:, 1] = box[:, 1] - ymin
    cv2.fillPoly(mask, box.reshape(1, -1, 2).astype("int32"), 1)
    return cv2.mean(bitmap[ymin:ymax + 1, xmin:xmax + 1], mask)[0]


def unclip(box, ratio):
    area = cv2.contourArea(box.astype(np.float32))
    length = cv2.arcLength(box.astype(np.float32), True)
    distance = area * ratio / length
    offset = pyclipper.PyclipperOffset()
    offset.AddPath(box, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
    return np.array(offset.Execute(distance))


def order_clockwise(pts):
    by_x = pts[np.argsort(pts[:, 0]), :]
    left = by_x[:2, :]
    right = by_x[2:, :]
    tl, bl = left[np.argsort(left[:, 1]), :]
    tr, br = right[np.argsort(right[:, 1]), :]
    return np.array([tl, tr, br, bl], dtype="float32")


def places_from(pred, dest_w, dest_h):
    bitmap = pred > THRESH
    height, width = bitmap.shape
    contours, _ = cv2.findContours((bitmap * 255).astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    found = []
    for contour in contours[:3000]:
        points, sside = mini_box(contour)
        if sside < 3:
            continue
        points = np.array(points)
        score = box_score_fast(pred, points.reshape(-1, 2))
        if BOX_THRESH > score:
            continue
        box = unclip(points, UNCLIP).reshape(-1, 1, 2)
        box, sside = mini_box(box)
        if sside < 5:
            continue
        box = np.array(box)
        box[:, 0] = np.clip(np.round(box[:, 0] / width * dest_w), 0, dest_w)
        box[:, 1] = np.clip(np.round(box[:, 1] / height * dest_h), 0, dest_h)
        box = order_clockwise(box.astype("int32").astype("float32"))
        box[:, 0] = np.clip(box[:, 0], 0, dest_w - 1)
        box[:, 1] = np.clip(box[:, 1], 0, dest_h - 1)
        across = int(np.linalg.norm(box[0] - box[1]))
        down = int(np.linalg.norm(box[0] - box[3]))
        if across <= 3 or down <= 3:
            continue
        found.append((box, score))
    found.sort(key=lambda f: (f[0][0][1], f[0][0][0]))
    return found


def rotate_crop(img, points):
    across = int(max(np.linalg.norm(points[0] - points[1]), np.linalg.norm(points[2] - points[3])))
    down = int(max(np.linalg.norm(points[0] - points[3]), np.linalg.norm(points[1] - points[2])))
    std = np.float32([[0, 0], [across, 0], [across, down], [0, down]])
    m = cv2.getPerspectiveTransform(points, std)
    out = cv2.warpPerspective(img, m, (across, down), borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC)
    if out.shape[0] * 1.0 / out.shape[1] >= 1.5:
        out = np.rot90(out)
    return out


def strip_bytes(crop, batch_width):
    h, w = crop.shape[:2]
    ratio = w / float(h)
    wide = batch_width if math.ceil(REC_HEIGHT * ratio) > batch_width else int(math.ceil(REC_HEIGHT * ratio))
    return cv2.resize(crop, (wide, REC_HEIGHT)), wide


def write_picture(name, bgr):
    with gzip.open(OUT / name, "wb") as f:
        f.write(struct.pack("<ii", bgr.shape[1], bgr.shape[0]))
        f.write(np.ascontiguousarray(bgr).tobytes())


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    img = cv2.cvtColor(np.array(draw_screen()), cv2.COLOR_RGB2BGR)
    h, w = img.shape[:2]
    write_picture("screen.bin.gz", img)

    resized = resize_for_det(img, LONG_SIDE)
    write_picture("resized.bin.gz", resized)
    x = ((resized.astype("float32") / 255.0 - MEAN) / STD).transpose(2, 0, 1)[None]
    det = ort.InferenceSession(str(MODELS / "v6-tiny-det.onnx"), providers=["CPUExecutionProvider"])
    pred = det.run(None, {det.get_inputs()[0].name: x})[0][0, 0]
    quantised = np.clip(np.round(pred * 255), 0, 255).astype(np.uint8)
    with gzip.open(OUT / "prob.bin.gz", "wb") as f:
        f.write(struct.pack("<ii", quantised.shape[1], quantised.shape[0]))
        f.write(quantised.tobytes())
    places = places_from(quantised.astype(np.float32) / 255.0, w, h)
    with open(OUT / "places.tsv", "w", encoding="utf-8", newline="\n") as f:
        for box, score in places:
            f.write("\t".join(str(int(v)) for v in box.reshape(-1)) + "\t%.6f\n" % score)

    settings = yaml.safe_load((MODELS / "v5-en-rec.yml").read_text(encoding="utf-8"))
    letters = settings["PostProcess"]["character_dict"]
    (OUT / "en-letters.tsv").write_text("\n".join(letters) + "\n", encoding="utf-8", newline="\n")
    character = ["blank"] + letters + [" "]
    rec = ort.InferenceSession(str(MODELS / "v5-en-rec.onnx"), providers=["CPUExecutionProvider"])

    crops = [rotate_crop(img, box) for box, _ in places]
    order = np.argsort([c.shape[1] / float(c.shape[0]) for c in crops], kind="stable")
    lines = [None] * len(crops)
    strips = []
    for start in range(0, len(order), REC_BATCH):
        group = order[start:start + REC_BATCH]
        widest = REC_WIDTH / REC_HEIGHT
        for i in group:
            widest = max(widest, crops[i].shape[1] / float(crops[i].shape[0]))
        batch_width = int(REC_HEIGHT * widest)
        batch = np.zeros((len(group), 3, REC_HEIGHT, batch_width), dtype=np.float32)
        for n, i in enumerate(group):
            small, wide = strip_bytes(crops[i], batch_width)
            strips.append((int(i), batch_width, wide, small))
            batch[n, :, :, :wide] = (small.astype("float32").transpose(2, 0, 1) / 255 - 0.5) / 0.5
        out = rec.run(None, {rec.get_inputs()[0].name: batch})[0]
        assert out.shape[2] == len(character), (out.shape, len(character))
        for n, i in enumerate(group):
            idx = out[n].argmax(axis=1)
            prob = out[n].max(axis=1)
            keep = np.ones(len(idx), dtype=bool)
            keep[1:] = idx[1:] != idx[:-1]
            keep &= idx != 0
            text = "".join(character[k] for k in idx[keep])
            conf = float(np.mean(prob[keep])) if keep.any() else 0.0
            lines[i] = (text, conf, idx, prob)

    with gzip.open(OUT / "strips.bin.gz", "wb") as f:
        f.write(struct.pack("<i", len(strips)))
        for i, batch_width, wide, small in sorted(strips):
            f.write(struct.pack("<iii", i, batch_width, wide))
            f.write(small.tobytes())
    with open(OUT / "lines.tsv", "w", encoding="utf-8", newline="\n") as f:
        for text, conf, idx, prob in lines:
            steps = " ".join("%d:%.6f" % (k, p) for k, p in zip(idx, prob))
            f.write("%s\t%.6f\t%s\n" % (text, conf, steps))

    print("picture", w, h, "map", quantised.shape[1], quantised.shape[0], "places", len(places))
    for (box, score), (text, conf, _, _) in zip(places, lines):
        print("%.2f %.2f %s | %s" % (score, conf, box.reshape(-1).astype(int).tolist(), text))


if __name__ == "__main__":
    sys.exit(main())
