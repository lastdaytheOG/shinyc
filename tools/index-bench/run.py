"""Drives one benchmark run: a clean vault, the instrumented test, results pulled to results/<label>.*

  run.py <serial> <pkg> <label> scan <folder> [--keep] [--expect N]
  run.py <serial> <pkg> <label> import [--keep]
  run.py <serial> <pkg> <label> digest --keep
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ADB = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
RESULTS = Path(os.environ.get("INDEX_BENCH_RESULTS", Path(__file__).parent / "results"))
RESULTS.mkdir(parents=True, exist_ok=True)


def adb(serial, *args, check=True, timeout=3600):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    p = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, timeout=timeout, env=env, encoding="utf-8", errors="replace")
    if check and p.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {p.stdout} {p.stderr}")
    return p.stdout


def host_state():
    """Whether the laptop is on mains, and how busy it is: a run on battery is not comparable."""
    out = subprocess.run(["powershell.exe", "-NoProfile", "-Command",
        "$b=Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus; $c=Get-CimInstance Win32_Processor; Write-Output \"$($b.PowerOnline) $($c.LoadPercentage)\""],
        capture_output=True, text=True, timeout=60).stdout.split()
    return {"mains": out[0] == "True" if out else None, "load": int(out[1]) if len(out) > 1 and out[1].isdigit() else None}


def main():
    serial, pkg, label, bench = sys.argv[1:5]
    host_before = host_state()
    rest = sys.argv[5:]
    keep = "--keep" in rest
    if keep:
        rest.remove("--keep")
    expect = None
    if "--expect" in rest:
        i = rest.index("--expect")
        expect = rest[i + 1]
        del rest[i : i + 2]
    settle = None
    if "--settle" in rest:
        i = rest.index("--settle")
        settle = rest[i + 1]
        del rest[i : i + 2]
    docs = None
    if "--docs" in rest:
        i = rest.index("--docs")
        docs = rest[i + 1]
        del rest[i : i + 2]
    design = []
    if "--design" in rest:
        i = rest.index("--design")
        for kv in rest[i + 1].split(","):
            k, v = kv.split("=")
            design += ["-e", k, v]
        del rest[i : i + 2]
    folder = rest[0] if rest else None

    if not keep:
        adb(serial, "shell", "am", "force-stop", pkg, check=False)
        adb(serial, "shell", "pm", "clear", pkg)
        for perm in ("android.permission.READ_MEDIA_IMAGES", "android.permission.POST_NOTIFICATIONS"):
            adb(serial, "shell", "pm", "grant", pkg, perm, check=False)
    if docs:
        remote_docs = f"/sdcard/Android/data/{pkg}/files/bench/docs"
        adb(serial, "shell", "mkdir", "-p", remote_docs)
        adb(serial, "shell", "rm", "-f", f"{remote_docs}/*", check=False)
        for name in docs.split(","):
            for f in sorted(Path(name).glob("*.pdf")) if Path(name).is_dir() else [Path(name)]:
                adb(serial, "push", str(f), f"{remote_docs}/{f.name}")
    instr = ["shell", "am", "instrument", "-w", "-e", "class", "com.amar.vault.IndexingEngineBenchDeviceTest", "-e", "bench", bench, "-e", "label", label]
    if folder:
        instr += ["-e", "folder", folder]
    if expect:
        instr += ["-e", "expect", expect]
    if settle:
        instr += ["-e", "settle", settle]
    instr += design
    instr.append(f"{pkg}.test/androidx.test.runner.AndroidJUnitRunner")
    t0 = time.time()
    out = adb(serial, *instr, check=False)
    took = time.time() - t0
    remote = f"/sdcard/Android/data/{pkg}/files/bench"
    adb(serial, "pull", f"{remote}/{label}.json", str(RESULTS / f"{label}.json"), check=False)
    adb(serial, "pull", f"{remote}/{label}.digest.jsonl", str(RESULTS / f"{label}.digest.jsonl"), check=False)
    ok = "OK (" in out
    host_after = host_state()
    valid = bool(host_before["mains"] and host_after["mains"])
    (RESULTS / f"{label}.host.json").write_text(json.dumps({"before": host_before, "after": host_after, "valid": valid}))
    print(f"{label}: instrument {'OK' if ok else 'NOT OK'} in {took:.0f}s; host before {host_before} after {host_after}" + ("" if valid else "  *** NOT ON MAINS: NOT COMPARABLE ***"))
    if not ok:
        print(out[-2500:])
    f = RESULTS / f"{label}.json"
    if f.exists():
        r = json.loads(f.read_text(encoding="utf-8"))
        keys = ("debuggable", "folder", "state", "gallery_pictures", "new_rows", "new_vectors", "total_ms", "ms_per_picture", "cpu_ms_process",
                "cpu_share_of_core", "gc_count", "gc_ms", "peak_heap_mb", "peak_native_mb", "thermal_worst")
        print({k: (round(r[k], 1) if isinstance(r.get(k), float) else r.get(k)) for k in keys if k in r})
        for d in r.get("documents", []):
            print("  ", {k: (round(v, 1) if isinstance(v, float) else v) for k, v in d.items()})


if __name__ == "__main__":
    main()
