"""The new build on a real phone: is it safe to put over the real app, and how fast is it there.

    python phone_run.py <serial> [--apk X --test-apk Y] [--sets Screenshots,ALL] [--keep-app]

The bench build has its own package and data, so the phone's own vault is never touched.

  1. Stops if the phone has too little room.
  2. Installs the bench build.
  3. Runs PhotoPixelsDeviceTest: the photo model's pixels from native code must be Kotlin's bits on
     this CPU, or vectors made by the new build differ from the ones already stored. If this fails
     the real app must not be updated, and nothing more is run.
  4. Scans each set in turn, each started cool. A run killed part way (a phone's own cleaner does
     this) is run again and carries on where it stopped; its time still counts.
  5. Writes results/phone-summary.json and takes the bench build, and the text it read, off the phone.
     It stays only if something did not finish, so that it can be looked at.

Results hold timings, counts, file names and hashes, and stay in the git-ignored results folder.
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
HERE = Path(__file__).parent
RESULTS = Path(os.environ.get("INDEX_BENCH_RESULTS", HERE / "results"))
RESULTS.mkdir(parents=True, exist_ok=True)
PKG = "com.amar.vault.bench"
RUNNER = f"{PKG}.test/androidx.test.runner.AndroidJUnitRunner"
ROOM_NEEDED_MB = 1500   # the APK, the models it copies out, the vault it builds
TRIES = 4


def option(args, name, default=None):
    if name not in args:
        return default
    i = args.index(name)
    value = args[i + 1]
    del args[i : i + 2]
    return value


args = sys.argv[1:]
apk = option(args, "--apk", r"D:\amar-bench\apks\bench-arm64.apk")
test_apk = option(args, "--test-apk", r"D:\amar-bench\apks\bench-arm64-test.apk")
sets = option(args, "--sets", "Screenshots,ALL").split(",")
keep_app = "--keep-app" in args
if keep_app:
    args.remove("--keep-app")
serial = args[0]


def adb(*a, timeout=900):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    p = subprocess.run([ADB, "-s", serial, *a], capture_output=True, timeout=timeout, env=env)
    return p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")


def free_mb():
    last = adb("shell", "df", "-k", "/data").strip().splitlines()[-1].split()
    return int(last[3]) // 1024


def phone_state():
    t = adb("shell", "dumpsys", "thermalservice")
    b = adb("shell", "dumpsys", "battery")
    status = re.search(r"Thermal Status: (\d)", t)
    skin = re.search(r"mValue=([\d.]+), mType=3, mName=SKIN", t)
    level = re.search(r"level: (\d+)", b)
    return {
        "thermal": int(status.group(1)) if status else None,
        "skin": float(skin.group(1)) if skin else None,
        "battery": int(level.group(1)) if level else None,
    }


def wait_cool(limit=1500):
    t0 = time.time()
    while time.time() - t0 < limit:
        s = phone_state()
        if (s["thermal"] or 0) <= 1 and (s["skin"] or 0) <= 38.5:
            print("cool:", s, flush=True)
            return
        time.sleep(30)
    print(f"still warm after {limit}s, going on:", phone_state(), flush=True)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def attempt(label, folder, keep):
    for suffix in (".json", ".digest.jsonl", ".progress.jsonl", ".host.json"):
        (RESULTS / f"{label}{suffix}").unlink(missing_ok=True)

    # The app on screen, as someone leaving it to index: a phone treats work in the background
    # far less kindly. After the test has saved its settings, before its settling time ends.
    def open_app():
        time.sleep(14)
        adb("shell", "am", "start", "-n", f"{PKG}/com.amar.vault.MainActivity")

    threading.Thread(target=open_app, daemon=True).start()
    command = [sys.executable, str(HERE / "run.py"), serial, PKG, label, "scan", folder, "--settle", "25000"]
    if keep:
        command.append("--keep")
    p = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    print(p.stdout[-900:], p.stderr[-500:], flush=True)

    final = read_json(RESULTS / f"{label}.json")
    progress_file = RESULTS / f"{label}.progress.jsonl"
    progress = [json.loads(line) for line in progress_file.read_text(encoding="utf-8").splitlines()] if progress_file.exists() else []
    finished = bool(final and final.get("state") == "SUCCEEDED")
    digest = RESULTS / f"{label}.digest.jsonl"
    stored = sum(1 for _ in digest.open(encoding="utf-8")) if digest.exists() else None
    if not finished:
        # Why it stopped, for whoever looks next. Stays in the git-ignored results folder.
        (RESULTS / f"{label}.logcat.txt").write_text(adb("logcat", "-d", "-b", "events", "-b", "crash", "-t", "4000"), encoding="utf-8")
    return {"finished": finished, "final": final, "progress": progress, "stored": stored}


def scan_set(name, folder, keep, rows_before):
    parts = []
    for n in range(TRIES):
        label = f"phone-{name}" + (f"-try{n + 1}" if n else "")
        parts.append(attempt(label, folder, keep or n > 0))
        if parts[-1]["finished"]:
            break
        print(f"{label} did not finish; carrying on where it stopped", flush=True)
        wait_cool()
    return summarise(name, parts, rows_before)


def summarise(name, parts, rows_before):
    def of(part, key, default=0):
        if part["final"] and key in part["final"]:
            return part["final"][key]
        return default

    ms = rows = 0
    for p in parts:
        last = p["progress"][-1] if p["progress"] else {}
        ms += of(p, "total_ms", last.get("ms", 0))
        rows += of(p, "new_rows", last.get("rows", 0))
    # What is stored at the end is exact. The sum above misses what a killed run stored after its last note.
    vault_rows = parts[-1]["stored"]
    if vault_rows is not None:
        rows = vault_rows - rows_before
    notes = [line for p in parts for line in p["progress"]]
    return {
        "set": name,
        "finished": parts[-1]["finished"],
        "tries": len(parts),
        "pictures_stored": rows,
        "minutes": round(ms / 60000, 1),
        "ms_per_picture": round(ms / max(1, rows)),
        "thermal_worst": max([of(p, "thermal_worst", -1) for p in parts] + [n.get("thermal", -1) for n in notes]),
        "peak_native_mb": max([of(p, "peak_native_mb") for p in parts] + [n.get("peak_native_mb", 0) for n in notes]),
        "peak_heap_mb": max([of(p, "peak_heap_mb") for p in parts] + [n.get("peak_heap_mb", 0) for n in notes]),
        "vault_rows_at_end": vault_rows,
        "vectors": sum(of(p, "new_vectors") for p in parts),
    }


summary = {"serial": serial, "started": time.strftime("%Y-%m-%d %H:%M"), "state_at_start": phone_state(), "free_mb": free_mb()}
print(summary, flush=True)
if summary["free_mb"] < ROOM_NEEDED_MB:
    sys.exit(f"STOP: {summary['free_mb']} MB free on the phone, {ROOM_NEEDED_MB} MB needed. Nothing was installed.")

print("installing the bench build (if the phone asks to allow it, allow it)...", flush=True)
for file in (apk, test_apk):
    out = adb("install", "-r", "-t", file)
    if "Success" not in out:
        sys.exit(f"STOP: could not install {file}: {out[-400:]}")

parity = adb("shell", "am", "instrument", "-w", "-e", "class", "com.amar.vault.PhotoPixelsDeviceTest", RUNNER)
(RESULTS / "phone-parity.txt").write_text(parity, encoding="utf-8")
summary["parity_ok"] = "OK (2 tests)" in parity
print("native pixels equal Kotlin's on this CPU:", summary["parity_ok"], flush=True)
if not summary["parity_ok"]:
    (RESULTS / "phone-summary.json").write_text(json.dumps(summary, indent=1))
    print(parity[-1500:])
    sys.exit("STOP: do NOT update the real app: photo vectors from this build would differ on this phone.")

stay_before = adb("shell", "settings", "get", "global", "stay_on_while_plugged_in").strip()
adb("shell", "svc", "power", "stayon", "usb")
summary["sets"] = []
try:
    for i, folder in enumerate(sets):
        wait_cool()
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        name = folder.lower()
        # Later sets build on the earlier ones: a picture read once is not read again.
        rows_before = (summary["sets"][-1]["vault_rows_at_end"] or 0) if summary["sets"] else 0
        summary["sets"].append(scan_set(name, folder, i > 0, rows_before))
        (RESULTS / "phone-summary.json").write_text(json.dumps(summary, indent=1))
        print(summary["sets"][-1], flush=True)
finally:
    adb("shell", "settings", "put", "global", "stay_on_while_plugged_in", stay_before if stay_before.isdigit() else "0")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")

summary["state_at_end"] = phone_state()
done = all(s["finished"] for s in summary["sets"])
if done and not keep_app:
    print(adb("uninstall", f"{PKG}.test").strip(), adb("uninstall", PKG).strip())
    summary["bench_removed"] = True
else:
    summary["bench_removed"] = False
    print("the bench build is still on the phone")
(RESULTS / "phone-summary.json").write_text(json.dumps(summary, indent=1))
print(json.dumps(summary, indent=1))
print("PHONE RUN DONE" if done else "PHONE RUN DID NOT FINISH")
