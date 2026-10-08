"""Builds the keyword engine's own tests and runs them on the connected emulator or phone.

    python tools/vault-check/engine_test.py

The engine (app/src/main/cpp/SearchEngine.cpp) is plain C++ with nothing of Android in it, so
its tests (test_search_engine.cpp, beside it) are one small program. It is built with the same
compiler the app is built with, for whatever kind of processor the device has, copied to
/data/local/tmp and run there. Nothing of the app is touched.
"""
import glob
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CPP = os.path.join(ROOT, "app", "src", "main", "cpp")
SDK = os.environ.get("ANDROID_HOME") or os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
TARGETS = {"x86_64": "x86_64-linux-android26", "arm64-v8a": "aarch64-linux-android26"}


def main():
    compilers = sorted(glob.glob(os.path.join(SDK, "ndk", "*", "toolchains", "llvm", "prebuilt", "*", "bin", "clang++*")))
    compilers = [c for c in compilers if os.path.basename(c) in ("clang++", "clang++.exe")]
    if not compilers:
        sys.exit("no NDK compiler under " + SDK)
    abi = subprocess.run([ADB, "shell", "getprop", "ro.product.cpu.abi"], capture_output=True, text=True).stdout.strip()
    if abi not in TARGETS:
        sys.exit("no device connected, or one of a kind this does not build for: '%s'" % abi)

    out_dir = os.path.join(ROOT, "app", "build", "engine-test")
    os.makedirs(out_dir, exist_ok=True)
    binary = os.path.join(out_dir, "test_search_engine")
    subprocess.run([
        compilers[-1], "--target=" + TARGETS[abi], "-std=c++17", "-O2", "-Wall", "-static-libstdc++",
        os.path.join(CPP, "SearchEngine.cpp"), os.path.join(CPP, "test_search_engine.cpp"), "-o", binary,
    ], check=True)

    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    on_device = "/data/local/tmp/test_search_engine"
    subprocess.run([ADB, "push", binary, on_device], check=True, capture_output=True, env=env)
    subprocess.run([ADB, "shell", "chmod", "755", on_device], check=True, env=env)
    result = subprocess.run([ADB, "shell", on_device], env=env)
    subprocess.run([ADB, "shell", "rm", on_device], env=env)
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()
