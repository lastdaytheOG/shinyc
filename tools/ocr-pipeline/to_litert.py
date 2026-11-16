import argparse
import shutil
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


PER_CHANNEL = {"BatchNormalization", "HardSigmoid", "HardSwish", "Mul", "Add", "Relu", "Sigmoid"}


def axes_of(node, found):
    if len(node.input) > 1:
        value = found.get(node.input[1])
        return None if value is None else [int(v) for v in value.reshape(-1)]
    for attribute in node.attribute:
        if attribute.name == "axes":
            return list(attribute.ints)
    return None


def perm_of(node):
    for attribute in node.attribute:
        if attribute.name == "perm":
            return list(attribute.ints)
    return None


def keep_four_dims(model):
    model = onnx.shape_inference.infer_shapes(model)
    graph = model.graph
    found = constants(graph)
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim] for v in graph.value_info}
    consumers = {}
    for node in graph.node:
        for name in node.input:
            consumers.setdefault(name, []).append(node)
    outputs = {o.name for o in graph.output}
    renamed = {}
    gone = set()
    for squeeze in [n for n in graph.node if n.op_type == "Squeeze"]:
        shape = shapes.get(squeeze.input[0])
        if axes_of(squeeze, found) != [2] or shape is None or len(shape) != 4 or shape[2] != 1:
            continue
        inside = {squeeze.output[0]}
        ends = []
        seen = set()
        stack = [squeeze.output[0]]
        while stack:
            name = stack.pop()
            if name in outputs:
                raise SystemExit("a squeezed tensor is a model output: %s" % name)
            for user in consumers.get(name, []):
                if id(user) in seen:
                    continue
                seen.add(id(user))
                if user.op_type == "Unsqueeze" and axes_of(user, found) == [2]:
                    ends.append(user)
                elif user.op_type == "Transpose" and perm_of(user) == [0, 2, 1]:
                    ends.append(user)
                elif user.op_type in PER_CHANNEL:
                    for k, other in enumerate(user.input):
                        if other in inside or other == "":
                            continue
                        if user.op_type == "BatchNormalization" and k > 0:
                            continue
                        value = found.get(other)
                        if value is None or value.size != 1:
                            raise SystemExit("%s mixes a squeezed tensor with %s" % (user.name, other))
                    inside.add(user.output[0])
                    stack.append(user.output[0])
                else:
                    raise SystemExit("a squeezed tensor reaches %s %s" % (user.op_type, user.name))
        if not ends:
            raise SystemExit("a squeeze is never undone: %s" % squeeze.name)
        renamed[squeeze.output[0]] = squeeze.input[0]
        gone.add(id(squeeze))
        for end in ends:
            if end.op_type == "Unsqueeze":
                renamed[end.output[0]] = end.input[0]
                gone.add(id(end))
            else:
                del end.attribute[:]
                end.attribute.append(helper.make_attribute("perm", [0, 2, 3, 1]))
    nodes = [n for n in graph.node if id(n) not in gone]
    for node in nodes:
        for k, name in enumerate(node.input):
            while name in renamed:
                name = renamed[name]
            node.input[k] = name
    del graph.node[:]
    graph.node.extend(nodes)
    del graph.value_info[:]
    return onnx.shape_inference.infer_shapes(model), len(gone)


def simplify(model):
    import onnxsim

    model.ir_version = max(model.ir_version, 9)
    simpler, ok = onnxsim.simplify(model)
    if not ok:
        raise SystemExit("onnxsim could not check the simplified model")
    return simpler


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
    parser.add_argument("--save")
    parser.add_argument("--precision", choices=["float32", "float16"], default="float32")
    args = parser.parse_args()
    sys.stdout.reconfigure(errors="replace")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model = onnx.load(MODELS / (args.name + ".onnx"))
    if model.opset_import[0].version < OPSET:
        model = version_converter.convert_version(model, OPSET)
    fused = fuse_gelu(model)
    model = fix_shape(model, [int(v) for v in args.shape.split(",")])
    model = simplify(model)
    model, unsqueezed = keep_four_dims(model)
    onnx.checker.check_model(model)
    rewritten = out / (args.name + "-gelu.onnx")
    onnx.save(model, rewritten)
    print("GELU spots rewritten:", fused, "| squeezes undone:", unsqueezed,
          "| left:", sorted({n.op_type for n in model.graph.node}))
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
    if args.save:
        made = out / "tf" / ("%s-gelu_%s.tflite" % (args.name, args.precision))
        shutil.copyfile(made, MODELS / args.save)
        print("saved", MODELS / args.save)


if __name__ == "__main__":
    sys.exit(main())
