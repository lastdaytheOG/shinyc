"""What each read of a picture costs and finds, and every set of reads scored against typed truth.

    reads_score.py <reads.jsonl> <truth folder> [--top 25] [--missed "EN_GRAY+HI_GRAY"]

A word counts as found when the set read it as many times as the truth says it. The union of the
reads is an upper bound on what the merge keeps: ReadingMerge only ever drops what repeats a word.
Prints counts, and missed words only when asked.
"""
import collections
import io
import itertools
import json
import os
import re
import statistics
import sys

WORD = re.compile(r'[^\W_]+(?:[ऀ-ॿ̀-ͯ]+[^\W_]*)*', re.UNICODE)


def words(t):
    return WORD.findall(t.lower())


def truth(path):
    needed, optional = [], []
    for line in io.open(path, encoding='utf-8').read().splitlines():
        if not line.strip():
            continue
        if line.startswith('?'):
            optional += words(line[1:])
        else:
            needed += words(line)
    return collections.Counter(needed), collections.Counter(optional)


PRODUCTION = ('EN_RAW', 'HI_RAW', 'EN_GRAY', 'HI_GRAY', 'EN_INVERT', 'EN_UPSCALE')


def main():
    path, folder = sys.argv[1], sys.argv[2]
    top = int(sys.argv[sys.argv.index('--top') + 1]) if '--top' in sys.argv else 25
    show = sys.argv[sys.argv.index('--missed') + 1].split('+') if '--missed' in sys.argv else None
    pics = [json.loads(l) for l in open(path, encoding='utf-8') if l.strip()]
    names = [r['read'] for r in pics[0]['reads']]
    ms = {n: [] for n in names}
    for p in pics:
        for r in p['reads']:
            ms[r['read']].append(r['ms'])
    print(f"{len(pics)} pictures")
    print("cost of one read, alone (median ms): " + ", ".join(f"{n} {statistics.median(ms[n]):.0f}" for n in names))
    all_ms = sum(statistics.median(ms[n]) for n in names)
    t = {}
    for p in pics:
        f = os.path.join(folder, os.path.splitext(p['name'])[0] + '.txt')
        if os.path.exists(f):
            t[p['name']] = truth(f)
    pics = [p for p in pics if p['name'] in t]
    total = sum(sum(v[0].values()) for v in t.values())
    print(f"{len(pics)} of them with truth, {total} words to find")
    rows = []
    for r in range(1, len(names) + 1):
        for subset in itertools.combinations(names, r):
            found = noise = 0
            missed = collections.Counter()
            for p in pics:
                needed, optional = t[p['name']]
                got = collections.Counter()
                for read in p['reads']:
                    if read['read'] in subset:
                        for w, n in collections.Counter(words(read['text'])).items():
                            got[w] = max(got[w], n)
                for w, n in needed.items():
                    k = min(n, got.get(w, 0))
                    found += k
                    if k < n:
                        missed[w] += n - k
                for w, n in got.items():
                    extra = n - needed.get(w, 0) - optional.get(w, 0)
                    if extra > 0:
                        noise += extra
            cost = sum(statistics.median(ms[n]) for n in subset)
            rows.append((subset, found, noise, cost, missed))
    rows.sort(key=lambda x: (-x[1], x[3]))
    prod = next(x for x in rows if set(x[0]) == set(names))
    print(f"\nall six reads (today): {prod[1]}/{total} = {100 * prod[1] / total:.2f}%, noise {prod[2]}, cost {prod[3]:.0f} ms")
    print(f"\n{'reads':58s} {'found':>10s} {'recall':>7s} {'vs all':>7s} {'noise':>6s} {'ms':>6s} {'x fast':>6s}")
    # Only sets no other set beats on both recall and cost.
    best_cost = float('inf')
    shown = 0
    for subset, found, noise, cost, missed in rows:
        if cost < best_cost:
            best_cost = cost
            print(f"{'+'.join(subset):58s} {found:5d}/{total} {100 * found / total:6.2f}% {found - prod[1]:+7d} {noise:6d} {cost:6.0f} {all_ms / cost:6.1f}")
            shown += 1
            if shown >= top:
                break
    if show:
        for subset, found, noise, cost, missed in rows:
            if set(subset) == set(show):
                print("\nmissed by", '+'.join(subset), ":", dict(missed))
                only = collections.Counter(missed) - collections.Counter(prod[4])
                print("missed here but found by all six:", dict(only))


if __name__ == "__main__":
    main()
