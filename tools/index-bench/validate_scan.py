"""Checks that a scan which skips what has been read still ends with the same vault: run on a test device
with the bench corpus in the gallery. Each step compares what is stored, not how long it took.

    validate_scan.py <serial> <pkg> <folder>

  1 a scan from nothing                 the reference
  2 the same scan again                 reads nothing, stores the same
  3 a scan killed part way, then again  ends with the same pictures and the same text
  4 a new picture                       only that one is read
  5 a copy of a picture under a new name stored once, as before
  6 a picture changed in place          still one row, with the new picture's text and vector

The checks that change the gallery put the files back afterwards.
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
HERE = Path(__file__).parent
RESULTS = Path(os.environ.get("INDEX_BENCH_RESULTS", Path(__file__).parent / "results"))
serial, pkg, folder = sys.argv[1:4]
remote_folder = f"/sdcard/Pictures/{folder}"
failures = []


def adb(*args, check=True, timeout=1800):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    p = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, timeout=timeout, env=env, encoding="utf-8", errors="replace")
    if check and p.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {p.stdout} {p.stderr}")
    return p.stdout


def run(label, *extra):
    p = subprocess.run([sys.executable, str(HERE / "run.py"), serial, pkg, label, "scan", folder, *extra], capture_output=True, text=True)
    print("   ", p.stdout.strip().splitlines()[0] if p.stdout.strip() else p.stderr[-300:], flush=True)
    return json.loads((RESULTS / f"{label}.json").read_text(encoding="utf-8"))


def digest(label):
    d = {}
    for line in (RESULTS / f"{label}.digest.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            j = json.loads(line)
            d[j["key"]] = j
    return d


def check(what, ok, detail=""):
    print(("  ok  " if ok else "  FAIL ") + what + (f"  ({detail})" if detail else ""), flush=True)
    if not ok:
        failures.append(what)


def compare(a, b, ignore=()):
    only_a, only_b = set(a) - set(b), set(b) - set(a)
    differ = {}
    for k in set(a) & set(b):
        cols = [c for c in a[k] if a[k][c] != b[k].get(c) and c not in ignore]
        if cols:
            differ[k] = cols
    return only_a, only_b, differ


def scan_media():
    adb("shell", "content", "call", "--uri", "content://media/", "--method", "scan_volume", "--arg", "external_primary", check=False)
    time.sleep(6)


print("1. a scan from nothing")
first = run("v-first")
d1 = digest("v-first")
check("every picture stored", first["new_rows"] == first["gallery_pictures"], f"{first['new_rows']} of {first['gallery_pictures']}")
check("every photo has a vector", first["new_vectors"] == first["new_rows"], f"{first['new_vectors']}")

print("2. the same scan again")
again = run("v-again", "--keep", "--settle", "5000")
d2 = digest("v-again")
only_a, only_b, differ = compare(d1, d2)
check("nothing new read", again["new_rows"] == 0 and again["gallery_pictures"] in (0, -1), f"candidates {again['gallery_pictures']}, new rows {again['new_rows']}")
check("what is stored is the same", not (only_a or only_b or differ), f"{len(only_a)}, {len(only_b)}, {len(differ)}")

print("3. a scan killed part way, then again")
adb("shell", "am", "force-stop", pkg, check=False)
adb("shell", "pm", "clear", pkg)
for perm in ("android.permission.READ_MEDIA_IMAGES", "android.permission.POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", pkg, perm, check=False)
instr = subprocess.Popen([ADB, "-s", serial, "shell", "am", "instrument", "-w", "-e", "class", "com.amar.vault.IndexingEngineBenchDeviceTest",
                          "-e", "bench", "scan", "-e", "label", "v-killed", "-e", "folder", folder, f"{pkg}.test/androidx.test.runner.AndroidJUnitRunner"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(float(os.environ.get("KILL_AFTER", "25")))
adb("shell", "am", "crash", pkg, check=False)
time.sleep(3)
instr.kill()
resumed = run("v-resumed", "--keep", "--settle", "15000")
d3 = digest("v-resumed")
only_a, only_b, differ = compare(d1, d3, ignore=("vector",))
check("the resume did not read everything again", 0 <= resumed["gallery_pictures"] < first["gallery_pictures"], f"{resumed['gallery_pictures']} of {first['gallery_pictures']} read")
check("same pictures, same text after the resume", not (only_a or only_b or differ), f"only first {len(only_a)}, only resumed {len(only_b)}, differ {len(differ)}: {list(differ.items())[:3]}")
vector_diff = [k for k in set(d1) & set(d3) if d1[k]["vector"] != d3[k]["vector"]]
no_vector = [k for k in d3 if d3[k]["vector"] == "" and d3[k]["type"] == "photo"]
print(f"      vectors: {len(vector_diff)} differ from the uninterrupted run (made from the thumbnail by the backfill), {len(no_vector)} missing")

print("4. a new picture")
names = adb("shell", "ls", remote_folder).split()
sample = names[0]
ext = sample.rsplit(".", 1)[1]
donor = next(n for n in names if n != sample)
adb("shell", "cp", f"{remote_folder}/{sample}", f"/sdcard/Pictures/benchtmp.{ext}")

# 4 uses a picture from another folder so it is really new to the vault.
other = next((d for d in ("BenchP2", "BenchT12", "BenchP12") if d != folder), None)
other_name = adb("shell", "ls", f"/sdcard/Pictures/{other}").split()[0]
new_path = f"{remote_folder}/zz_new_{other_name}"
adb("shell", "cp", f"/sdcard/Pictures/{other}/{other_name}", new_path)
scan_media()
one = run("v-new", "--keep", "--settle", "5000")
d4 = digest("v-new")
only_a, only_b, differ = compare(d2, d4)
check("only the new picture was read", one["gallery_pictures"] == 1 and one["new_rows"] == 1, f"read {one['gallery_pictures']}, new rows {one['new_rows']}")
check("nothing else changed", len(only_b) == 1 and not only_a and not differ, f"{only_a} {only_b} {differ}")

print("5. a copy of a picture under a new name")
copy_path = f"{remote_folder}/zz_copy_{sample}"
adb("shell", "cp", f"{remote_folder}/{sample}", copy_path)
scan_media()
dup = run("v-copy", "--keep", "--settle", "5000")
d5 = digest("v-copy")
only_a, only_b, differ = compare(d4, d5)
check("the copy adds no row", dup["new_rows"] == 0 and not only_b, f"new rows {dup['new_rows']}, {only_b}")
check("nothing else changed", not only_a and not differ, f"{only_a} {differ}")

print("6. a picture changed in place")
# The copy goes first: once the picture it copies has changed, it would be a picture of its own.
adb("shell", "rm", "-f", copy_path)
scan_media()
time.sleep(2)
adb("shell", "cp", f"{remote_folder}/{donor}", f"{remote_folder}/{sample}")
adb("shell", "touch", f"{remote_folder}/{sample}", check=False)
scan_media()
sample_id = None
for line in adb("shell", "content query --uri content://media/external/images/media --projection _id:_display_name").splitlines():
    if f"_display_name={sample}" in line:
        sample_id = line.split("_id=")[1].split(",")[0].strip()
changed = run("v-changed", "--keep", "--settle", "5000")
d6 = digest("v-changed")
only_a, only_b, differ = compare(d5, d6)
check("still one row for it", changed["new_rows"] == 0, f"new rows {changed['new_rows']}")
check("only it was read again", changed["gallery_pictures"] == 1, f"read {changed['gallery_pictures']}")
check("the changed picture has the new picture's hash and vector", sample_id in differ and "phash" in differ[sample_id] and "vector" in differ[sample_id], f"{differ.get(sample_id)}")
others = {k: v for k, v in differ.items() if k != sample_id}
check("nothing else changed", not only_a and not only_b and not others, f"{only_a} {only_b} {list(others.items())[:3]}")

print("putting the gallery back")
adb("shell", "cp", f"/sdcard/Pictures/benchtmp.{ext}", f"{remote_folder}/{sample}")
adb("shell", "rm", "-f", new_path, f"/sdcard/Pictures/benchtmp.{ext}")
scan_media()

print("\nALL CHECKS PASSED" if not failures else f"\n{len(failures)} FAILED: {failures}")
sys.exit(1 if failures else 0)
