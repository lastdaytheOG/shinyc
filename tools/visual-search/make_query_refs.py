"""Reference query vectors for the app's QueryEncoder parity test.

python make_query_refs.py <onnx model dir> <out query_refs.tsv> [<model file in onnx/>]

<onnx model dir> holds EmbeddingGemma 2's ONNX export (tokenizer.json, onnx/model_quantized.onnx),
or prune_query_model.py's trimmed copy (onnx/query.onnx). Encodes a mix of the benchmark's
gallery searches (English, Hindi, Hindi in Latin letters) with that tokenizer.json (the one the
app's table is built from) and ONNX Runtime, as the model card prescribes, and writes
text<TAB>base64(float32 little-endian unit vector). The Kotlin QueryEncoder must give the same
vectors from the same model file.
"""
from __future__ import annotations

import base64
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

sys.path.insert(0, str(Path(__file__).parent))
from gallery_queries import Q  # noqa: E402

PROMPT = "task: search result | query: "


def main() -> None:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    model = sys.argv[3] if len(sys.argv) > 3 else "model_quantized.onnx"
    tok = Tokenizer.from_file(str(src / "tokenizer.json"))
    sess = ort.InferenceSession(str(src / "onnx" / model), providers=["CPUExecutionProvider"])
    feats = {i.name: i.shape[-1] for i in sess.get_inputs() if i.name.endswith("_features")}
    texts = [q[1 + k % 3] for k, q in enumerate(Q[:30])]
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        for text in texts:
            ids = np.array([tok.encode(PROMPT + text).ids], dtype=np.int64)
            feed = {"input_ids": ids, "attention_mask": np.ones_like(ids)}
            feed.update({n: np.zeros((0, w), dtype=np.float32) for n, w in feats.items()})
            v = sess.run(["sentence_embedding"], feed)[0][0].astype(np.float32)
            v /= np.linalg.norm(v)
            f.write(text + "\t" + base64.b64encode(v.astype("<f4").tobytes()).decode() + "\n")
    print(f"{out}: {len(texts)} vectors of {v.shape[0]}")


if __name__ == "__main__":
    main()
