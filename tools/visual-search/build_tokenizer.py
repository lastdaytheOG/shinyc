"""Compile EmbeddingGemma 2's tokenizer.json into the compact table the app tokenizes with.

python build_tokenizer.py <dir with tokenizer.json> <out gemma_bpe.bin> <out cases.tsv>

The app needs only what BPE encoding reads: the id of every token that is a single character,
the 256 byte-fallback ids, and each merge as (left id, right id, merged id) in rank order.
The token strings themselves are never needed on the phone.

Binary layout, little-endian:
  "GBPE" | int32 version=1 | int32 bos | int32 eos | int32 unk
  int32 nChars, then nChars x (int32 code point, int32 id), sorted by code point
  256 x int32 byte-fallback id (<0x00>..<0xFF>)
  int32 nMerges, then nMerges x (int32 left, int32 right, int32 merged), rank = position

cases.tsv: text<TAB>space-separated ids, as the reference tokenizer (transformers) encodes the
text with the model's query prompt; the app's unit test must reproduce every line.
"""
from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

PROMPT = "task: search result | query: "

CASES = [
    "dog holding a stick in the snow", "cat sitting in a bathroom sink", "a", "", "  two  spaces ",
    "Red car parked next to the yellow building!!", "2 boys playing cricket at 5:30pm", "pizza & beer @ home",
    "नदी में हाथी को नहलाता आदमी", "पीले रेनकोट में फ़ोन देखती औरत", "शादी की सजावट", "बर्फ़ में कुत्ता",
    "barf mein kutta", "shaadi ki decoration", "beach pe sunset", "purple phool wala good morning message",
    "মেয়েটি হাসছে", "నది ఒడ్డున ఏనుగు", "zebra 🦓 and giraffe 🦒", "Café crème brûlée", "naïve résumé",
    "ID card / Aadhaar 1234 5678 9012", "UPI: amar@okicici ₹500", "tab\there", "line\nbreak",
    "日本の寺", "مرحبا", "Ελληνικά", "ПРИВЕТ мир", "x² + y² = z²", "e-mail: a.b@c.co", "'quoted' \"double\"",
    "ज़मीन ज़मीन", "क़लम", "क्षत्रिय ज्ञान", "॥ ॐ ॥", "१२३ ४५६", "Mixed हिंदी and English words",
]


def main() -> None:
    src, out_bin, out_cases = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    tok = json.loads((src / "tokenizer.json").read_text(encoding="utf-8"))
    model = tok["model"]
    assert model["type"] == "BPE" and model["byte_fallback"] and not model.get("ignore_merges")
    assert tok["normalizer"] == {"type": "Replace", "pattern": {"String": " "}, "content": "\u2581"}
    vocab: dict[str, int] = model["vocab"]
    special = {a["content"]: a["id"] for a in tok["added_tokens"]}
    bos, eos, unk = special["<bos>"], special["<eos>"], special["<unk>"]

    chars = sorted((ord(t), i) for t, i in vocab.items() if len(t) == 1)
    byte_ids = [vocab.get(f"<0x{b:02X}>", -1) for b in range(256)]
    assert all(i >= 0 for i in byte_ids), "missing byte-fallback tokens"
    merges = []
    for pair in model["merges"]:
        left, right = pair if isinstance(pair, list) else pair.split(" ", 1)
        merges.append((vocab[left], vocab[right], vocab[left + right]))

    out_bin.parent.mkdir(parents=True, exist_ok=True)
    with open(out_bin, "wb") as f:
        f.write(b"GBPE")
        f.write(struct.pack("<4i", 1, bos, eos, unk))
        f.write(struct.pack("<i", len(chars)))
        for cp, i in chars:
            f.write(struct.pack("<2i", cp, i))
        f.write(struct.pack("<256i", *byte_ids))
        f.write(struct.pack("<i", len(merges)))
        for m in merges:
            f.write(struct.pack("<3i", *m))
    print(f"{out_bin}: {len(chars)} characters, {len(merges)} merges, {out_bin.stat().st_size / 1e6:.1f} MB")

    # The reference is tokenizer.json itself, through the `tokenizers` library: transformers'
    # AutoTokenizer also reads tokenizer_config.json, which a trimmed copy does not have, and
    # without it leaves out the <eos> that the app (and the model card) put at the end.
    from tokenizers import Tokenizer
    from gallery_queries import Q
    ref = Tokenizer.from_file(str(src / "tokenizer.json"))
    cases = CASES + [text for q in Q for text in q[1:4]]
    out_cases.parent.mkdir(parents=True, exist_ok=True)
    with open(out_cases, "w", encoding="utf-8", newline="\n") as f:
        for text in cases:
            ids = ref.encode(PROMPT + text).ids
            escaped = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
            f.write(escaped + "\t" + " ".join(map(str, ids)) + "\n")
    print(f"{out_cases}: {len(cases)} cases")


if __name__ == "__main__":
    main()
