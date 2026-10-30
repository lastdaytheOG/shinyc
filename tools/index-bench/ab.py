"""The old app and the new one, side by side on one device, in alternating order, on one folder.

    ab.py <serial> <old pkg> <new pkg> <prefix> <folder> <count> [--rounds 2]
"""
import json
import os
import statistics
import subprocess
import sys
from pathlib import Path

RESULTS = Path(os.environ.get("INDEX_BENCH_RESULTS", Path(__file__).parent / "results"))
HERE = Path(__file__).parent

args = sys.argv[1:]
rounds = 2
if "--rounds" in args:
    i = args.index("--rounds")
    rounds = int(args[i + 1])
    del args[i : i + 2]
serial, old, new, prefix, folder, count = args
order = []
for r in range(rounds):
    order += [(r, "old", old), (r, "new", new)] if r % 2 == 0 else [(r, "new", new), (r, "old", old)]

runs = {"old": [], "new": []}
last = {}
for r, name, pkg in order:
    label = f"{prefix}-{name}-{r}"
    p = subprocess.run([sys.executable, str(HERE / "run.py"), serial, pkg, label, "scan", folder, "--expect", count], capture_output=True, text=True)
    print(p.stdout.strip().splitlines()[0][:130] if p.stdout.strip() else p.stderr[-400:], flush=True)
    if "NOT ON MAINS" in p.stdout:
        print("stopping: the laptop is on battery", flush=True)
        sys.exit(2)
    j = json.load(open(RESULTS / f"{label}.json", encoding="utf-8"))
    m = {k: int(v) for k, v in j["metrics"].items()}
    runs[name].append((j["ms_per_picture"], m.get("ocr_ms.avg", 0), j["gc_count"], j["gc_ms"], j["peak_native_mb"], j["peak_heap_mb"], j["new_rows"], j["new_vectors"]))
    last[name] = label

print(f"\n{folder}: {'':4s} {'ms per picture, each run':30s} {'median':>7s} {'reading':>8s} {'gc n':>5s} {'gc ms':>6s} {'nat MB':>7s} {'heap MB':>8s} {'rows':>5s} {'vectors':>8s}")
for name in ("old", "new"):
    rs = runs[name]
    print(f"{name:12s} {' '.join(f'{x[0]:6.0f}' for x in rs):30s} {statistics.median(x[0] for x in rs):7.0f} {statistics.median(x[1] for x in rs):8.0f} {statistics.median(x[2] for x in rs):5.0f} {statistics.median(x[3] for x in rs):6.0f} {max(x[4] for x in rs):7d} {max(x[5] for x in rs):8d} {rs[-1][6]:5d} {rs[-1][7]:8d}")
print(f"old / new: {statistics.median(x[0] for x in runs['old']) / statistics.median(x[0] for x in runs['new']):.2f}x")
