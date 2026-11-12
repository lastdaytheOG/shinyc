import base64
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

PASSAGES = [
    ("Electricity bill", "Shivalik Power Distribution Ltd\nElectricity Bill\nConsumer No. 4471029384\nUnits consumed: 312 kWh\nNet amount payable: Rs 2,438.20", 384),
    (None, "Payment successful\n₹450.00\nPaid to Ramesh Kirana Store\nUPI transaction ID 123456789012", 384),
    ("notes 7", "Graphics cards run thousands of small threads at once, and memory bandwidth often limits how fast a kernel finishes.", 384),
    ("IMG 2210", "बिजली का बिल हर महीने की पाँच तारीख तक जमा करना होता है, वरना देर से भुगतान का शुल्क लगता है।", 384),
    (None, "kal maths ka paper hai, chapter 5 aur 7 aayenge. formulas ki list bhej do please", 384),
    ("Rental Agreement", "The tenant has paid a security deposit of Rs 54,000, refundable within thirty days of vacating. " * 30, 384),
    ("Rental Agreement", "The tenant has paid a security deposit of Rs 54,000, refundable within thirty days of vacating. " * 30, 128),
    ("Monsoon notes", "बरसात का मौसम जून में शुरू होता है और सितंबर तक चलता है। " * 40, 256),
]


def passage_text(title, text):
    t = title.strip() if title and title.strip() else "none"
    return f"title: {t} | text: {text}"


def main():
    model_dir, tok_path, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    tok = Tokenizer.from_file(str(tok_path))
    eos = tok.token_to_id("<eos>")
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    sess = ort.InferenceSession(str(model_dir / "query.onnx"), so, providers=["CPUExecutionProvider"])
    feats = {i.name: i.shape[-1] for i in sess.get_inputs() if i.name.endswith("_features")}
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        for title, text, max_tokens in PASSAGES:
            ids = tok.encode(passage_text(title, text[: max_tokens * 8])).ids
            if len(ids) > max_tokens:
                ids = ids[:max_tokens - 1] + [eos]
            arr = np.array([ids], dtype=np.int64)
            feed = {"input_ids": arr, "attention_mask": np.ones_like(arr)}
            feed.update({n: np.zeros((0, w), dtype=np.float32) for n, w in feats.items()})
            v = sess.run(["sentence_embedding"], feed)[0][0].astype(np.float32)
            v /= np.linalg.norm(v)
            esc = lambda s: (s or "").replace("\t", " ").replace("\n", "\\n")
            f.write(f"{esc(title)}\t{esc(text)}\t{max_tokens}\t{len(ids)}\t" + base64.b64encode(v.astype("<f4").tobytes()).decode() + "\n")
    print(out, len(PASSAGES))


if __name__ == "__main__":
    main()
