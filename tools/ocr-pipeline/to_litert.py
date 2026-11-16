import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper, version_converter

HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"
OPSET = 20


def constants(graph):
    found = {i.name: numpy_helper.to_array(i) for i in graph.initializer}
    for node in graph.node:
        if node.op_type == "Constant":
            for attribute in node.attribute:
                if attribute.name == "value":
                    found[node.output[0]] = numpy_helper.to_array(attribute.t)
    return found


def scalar(found, name):
    value = found.get(name)
    if value is None or value.size != 1:
        return None
    return float(value.reshape(-1)[0])


def fuse_gelu(model):
    graph = model.graph
    found = constants(graph)
    producer = {o: n for n in graph.node for o in n.output}
    consumers = {}
    for node in graph.node:
        for name in node.input:
            consumers.setdefault(name, []).append(node)

    def only_consumer(name, kind):
        users = consumers.get(name, [])
        if len(users) == 1 and users[0].op_type == kind:
            return users[0]
        return None

    def other(node, name):
        return node.input[1] if node.input[0] == name else node.input[0]

    remove = []
    add = []
    for erf in [n for n in graph.node if n.op_type == "Erf"]:
        div = producer.get(erf.input[0])
        if div is None or div.op_type != "Div" or abs((scalar(found, div.input[1]) or 0) - 2 ** 0.5) > 1e-3:
            raise SystemExit("an Erf is not part of a GELU: %s" % erf.name)
        x = div.input[0]
        plus = only_consumer(erf.output[0], "Add")
        if plus is None or abs((scalar(found, other(plus, erf.output[0])) or 0) - 1.0) > 1e-6:
            raise SystemExit("an Erf is not followed by + 1: %s" % erf.name)
        first = only_consumer(plus.output[0], "Mul")
        second = only_consumer(first.output[0], "Mul") if first is not None else None
        if first is None or second is None:
            raise SystemExit("an Erf is not followed by two multiplications: %s" % erf.name)
        a = other(first, plus.output[0])
        b = other(second, first.output[0])
        halves = [n for n in (a, b) if abs((scalar(found, n) or 0) - 0.5) < 1e-6]
        if len(halves) != 1 or x not in (a, b):
            raise SystemExit("the multiplications after an Erf are not x and 0.5: %s" % erf.name)
        if len(consumers.get(div.output[0], [])) != 1:
            raise SystemExit("x / sqrt(2) is used elsewhere: %s" % erf.name)
        remove += [div, erf, plus, first, second]
        add.append((second, helper.make_node("Gelu", [x], [second.output[0]], name=erf.name + "_gelu")))
    nodes = []
    replaced = {id(old): new for old, new in add}
    gone = {id(n) for n in remove}
    for node in graph.node:
        if id(node) in replaced:
            nodes.append(replaced[id(node)])
        elif id(node) not in gone:
            nodes.append(node)
    del graph.node[:]
    graph.node.extend(nodes)
    return len(add)


def fix_shape(model, shape):
    dims = model.graph.input[0].type.tensor_type.shape.dim
    if len(dims) != len(shape):
        raise SystemExit("the model takes %d dimensions, %d given" % (len(dims), len(shape)))
    for dim, size in zip(dims, shape):
        dim.ClearField("dim_param")
        dim.dim_value = size
    del model.graph.value_info[:]
    for output in model.graph.output:
        output.type.tensor_type.ClearField("shape")
    return onnx.shape_inference.infer_shapes(model)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("name")
    parser.add_argument("--shape", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model = onnx.load(MODELS / (args.name + ".onnx"))
    if model.opset_import[0].version < OPSET:
        model = version_converter.convert_version(model, OPSET)
    fused = fuse_gelu(model)
    model = fix_shape(model, [int(v) for v in args.shape.split(",")])
    onnx.checker.check_model(model)
    rewritten = out / (args.name + "-gelu.onnx")
    onnx.save(model, rewritten)
    print("GELU spots rewritten:", fused, "| left:", sorted({n.op_type for n in model.graph.node}))
    sample = out / "calibration_image_sample_data_20x128x128x3_float32.npy"
    np.save(sample, np.random.default_rng(7).random((20, 128, 128, 3), dtype=np.float32))
    done = subprocess.run(
        [sys.executable, "-m", "onnx2tf", "-i", str(rewritten.resolve()), "-o", str((out / "tf").resolve()), "-n"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=str(out),
    )
    if done.returncode != 0:
        print(done.stdout[-3000:])
        print(done.stderr[-3000:])
        raise SystemExit("onnx2tf failed")
    for made in sorted((out / "tf").glob("*.tflite")):
        print(made.name, made.stat().st_size)


if __name__ == "__main__":
    sys.exit(main())
