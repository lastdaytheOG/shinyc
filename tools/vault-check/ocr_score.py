"""Score what the app read off pictures against what the pictures say.

    python ocr_score.py <folder with N.txt truth files> <result.jsonl> [--passes] [--show NAME]

A truth file has one line of the picture per line; a line starting with "?" is optional (text
that is cut off, or too small to ask for: reading it is not counted as noise, missing it is not
counted as a miss). The result file is what PictureOcrMeasureDeviceTest writes.

For each picture: how many of the words it says were read (found/needed), how many words were
read that it does not say (noise), how many of those are repeats of words it does say, how
many lines came out for how many it has, and how many of its lines came out in the order they
are on the picture (order: of the lines that were read, the longest run that is in order).
"""
import collections
import io
import json
import os
import re
import sys

# A word is what search takes for one: a run of letters and digits. "edition.pdf" is two words,
# "BE_ADS_" is two, "2024-26" is two. Vowel signs and the like belong to their word.
WORD = re.compile(r'[^\W_]+(?:[ऀ-ॿ̀-ͯ]+[^\W_]*)*', re.UNICODE)


def words(text):
    return WORD.findall(text.lower())


def truth(path):
    needed, optional, lines = [], [], []
    for line in io.open(path, encoding='utf-8').read().splitlines():
        if not line.strip():
            continue
        if line.startswith('?'):
            optional += words(line[1:])
        else:
            needed += words(line)
            lines.append(words(line))
    return collections.Counter(needed), collections.Counter(optional), lines


def in_order(text, truth_lines):
    """(lines of the picture found in the text, how many of those are in the picture's order)."""
    out = [set(words(l)) for l in text.splitlines() if l.strip()]
    used, where = set(), []
    for line in truth_lines:
        want = set(line)
        for at, got in enumerate(out):
            if at not in used and want and len(want & got) >= 0.8 * len(want | got):
                used.add(at)
                where.append(at)
                break
    longest = []
    for at in where:  # longest increasing run
        best = 1 + max([n for a, n in longest if a < at], default=0)
        longest.append((at, best))
    return len(where), max([n for _, n in longest], default=0)


def score(text, needed, optional):
    read = collections.Counter(words(text))
    found = sum(min(n, read[w]) for w, n in needed.items())
    allowed = needed + optional
    noise = sum(max(0, n - allowed[w]) for w, n in read.items())
    repeats = sum(max(0, n - allowed[w]) for w, n in read.items() if w in allowed)
    missed = sorted(w for w, n in needed.items() if read[w] < n)
    junk = sorted(w for w, n in read.items() if w not in allowed)
    return found, sum(needed.values()), noise, repeats, missed, junk


def main():
    folder, result = sys.argv[1], sys.argv[2]
    show_passes = '--passes' in sys.argv
    show = sys.argv[sys.argv.index('--show') + 1] if '--show' in sys.argv else None
    total = collections.Counter()
    by_pass = collections.defaultdict(collections.Counter)
    print(f"{'picture':10s} {'read':>9s} {'noise':>6s} {'repeats':>8s} {'lines':>9s} {'order':>8s} {'ms':>6s}")
    for line in io.open(result, encoding='utf-8'):
        r = json.loads(line)
        truth_file = os.path.join(folder, os.path.splitext(r['name'])[0] + '.txt')
        if not os.path.exists(truth_file):
            continue
        needed, optional, lines_of_truth = truth(truth_file)
        truth_lines = len(lines_of_truth)
        found, need, noise, repeats, missed, junk = score(r['text'], needed, optional)
        out_lines = len([l for l in r['text'].splitlines() if l.strip()])
        matched, ordered = in_order(r['text'], lines_of_truth)
        print(f"{r['name']:10s} {found:4d}/{need:<4d} {noise:6d} {repeats:8d} {out_lines:4d}/{truth_lines:<4d} {ordered:4d}/{matched:<3d} {r['ms']:6d}")
        total.update(found=found, need=need, noise=noise, repeats=repeats, lines=out_lines, truth_lines=truth_lines,
                     ms=r['ms'], matched=matched, ordered=ordered)
        if show in (r['name'], 'all'):
            print('   missed:', missed)
            print('   not on the picture:', junk)
            if show != 'all':
                print('   --- text ---')
                print('   ' + r['text'].replace('\n', '\n   '))
        for p in r.get('passes', []):
            f, n, nz, rp, _, _ = score(p['text'], needed, optional)
            by_pass[p['pass']].update(found=f, need=n, noise=nz, ms=p['ms'])
            if show in (r['name'], 'all') and show_passes:
                print(f"   --- pass {p['pass']} ({p['ms']:.0f} ms): {f}/{n} read, {nz} noise ---")
                print('   ' + p['text'].replace('\n', '\n   '))
    if total['need']:
        print(f"{'ALL':10s} {total['found']:4d}/{total['need']:<4d} {total['noise']:6d} {total['repeats']:8d} "
              f"{total['lines']:4d}/{total['truth_lines']:<4d} {total['ordered']:4d}/{total['matched']:<3d} {total['ms']:6d}   "
              f"({100.0 * total['found'] / total['need']:.1f}% of the words read)")
    if show_passes:
        print('\nEach pass on its own:')
        for name, c in by_pass.items():
            print(f"  {name:10s} {c['found']:4d}/{c['need']:<4d} read  {c['noise']:5d} noise  {c['ms']:7.0f} ms")


if __name__ == '__main__':
    main()
