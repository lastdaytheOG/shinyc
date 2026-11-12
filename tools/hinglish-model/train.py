import argparse
import json
import math
import pathlib
import random
import time
import unicodedata

import torch
import torch.nn as nn

from model import BOS, EOS, LETTERS, MAX_IN, MAX_OUT, PAD, Translit, encode
from rules import key

HUMAN = {"Dakshina", "AK-Freq"}


def read_pairs(path, human_weight=1):
    out = []
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        latin = r["english word"].strip().lower()
        native = unicodedata.normalize("NFC", r["native word"].strip())
        if not latin or len(latin) > MAX_IN or not all(c in LETTERS for c in latin):
            continue
        if not all("\u0900" <= c <= "\u097f" or c in "\u200c\u200d" for c in native):
            continue
        k = key(native)
        if not k or len(k) > MAX_OUT - 2 or not all(c in LETTERS for c in k):
            continue
        n = human_weight if r["source"] in HUMAN else 1
        out.extend([(latin, k, r["source"], native)] * n)
    return out


def batches(pairs, size, shuffle=True):
    order = sorted(range(len(pairs)), key=lambda i: (len(pairs[i][0]), random.random() if shuffle else 0))
    chunks = [order[i:i + size] for i in range(0, len(order), size)]
    if shuffle:
        random.shuffle(chunks)
    for chunk in chunks:
        src = [encode(pairs[i][0]) for i in chunk]
        tgt = [encode(pairs[i][1]) for i in chunk]
        s_len = max(len(s) for s in src)
        t_len = max(len(t) for t in tgt) + 1
        src_t = torch.full((len(chunk), s_len), PAD, dtype=torch.long)
        tin = torch.full((len(chunk), t_len), PAD, dtype=torch.long)
        tout = torch.full((len(chunk), t_len), PAD, dtype=torch.long)
        for j, (s, t) in enumerate(zip(src, tgt)):
            src_t[j, :len(s)] = torch.tensor(s)
            tin[j, :len(t) + 1] = torch.tensor([BOS] + t)
            tout[j, :len(t) + 1] = torch.tensor(t + [EOS])
        yield src_t, torch.tensor([len(s) for s in src]), tin, tout


def evaluate(model, pairs, size=1024):
    model.eval()
    loss_fn = nn.CrossEntropyLoss(ignore_index=PAD, reduction="sum")
    total, tokens, right = 0.0, 0, 0
    with torch.no_grad():
        for src, lengths, tin, tout in batches(pairs, size, shuffle=False):
            logits = model(src, lengths, tin)
            total += loss_fn(logits.reshape(-1, logits.size(-1)), tout.reshape(-1)).item()
            tokens += (tout != PAD).sum().item()
            pred = logits.argmax(-1)
            ok = ((pred == tout) | (tout == PAD)).all(dim=1)
            right += ok.sum().item()
    model.train()
    return total / tokens, right / len(pairs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="D:/amar-translit/data/hin")
    ap.add_argument("--out", default="D:/amar-translit/runs/a")
    ap.add_argument("--emb", type=int, default=48)
    ap.add_argument("--enc", type=int, default=128)
    ap.add_argument("--dec", type=int, default=256)
    ap.add_argument("--feed", type=int, default=1)
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--batch", type=int, default=512)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--human-weight", type=int, default=4)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    random.seed(1)
    torch.manual_seed(1)
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    data = pathlib.Path(a.data)
    train = read_pairs(data / "hin_train.json", a.human_weight)
    if a.limit:
        random.shuffle(train)
        train = train[:a.limit]
    valid = read_pairs(data / "hin_valid.json")
    valid_human = [p for p in valid if p[2] in HUMAN]
    print(f"train {len(train)} valid {len(valid)} valid human {len(valid_human)}", flush=True)
    model = Translit(a.emb, a.enc, a.dec, bool(a.feed))
    print("params", sum(p.numel() for p in model.parameters()), flush=True)
    opt = torch.optim.Adam(model.parameters(), lr=a.lr)
    steps_per_epoch = math.ceil(len(train) / a.batch)
    total_steps = steps_per_epoch * a.epochs
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=total_steps, pct_start=0.05)
    loss_fn = nn.CrossEntropyLoss(ignore_index=PAD)
    step = 0
    log = open(out / "log.txt", "a", encoding="utf-8")
    for epoch in range(a.epochs):
        t0 = time.time()
        running = 0.0
        for src, lengths, tin, tout in batches(train, a.batch):
            logits = model(src, lengths, tin)
            loss = loss_fn(logits.reshape(-1, logits.size(-1)), tout.reshape(-1))
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            step += 1
            running += loss.item()
            if step % 200 == 0:
                msg = f"epoch {epoch} step {step}/{total_steps} loss {running / 200:.4f} {time.time() - t0:.0f}s"
                print(msg, flush=True)
                log.write(msg + "\n")
                log.flush()
                running = 0.0
        vl, va = evaluate(model, valid)
        hl, ha = evaluate(model, valid_human)
        msg = f"epoch {epoch} done {time.time() - t0:.0f}s valid loss {vl:.4f} exact {va:.4f} human loss {hl:.4f} exact {ha:.4f}"
        print(msg, flush=True)
        log.write(msg + "\n")
        log.flush()
        torch.save({"sizes": model.sizes, "state": model.state_dict()}, out / f"epoch{epoch}.pt")
    torch.save({"sizes": model.sizes, "state": model.state_dict()}, out / "final.pt")


if __name__ == "__main__":
    main()
