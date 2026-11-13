import argparse
import json
import pathlib
import time

import torch

from teacher import Teacher
from train import read_pairs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="D:/amar-translit/data/hin")
    ap.add_argument("--teacher", default="D:/amar-translit/teacher")
    ap.add_argument("--out", default="D:/amar-translit/distill")
    ap.add_argument("--shard", type=int, required=True)
    ap.add_argument("--shards", type=int, required=True)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--batch", type=int, default=256)
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    data = pathlib.Path(a.data)
    held = {p[0] for f in ("hin_valid.json", "hin_test.json") for p in read_pairs(data / f)}
    words = sorted({p[0] for p in read_pairs(data / "hin_train.json")} - held)
    mine = words[a.shard::a.shards]
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"shard{a.shard}.jsonl"
    done = set()
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                done.add(json.loads(line)["w"])
    todo = [w for w in mine if w not in done]
    teacher = Teacher(a.teacher)
    t0 = time.time()
    with open(path, "a", encoding="utf-8") as f:
        for i in range(0, len(todo), a.batch):
            chunk = todo[i:i + a.batch]
            for w, g in zip(chunk, teacher.beam(chunk)):
                f.write(json.dumps({"w": w, "g": [[d, round(r, 4)] for d, _, r in g]}, ensure_ascii=False) + "\n")
            f.flush()
            if (i // a.batch) % 20 == 0:
                print(f"shard {a.shard}: {len(done) + i + len(chunk)}/{len(mine)} {time.time() - t0:.0f}s", flush=True)
    print(f"shard {a.shard}: done {len(mine)}", flush=True)


if __name__ == "__main__":
    main()
