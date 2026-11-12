import math

import torch
import torch.nn as nn

LETTERS = "abcdefghijklmnopqrstuvwxyz"
PAD, BOS, EOS = 0, 1, 2
VOCAB = 3 + len(LETTERS)
MAX_IN = 24
MAX_OUT = 32


def encode(word):
    return [3 + LETTERS.index(c) for c in word]


def decode(ids):
    return "".join(LETTERS[i - 3] for i in ids if i >= 3)


class Translit(nn.Module):
    def __init__(self, emb=48, enc=128, dec=256, feed=True):
        super().__init__()
        self.sizes = (emb, enc, dec, int(feed))
        self.feed = feed
        self.src_emb = nn.Embedding(VOCAB, emb, padding_idx=PAD)
        self.encoder = nn.GRU(emb, enc, batch_first=True, bidirectional=True)
        self.bridge = nn.Linear(2 * enc, dec)
        self.tgt_emb = nn.Embedding(VOCAB, emb, padding_idx=PAD)
        self.cell = nn.GRUCell(emb + 2 * enc if feed else emb, dec)
        self.attn = nn.Linear(dec, 2 * enc, bias=False)
        self.out = nn.Linear(dec + 2 * enc, VOCAB)

    def encode(self, src, lengths):
        x = self.src_emb(src)
        packed = nn.utils.rnn.pack_padded_sequence(x, lengths.cpu(), batch_first=True, enforce_sorted=False)
        out, h = self.encoder(packed)
        out, _ = nn.utils.rnn.pad_packed_sequence(out, batch_first=True, total_length=src.size(1))
        h0 = torch.tanh(self.bridge(torch.cat([h[0], h[1]], dim=1)))
        return out, h0

    def step(self, y_prev, h, ctx, enc_out, mask):
        x = torch.cat([self.tgt_emb(y_prev), ctx], dim=1) if self.feed else self.tgt_emb(y_prev)
        h = self.cell(x, h)
        q = self.attn(h)
        scores = torch.bmm(enc_out, q.unsqueeze(2)).squeeze(2)
        scores = scores.masked_fill(~mask, -1e9)
        a = torch.softmax(scores, dim=1)
        ctx = torch.bmm(a.unsqueeze(1), enc_out).squeeze(1)
        logits = self.out(torch.cat([h, ctx], dim=1))
        return logits, h, ctx

    def forward(self, src, lengths, tgt_in):
        enc_out, h = self.encode(src, lengths)
        mask = src != PAD
        ctx = torch.zeros(src.size(0), enc_out.size(2))
        steps = []
        for t in range(tgt_in.size(1)):
            logits, h, ctx = self.step(tgt_in[:, t], h, ctx, enc_out, mask)
            steps.append(logits)
        return torch.stack(steps, dim=1)

    @torch.no_grad()
    def beam(self, word, width=4, max_out=MAX_OUT):
        src = torch.tensor([encode(word)])
        enc_out, h = self.encode(src, torch.tensor([src.size(1)]))
        mask = torch.ones(1, src.size(1), dtype=torch.bool)
        ctx = torch.zeros(1, enc_out.size(2))
        beams = [(0.0, [BOS], h, ctx)]
        done = []
        for _ in range(max_out):
            if not beams:
                break
            n = len(beams)
            y = torch.tensor([b[1][-1] for b in beams])
            hs = torch.cat([b[2] for b in beams])
            cs = torch.cat([b[3] for b in beams])
            logits, hs, cs = self.step(y, hs, cs, enc_out.expand(n, -1, -1), mask.expand(n, -1))
            logp = torch.log_softmax(logits, dim=1)
            cand = []
            for i, (score, toks, _, _) in enumerate(beams):
                top = torch.topk(logp[i], width)
                for lp, tok in zip(top.values.tolist(), top.indices.tolist()):
                    cand.append((score + lp, toks + [tok], i))
            cand.sort(key=lambda c: -c[0])
            beams = []
            for score, toks, i in cand:
                if toks[-1] == EOS:
                    done.append((score, toks))
                elif toks[-1] >= 3 and len(beams) < width:
                    beams.append((score, toks, hs[i:i + 1], cs[i:i + 1]))
            done.sort(key=lambda d: -d[0])
            done = done[:width]
            if len(done) >= width and (not beams or beams[0][0] < done[-1][0]):
                break
        return [(decode(toks[1:-1]), math.exp(score)) for score, toks in done if len(toks) > 2]
