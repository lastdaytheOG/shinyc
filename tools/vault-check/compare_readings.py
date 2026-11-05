"""Compare two recordings of the same pages against what the pages say.

    python compare_readings.py <folder with N.txt truth files> <before.jsonl> <after.jsonl> [--show N]

For each kind of page (the file name up to its first "_"): words of the page that were read, numbers
of the page that were read, words read that the page does not say, and repeats. Then every number and
word that one of the two read and the other did not, page by page. Reading a word the second time
it is on a page counts: a table says 12 three times.
"""
import collections
import io
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ocr_score import score, truth, words  # noqa: E402


def has_digit(word):
    return any(c.isdigit() for c in word)


def numbers(counter):
    return collections.Counter({w: n for w, n in counter.items() if has_digit(w)})


def load(path):
    with io.open(path, encoding='utf-8') as f:
        return {r['name']: r for r in map(json.loads, f) if r}


def main():
    folder, before_path, after_path = sys.argv[1:4]
    limit = int(sys.argv[sys.argv.index('--show') + 1]) if '--show' in sys.argv else 15
    before, after = load(before_path), load(after_path)
    totals = collections.defaultdict(collections.Counter)
    lost, gained = [], []
    for name in sorted(before):
        if name not in after:
            continue
        truth_file = os.path.join(folder, os.path.splitext(name)[0] + '.txt')
        if not os.path.exists(truth_file):
            continue
        needed, optional, _ = truth(truth_file)
        kind = re.match(r'[A-Za-z]*', name).group(0) or 'other'
        for key, record in (('b', before[name]), ('a', after[name])):
            found, need, noise, repeats, _, _ = score(record['text'], needed, optional)
            read = collections.Counter(words(record['text']))
            wanted = numbers(needed)
            t = totals[kind]
            t[key + 'found'] += found
            t['need'] += need if key == 'b' else 0
            t[key + 'noise'] += noise
            t[key + 'repeats'] += repeats
            t[key + 'num'] += sum(min(n, read[w]) for w, n in wanted.items())
            t['neednum'] += sum(wanted.values()) if key == 'b' else 0
            t[key + 'lines'] += len([l for l in record['text'].splitlines() if l.strip()])
            t['pages'] += 1 if key == 'b' else 0
            t[key + 'esc'] += 1 if record.get('escalated') else 0
            t[key + 'ms'] += record.get('ms', 0)
        rb, ra = collections.Counter(words(before[name]['text'])), collections.Counter(words(after[name]['text']))
        for w, n in needed.items():
            b, a = min(n, rb[w]), min(n, ra[w])
            if a < b:
                lost.append((name, w, b - a))
            elif a > b:
                gained.append((name, w, a - b))
    print(f"{'kind':8s} {'pages':>5s} | {'words read':>21s} | {'numbers read':>21s} | {'wrong words':>13s} | {'repeats':>13s} | {'lines':>13s} | {'escalated':>9s} | ms/page")
    all_ = collections.Counter()
    for kind, t in list(sorted(totals.items())):
        all_.update(t)
    for kind, t in list(sorted(totals.items())) + [('ALL', all_)]:
        print(f"{kind:8s} {t['pages']:5d} | {t['bfound']:6d} -> {t['afound']:6d} /{t['need']:5d} | {t['bnum']:6d} -> {t['anum']:6d} /{t['neednum']:5d} | "
              f"{t['bnoise']:5d} -> {t['anoise']:5d} | {t['brepeats']:5d} -> {t['arepeats']:5d} | {t['blines']:5d} -> {t['alines']:5d} | "
              f"{t['besc']:3d} -> {t['aesc']:3d} | {t['bms'] // max(t['pages'], 1)} -> {t['ams'] // max(t['pages'], 1)}")
    lost_nums = [x for x in lost if has_digit(x[1])]
    gained_nums = [x for x in gained if has_digit(x[1])]
    print(f"\nnumbers lost {sum(x[2] for x in lost_nums)} on {len({x[0] for x in lost_nums})} pages, gained {sum(x[2] for x in gained_nums)} on {len({x[0] for x in gained_nums})} pages")
    print(f"words lost {sum(x[2] for x in lost)} on {len({x[0] for x in lost})} pages, gained {sum(x[2] for x in gained)} on {len({x[0] for x in gained})} pages")
    for title, rows in (('numbers lost', lost_nums), ('words lost', [x for x in lost if not has_digit(x[1])])):
        if rows:
            print(f'\n{title}:')
            for row in rows[:limit]:
                print('  ', row)
            if len(rows) > limit:
                print(f'   … {len(rows) - limit} more')


if __name__ == '__main__':
    main()
