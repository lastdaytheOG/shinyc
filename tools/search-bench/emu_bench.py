import argparse
import os
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
PKG = "com.amar.vault"
PACK = pathlib.Path(r"D:\amar-models\photo-search\siglip2-b16-v2cw1-w4a8")
GALLERY = pathlib.Path(r"D:\amar-vs\device\gallery")
RESULTS = HERE / "results"


def adb(serial, *a, check=True, capture=False):
    cmd = [ADB, "-s", serial, *a]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise SystemExit(f"adb failed: {' '.join(a)}\n{r.stdout}\n{r.stderr}")
    return r.stdout if capture else r


def shell(serial, cmd, **kw):
    return adb(serial, "shell", cmd, **kw)


def install(serial, apk_dir=None):
    app = pathlib.Path(apk_dir) / "app-debug.apk" if apk_dir else ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    test = pathlib.Path(apk_dir) / "app-debug-androidTest.apk" if apk_dir else ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    adb(serial, "install", "-r", "-t", str(app))
    adb(serial, "install", "-r", "-t", str(test))


def push_dir_files(serial, files, target):
    shell(serial, f"rm -rf /data/local/tmp/bench_up && mkdir -p /data/local/tmp/bench_up")
    for f in files:
        adb(serial, "push", str(f), f"/data/local/tmp/bench_up/{f.name}")
    shell(serial, f"run-as {PKG} mkdir -p {target}")
    shell(serial, f"for f in /data/local/tmp/bench_up/*; do run-as {PKG} cp \"$f\" {target}/; done")
    shell(serial, "rm -rf /data/local/tmp/bench_up")


def fresh(serial, photos=True):
    shell(serial, f"pm clear {PKG}")
    push_dir_files(serial, [PACK / n for n in ("gemma_bpe.bin", "query.onnx", "query.onnx_data", "photo-a8.onnx", "manifest.json")],
                   "files/photo-search")
    push_dir_files(serial, [HERE / "corpus" / "bench_corpus.jsonl", HERE / "corpus" / "bench_cases.jsonl"], "files/bench")
    if photos:
        shell(serial, f"run-as {PKG} mkdir -p files/bench/photos")
        adb(serial, "push", str(GALLERY), "/data/local/tmp/")
        shell(serial, f"run-as {PKG} sh -c 'cp /data/local/tmp/gallery/* files/bench/photos/'")
        shell(serial, "rm -rf /data/local/tmp/gallery")


def instrument(serial, *extra):
    out = adb(serial, "shell", "am", "instrument", "-w", *extra, f"{PKG}.test/androidx.test.runner.AndroidJUnitRunner",
              capture=True, check=False)
    print(out[-1500:])
    if "FAILURES" in out or "INSTRUMENTATION_FAILED" in out or "Process crashed" in out:
        raise SystemExit("instrumentation failed")


def load(serial, photos=True):
    instrument(serial, "-e", "class", f"{PKG}.SearchBenchDeviceTest#load", "-e", "bench", "load",
               *([] if photos else ["-e", "photos", "off"]))


def run(serial, label, config, slices=None):
    extra = ["-e", "slice", slices] if slices else []
    instrument(serial, "-e", "class", f"{PKG}.SearchBenchDeviceTest#run", "-e", "bench", "run", "-e", "label", label,
               "-e", "config", config, *extra)
    RESULTS.mkdir(exist_ok=True)
    data = subprocess.run([ADB, "-s", serial, "exec-out", "run-as", PKG, "cat", f"files/bench/results-{label}.jsonl"],
                          capture_output=True).stdout
    (RESULTS / f"results-{label}.jsonl").write_bytes(data)
    print("pulled", label, data.count(b"\n"), "lines")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5556")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--apk-dir", default=None)
    ap.add_argument("--vectors", action="store_true")
    ap.add_argument("--fresh", action="store_true")
    ap.add_argument("--no-photos", action="store_true")
    ap.add_argument("--run", nargs="*", default=[], help="label=config")
    ap.add_argument("--slices", default=None)
    a = ap.parse_args()
    os.environ["MSYS_NO_PATHCONV"] = "1"
    if a.install:
        install(a.serial, a.apk_dir)
    if a.fresh:
        fresh(a.serial, photos=not a.no_photos)
        load(a.serial, photos=not a.no_photos)
    if a.vectors:
        instrument(a.serial, "-e", "class", f"{PKG}.SearchBenchDeviceTest#vectors", "-e", "bench", "vectors")
    for spec in a.run:
        label, config = spec.split("=")
        run(a.serial, label, config, a.slices)


if __name__ == "__main__":
    main()
