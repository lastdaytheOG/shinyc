import argparse
import math
import pathlib

import torch
import torch.nn as nn
import torch.nn.functional as F

LANGS = ["en", "as", "bn", "brx", "gom", "gu", "hi", "kn", "ks", "mai", "ml", "mni", "mr", "ne", "or", "pa", "sa", "sd",
         "si", "ta", "te", "ur"]
BOS, PAD, EOS, UNK = 0, 1, 2, 3


def read_dict(path):
    symbols = ["<s>", "<pad>", "</s>", "<unk>"]
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line:
            symbols.append(line.rsplit(" ", 1)[0])
    symbols += [f"__{lang}__" for lang in LANGS]
    return symbols, {s: i for i, s in enumerate(symbols)}


def sinusoids(n, dim, padding_idx):
    half = dim // 2
    scale = math.log(10000) / (half - 1)
    freqs = torch.exp(torch.arange(half, dtype=torch.float) * -scale)
    angles = torch.arange(n, dtype=torch.float).unsqueeze(1) * freqs.unsqueeze(0)
    table = torch.cat([torch.sin(angles), torch.cos(angles)], dim=1)
    table[padding_idx, :] = 0
    return table


class Attention(nn.Module):
    def __init__(self, dim, heads):
        super().__init__()
        self.heads = heads
        self.q_proj = nn.Linear(dim, dim)
        self.k_proj = nn.Linear(dim, dim)
        self.v_proj = nn.Linear(dim, dim)
        self.out_proj = nn.Linear(dim, dim)

    def forward(self, x, memory, mask):
        b, t, d = x.shape
        s = memory.shape[1]
        h = self.heads
        hd = d // h
        q = (self.q_proj(x) * hd ** -0.5).view(b, t, h, hd).transpose(1, 2)
        k = self.k_proj(memory).view(b, s, h, hd).transpose(1, 2)
        v = self.v_proj(memory).view(b, s, h, hd).transpose(1, 2)
        w = torch.matmul(q, k.transpose(-1, -2))
        if mask is not None:
            w = w.masked_fill(mask, float("-inf"))
        w = torch.softmax(w, dim=-1)
        out = torch.matmul(w, v).transpose(1, 2).reshape(b, t, d)
        return self.out_proj(out)


class EncoderLayer(nn.Module):
    def __init__(self, dim, heads, ffn):
        super().__init__()
        self.self_attn = Attention(dim, heads)
        self.self_attn_layer_norm = nn.LayerNorm(dim)
        self.fc1 = nn.Linear(dim, ffn)
        self.fc2 = nn.Linear(ffn, dim)
        self.final_layer_norm = nn.LayerNorm(dim)

    def forward(self, x, pad_mask):
        y = self.self_attn_layer_norm(x)
        x = x + self.self_attn(y, y, pad_mask)
        y = self.final_layer_norm(x)
        return x + self.fc2(F.gelu(self.fc1(y)))


class DecoderLayer(nn.Module):
    def __init__(self, dim, heads, ffn):
        super().__init__()
        self.self_attn = Attention(dim, heads)
        self.self_attn_layer_norm = nn.LayerNorm(dim)
        self.encoder_attn = Attention(dim, heads)
        self.encoder_attn_layer_norm = nn.LayerNorm(dim)
        self.fc1 = nn.Linear(dim, ffn)
        self.fc2 = nn.Linear(ffn, dim)
        self.final_layer_norm = nn.LayerNorm(dim)

    def forward(self, x, memory, causal, memory_mask):
        y = self.self_attn_layer_norm(x)
        x = x + self.self_attn(y, y, causal)
        y = self.encoder_attn_layer_norm(x)
        x = x + self.encoder_attn(y, memory, memory_mask)
        y = self.final_layer_norm(x)
        return x + self.fc2(F.gelu(self.fc1(y)))


class Stack(nn.Module):
    def __init__(self, vocab, dim, heads, ffn, layers, decoder):
        super().__init__()
        self.embed_tokens = nn.Embedding(vocab, dim, padding_idx=PAD)
        self.layernorm_embedding = nn.LayerNorm(dim)
        self.layers = nn.ModuleList([(DecoderLayer if decoder else EncoderLayer)(dim, heads, ffn) for _ in range(layers)])
        self.layer_norm = nn.LayerNorm(dim)
        if decoder:
            self.output_projection = nn.Linear(dim, vocab, bias=False)
        self.register_buffer("positions", sinusoids(1024, dim, PAD), persistent=False)
        self.scale = math.sqrt(dim)

    def embed(self, tokens):
        mask = tokens.ne(PAD).long()
        pos = torch.cumsum(mask, dim=1) * mask + PAD
        x = self.embed_tokens(tokens) * self.scale + self.positions[pos]
        return self.layernorm_embedding(x)


class IndicXlit(nn.Module):
    def __init__(self, src_vocab, tgt_vocab, dim=256, heads=4, ffn=1024, layers=6):
        super().__init__()
        self.encoder = Stack(src_vocab, dim, heads, ffn, layers, decoder=False)
        self.decoder = Stack(tgt_vocab, dim, heads, ffn, layers, decoder=True)

    def encode(self, src):
        pad = src.eq(PAD)
        x = self.encoder.embed(src)
        mask = pad[:, None, None, :]
        for layer in self.encoder.layers:
            x = layer(x, mask)
        return self.encoder.layer_norm(x), mask

    def start(self, memory):
        cross = []
        b, s, d = memory.shape
        for layer in self.decoder.layers:
            a = layer.encoder_attn
            hd = d // a.heads
            k = a.k_proj(memory).view(b, s, a.heads, hd).transpose(1, 2)
            v = a.v_proj(memory).view(b, s, a.heads, hd).transpose(1, 2)
            cross.append((k, v))
        return cross, [None] * len(self.decoder.layers)

    def step(self, tok, pos, cross, cache, memory_mask):
        dec = self.decoder
        b = tok.shape[0]
        x = dec.embed_tokens(tok) * dec.scale + dec.positions[PAD + 1 + pos]
        x = dec.layernorm_embedding(x).unsqueeze(1)
        d = x.shape[-1]
        for li, layer in enumerate(dec.layers):
            a = layer.self_attn
            h = a.heads
            hd = d // h
            y = layer.self_attn_layer_norm(x)
            q = (a.q_proj(y) * hd ** -0.5).view(b, 1, h, hd).transpose(1, 2)
            k = a.k_proj(y).view(b, 1, h, hd).transpose(1, 2)
            v = a.v_proj(y).view(b, 1, h, hd).transpose(1, 2)
            if cache[li] is not None:
                k = torch.cat([cache[li][0], k], dim=2)
                v = torch.cat([cache[li][1], v], dim=2)
            cache[li] = (k, v)
            w = torch.softmax(torch.matmul(q, k.transpose(-1, -2)), dim=-1)
            x = x + a.out_proj(torch.matmul(w, v).transpose(1, 2).reshape(b, 1, d))
            c = layer.encoder_attn
            y = layer.encoder_attn_layer_norm(x)
            q = (c.q_proj(y) * hd ** -0.5).view(b, 1, h, hd).transpose(1, 2)
            ck, cv = cross[li]
            w = torch.matmul(q, ck.transpose(-1, -2)).masked_fill(memory_mask, float("-inf"))
            w = torch.softmax(w, dim=-1)
            x = x + c.out_proj(torch.matmul(w, cv).transpose(1, 2).reshape(b, 1, d))
            y = layer.final_layer_norm(x)
            x = x + layer.fc2(F.gelu(layer.fc1(y)))
        x = dec.layer_norm(x[:, 0])
        return torch.log_softmax(dec.output_projection(x).float(), dim=-1)


class Teacher:
    def __init__(self, root, lang="hi"):
        import argparse as ap
        root = pathlib.Path(root)
        torch.serialization.add_safe_globals([ap.Namespace])
        ck = torch.load(root / "transformer" / "indicxlit.pt", map_location="cpu", weights_only=True)
        self.src_symbols, self.src_index = read_dict(root / "corpus-bin" / "dict.en.txt")
        self.tgt_symbols, self.tgt_index = read_dict(root / "corpus-bin" / f"dict.{lang}.txt")
        state = {k: v for k, v in ck["model"].items() if not k.endswith(("version", "_float_tensor"))}
        self.model = IndicXlit(len(self.src_symbols), len(self.tgt_symbols))
        missing, unexpected = self.model.load_state_dict(state, strict=False)
        assert not unexpected and all("positions" in m for m in missing), (missing, unexpected)
        self.model.eval()
        self.lang_tok = self.src_index[f"__{lang}__"]
        self.allowed = torch.zeros(len(self.tgt_symbols), dtype=torch.bool)
        for i, s in enumerate(self.tgt_symbols):
            if i == EOS or (i > UNK and not s.startswith("__") and any("\u0900" <= c <= "\u097f" for c in s)):
                self.allowed[i] = True

    def source(self, word):
        return [self.lang_tok] + [self.src_index.get(c, UNK) for c in word.lower()] + [EOS]

    @torch.no_grad()
    def beam(self, words, width=5, max_len=40):
        n = len(words)
        srcs = [self.source(w) for w in words]
        s_len = max(len(s) for s in srcs)
        src = torch.full((n, s_len), PAD, dtype=torch.long)
        for i, s in enumerate(srcs):
            src[i, s_len - len(s):] = torch.tensor(s)
        memory, mask = self.model.encode(src)
        memory = memory.repeat_interleave(width, dim=0)
        mask = mask.repeat_interleave(width, dim=0)
        prev = torch.full((n * width, 1), EOS, dtype=torch.long)
        scores = torch.zeros(n, width)
        scores[:, 1:] = float("-inf")
        done = [[] for _ in range(n)]
        alive = torch.ones(n, dtype=torch.bool)
        cross, cache = self.model.start(memory)
        for step in range(max_len):
            logp = self.model.step(prev[:, -1], step, cross, cache, mask)
            logp[:, ~self.allowed] = float("-inf")
            v = logp.shape[-1]
            total = (scores.view(-1, 1) + logp).view(n, width * v)
            top, idx = total.topk(2 * width, dim=1)
            beam_of = idx // v
            tok = idx % v
            is_eos = tok.eq(EOS)
            for i, j in (is_eos & torch.isfinite(top)).nonzero().tolist():
                if alive[i] and len(done[i]) < width:
                    seq = prev[i * width + beam_of[i, j].item(), 1:].tolist()
                    sc = top[i, j].item()
                    done[i].append((sc / (len(seq) + 1), sc, seq))
            keep = ~is_eos & (torch.cumsum((~is_eos).int(), dim=1) <= width)
            order = torch.argsort((~keep).int(), dim=1, stable=True)[:, :width]
            sel_beam = beam_of.gather(1, order)
            sel_tok = tok.gather(1, order)
            sel_score = top.gather(1, order).masked_fill(~keep.gather(1, order), float("-inf"))
            rows = (torch.arange(n).unsqueeze(1) * width + sel_beam).view(-1)
            prev = torch.cat([prev[rows], sel_tok.view(-1, 1)], dim=1)
            cache = [(k[rows], v[rows]) for k, v in cache]
            scores = sel_score
            for i in range(n):
                if alive[i] and len(done[i]) >= width:
                    alive[i] = False
            scores[~alive] = float("-inf")
            if not alive.any():
                break
        out = []
        for i in range(n):
            ranked = sorted(done[i], key=lambda d: -d[0])[:width]
            out.append([("".join(self.tgt_symbols[t] for t in seq), norm, raw) for norm, raw, seq in ranked])
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="D:/amar-translit/teacher")
    ap.add_argument("words", nargs="+")
    a = ap.parse_args()
    t = Teacher(a.root)
    for w, guesses in zip(a.words, t.beam(a.words)):
        print(w, [(g, round(math.exp(n), 3)) for g, n, _ in guesses])


if __name__ == "__main__":
    main()
