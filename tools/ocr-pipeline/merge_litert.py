import argparse
import hashlib
from pathlib import Path

from tensorflow.lite.python import schema_py_generated as fb
from tensorflow.lite.tools import flatbuffer_utils as fu

HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"
GROUPS = {
    "v6-tiny-rec-48.tflite": [("48x%d" % w, "v6-tiny-rec-48x%d.tflite" % w) for w in (320, 640, 960)],
    "v5-en-rec-48.tflite": [("48x%d" % w, "v5-en-rec-48x%d.tflite" % w) for w in (320, 640, 960)],
    "v6-tiny-det-x1280.tflite": [("%dx1280" % w, "v6-tiny-det-%dx1280.tflite" % w) for w in (544, 576, 608, 640, 704)],
}


def digest(buffer):
    data = bytes(buffer.data) if buffer.data is not None else b""
    return hashlib.sha256(data).hexdigest(), len(data)


def code_of(code):
    return code.builtinCode, code.deprecatedBuiltinCode, code.customCode, code.version


def signature(key, index, subgraph):
    sig = fb.SignatureDefT()
    sig.signatureKey = key
    sig.subgraphIndex = index
    sig.inputs, sig.outputs = [], []
    for target, tensors in ((sig.inputs, subgraph.inputs), (sig.outputs, subgraph.outputs)):
        for n, tensor in enumerate(tensors):
            entry = fb.TensorMapT()
            name = subgraph.tensors[tensor].name
            entry.name = name.decode() if isinstance(name, bytes) else (name or "t%d" % n)
            entry.tensorIndex = tensor
            target.append(entry)
    return sig


def merge(parts):
    base = None
    found = {}
    codes = []
    for key, path in parts:
        model = fu.read_model(str(path))
        if len(model.subgraphs) != 1:
            raise SystemExit("%s has %d subgraphs" % (path, len(model.subgraphs)))
        subgraph = model.subgraphs[0]
        if base is None:
            base = model
            base.subgraphs = []
            base.signatureDefs = []
            for i, buffer in enumerate(base.buffers):
                found.setdefault(digest(buffer), i)
            codes = [code_of(c) for c in base.operatorCodes]
        else:
            for tensor in subgraph.tensors:
                buffer = model.buffers[tensor.buffer]
                k = digest(buffer)
                if k not in found:
                    base.buffers.append(buffer)
                    found[k] = len(base.buffers) - 1
                tensor.buffer = found[k]
            for op in subgraph.operators:
                code = model.operatorCodes[op.opcodeIndex]
                if code_of(code) not in codes:
                    base.operatorCodes.append(code)
                    codes.append(code_of(code))
                op.opcodeIndex = codes.index(code_of(code))
        subgraph.name = key
        base.subgraphs.append(subgraph)
        base.signatureDefs.append(signature(key, len(base.subgraphs) - 1, subgraph))
    return base


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("names", nargs="*", default=list(GROUPS))
    args = parser.parse_args()
    for name in args.names:
        parts = [(key, MODELS / file) for key, file in GROUPS[name]]
        missing = [str(p) for _, p in parts if not p.exists()]
        if missing:
            raise SystemExit("missing: " + ", ".join(missing))
        out = MODELS / name
        fu.write_model(merge(parts), str(out))
        before = sum(p.stat().st_size for _, p in parts)
        print("%s: %d signatures, %.1f MB from %.1f MB" % (name, len(parts), out.stat().st_size / 1e6, before / 1e6))


if __name__ == "__main__":
    main()
