"""Reference cases for the app's photo resize (PhotoPixels.resized): what PIL's
resize((size, size), BICUBIC) makes of small random pictures.

python make_resize_cases.py <out pil_bicubic_cases.txt>

Each case is a line "w h size", a line of the input's w*h*3 bytes (r g b, row by row) and a line
of the output's size*size*3 bytes. The photo model was trained on pictures resized this way.
"""
import sys

import numpy as np
from PIL import Image

CASES = [(37, 23, 16), (64, 48, 16), (10, 7, 16), (200, 150, 96)]


def main() -> None:
    rng = np.random.default_rng(7)
    with open(sys.argv[1], "w", encoding="ascii", newline="\n") as f:
        for w, h, size in CASES:
            # Smooth shapes plus noise: random pixels alone make every filter look alike.
            yy, xx = np.mgrid[0:h, 0:w]
            base = np.stack([128 + 100 * np.sin(xx / 3.0 + c) * np.cos(yy / 5.0 - c) for c in range(3)], -1)
            img = np.clip(base + rng.normal(0, 25, base.shape), 0, 255).astype(np.uint8)
            out = np.asarray(Image.fromarray(img, "RGB").resize((size, size), Image.BICUBIC))
            f.write(f"{w} {h} {size}\n")
            f.write(" ".join(map(str, img.reshape(-1))) + "\n")
            f.write(" ".join(map(str, out.reshape(-1))) + "\n")
    print(f"{sys.argv[1]}: {len(CASES)} cases")


if __name__ == "__main__":
    main()
