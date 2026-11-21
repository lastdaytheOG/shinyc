import argparse
import struct
from pathlib import Path

import numpy as np
from tensorflow.lite.python import schema_py_generated as fb
from tensorflow.lite.tools import flatbuffer_utils as fu

HERE = Path(__file__).resolve().parent
MODELS = HERE / "models"
OP = fb.BuiltinOperator
NONE = fb.ActivationFunctionType.NONE
ACTIVATION_OP = {
    fb.ActivationFunctionType.RELU: OP.RELU,
    fb.ActivationFunctionType.RELU6: OP.RELU6,
    fb.ActivationFunctionType.RELU_N1_TO_1: OP.RELU_N1_TO_1,
}


def builtin(code):
    return max(code.builtinCode, code.deprecatedBuiltinCode)


def opcode(model, op, version=1):
    for i, code in enumerate(model.operatorCodes):
        if builtin(code) == op:
            return i
    code = fb.OperatorCodeT()
    code.builtinCode = op
    code.deprecatedBuiltinCode = min(op, 127)
    code.version = version
    model.operatorCodes.append(code)
    return len(model.operatorCodes) - 1


def one_buffer(model):
    data = np.frombuffer(struct.pack("<f", 1.0), dtype=np.uint8)
    for i, buffer in enumerate(model.buffers):
        if buffer.data is not None and len(buffer.data) == 4 and bytes(buffer.data) == data.tobytes():
            return i
    buffer = fb.BufferT()
    buffer.data = data
    model.buffers.append(buffer)
    return len(model.buffers) - 1


def tensor_like(subgraph, index, suffix):
    source = subgraph.tensors[index]
    tensor = fb.TensorT()
    tensor.shape = None if source.shape is None else np.array(source.shape, dtype=np.int32)
    tensor.shapeSignature = None if source.shapeSignature is None else np.array(source.shapeSignature, dtype=np.int32)
    tensor.type = source.type
    tensor.buffer = 0
    name = source.name.decode() if isinstance(source.name, bytes) else source.name
    tensor.name = name + suffix
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def scalar_one(model, subgraph):
    tensor = fb.TensorT()
    tensor.shape = np.array([], dtype=np.int32)
    tensor.type = fb.TensorType.FLOAT32
    tensor.buffer = one_buffer(model)
    tensor.name = "gpu_ops_one"
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def plain(model, op, inputs, outputs):
    operator = fb.OperatorT()
    operator.opcodeIndex = opcode(model, op)
    operator.inputs = np.array(inputs, dtype=np.int32)
    operator.outputs = np.array(outputs, dtype=np.int32)
    return operator


def constant(model, subgraph, values, name):
    data = np.array(values, dtype=np.int32)
    raw = data.tobytes()
    index = None
    for i, buffer in enumerate(model.buffers):
        if i > 0 and buffer.data is not None and bytes(buffer.data) == raw:
            index = i
            break
    if index is None:
        buffer = fb.BufferT()
        buffer.data = np.frombuffer(raw, dtype=np.uint8)
        model.buffers.append(buffer)
        index = len(model.buffers) - 1
    tensor = fb.TensorT()
    tensor.shape = np.array([len(data)], dtype=np.int32)
    tensor.type = fb.TensorType.INT32
    tensor.buffer = index
    tensor.name = name
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def fresh(subgraph, shape, kind, name):
    tensor = fb.TensorT()
    tensor.shape = np.array(shape, dtype=np.int32)
    tensor.type = kind
    tensor.buffer = 0
    tensor.name = name
    subgraph.tensors.append(tensor)
    return len(subgraph.tensors) - 1


def values(model, subgraph, index):
    data = model.buffers[subgraph.tensors[index].buffer].data
    return None if data is None else np.frombuffer(bytes(data), dtype=np.int32).tolist()


def split_heads(model):
    reshape = {i for i, c in enumerate(model.operatorCodes) if builtin(c) == OP.RESHAPE}
    transpose = {i for i, c in enumerate(model.operatorCodes) if builtin(c) == OP.TRANSPOSE}
    strided = {i for i, c in enumerate(model.operatorCodes) if builtin(c) == OP.STRIDED_SLICE}
    done = 0
    for subgraph in model.subgraphs:
        consumers = {}
        for n, operator in enumerate(subgraph.operators):
            for t in operator.inputs:
                consumers.setdefault(int(t), []).append(n)
        drop = set()
        place = {}
        for n, first in enumerate(subgraph.operators):
            if first.opcodeIndex not in reshape:
                continue
            shape = subgraph.tensors[first.outputs[0]].shape.tolist()
            if len(shape) != 5:
                continue
            batch, length, parts, heads, size = shape
            follow = consumers.get(int(first.outputs[0]), [])
            if len(follow) != 1 or subgraph.operators[follow[0]].opcodeIndex not in transpose:
                continue
            turn = subgraph.operators[follow[0]]
            if values(model, subgraph, turn.inputs[1]) != [2, 0, 3, 1, 4]:
                continue
            slices = consumers.get(int(turn.outputs[0]), [])
            plan = []
            for s in slices:
                cut = subgraph.operators[s]
                if cut.opcodeIndex not in strided:
                    break
                begin = values(model, subgraph, cut.inputs[1])
                after = consumers.get(int(cut.outputs[0]), [])
                if begin is None or len(after) != 1 or subgraph.operators[after[0]].opcodeIndex not in reshape:
                    break
                last = subgraph.operators[after[0]]
                if subgraph.tensors[last.outputs[0]].shape.tolist() != [batch, heads, length, size]:
                    break
                plan.append((begin[0], s, after[0], int(last.outputs[0])))
            else:
                if sorted(p[0] for p in plan) != list(range(parts)):
                    continue
                source = int(first.inputs[0])
                if subgraph.tensors[source].shape.tolist() != [batch, length, parts * heads * size]:
                    continue
                drop.update([n, follow[0]])
                for k, s, r, out in plan:
                    drop.update([s, r])
                    name = "gpu_ops_head_%d_%d_%d" % (n, k, out)
                    piece = fresh(subgraph, [batch, length, heads * size], fb.TensorType.FLOAT32, name + "_piece")
                    shaped = fresh(subgraph, [batch, length, heads, size], fb.TensorType.FLOAT32, name + "_shaped")
                    take = plain(model, OP.SLICE, [source,
                                                   constant(model, subgraph, [0, 0, k * heads * size], name + "_begin"),
                                                   constant(model, subgraph, [batch, length, heads * size], name + "_size")],
                                 [piece])
                    take.builtinOptionsType = fb.BuiltinOptions.SliceOptions
                    take.builtinOptions = fb.SliceOptionsT()
                    form = plain(model, OP.RESHAPE, [piece, constant(model, subgraph, [batch, length, heads, size], name + "_shape")], [shaped])
                    form.builtinOptionsType = fb.BuiltinOptions.ReshapeOptions
                    form.builtinOptions = fb.ReshapeOptionsT()
                    form.builtinOptions.newShape = np.array([batch, length, heads, size], dtype=np.int32)
                    swap = plain(model, OP.TRANSPOSE, [shaped, constant(model, subgraph, [0, 2, 1, 3], name + "_perm")], [out])
                    swap.builtinOptionsType = fb.BuiltinOptions.TransposeOptions
                    swap.builtinOptions = fb.TransposeOptionsT()
                    place[r] = [take, form, swap]
                done += 1
        if drop:
            operators = []
            for n, operator in enumerate(subgraph.operators):
                if n in place:
                    operators.extend(place[n])
                elif n not in drop:
                    operators.append(operator)
            subgraph.operators = operators
    return done


def rewrite(model):
    counts = {"RELU_0_TO_1": 0, "fused activation": 0, "attention heads": split_heads(model)}
    relu01 = [i for i, c in enumerate(model.operatorCodes) if builtin(c) == OP.RELU_0_TO_1]
    transpose_conv = [i for i, c in enumerate(model.operatorCodes) if builtin(c) == OP.TRANSPOSE_CONV]
    for subgraph in model.subgraphs:
        one = None
        operators = []
        for operator in subgraph.operators:
            if operator.opcodeIndex in relu01:
                if one is None:
                    one = scalar_one(model, subgraph)
                middle = tensor_like(subgraph, operator.outputs[0], "_relu")
                operators.append(plain(model, OP.RELU, [operator.inputs[0]], [middle]))
                operators.append(plain(model, OP.MINIMUM, [middle, one], [operator.outputs[0]]))
                counts["RELU_0_TO_1"] += 1
                continue
            operators.append(operator)
            if operator.opcodeIndex in transpose_conv:
                activation = operator.builtinOptions.fusedActivationFunction
                if activation != NONE:
                    operator.builtinOptions.fusedActivationFunction = NONE
                    middle = tensor_like(subgraph, operator.outputs[0], "_conv")
                    out = operator.outputs[0]
                    operator.outputs = np.array([middle], dtype=np.int32)
                    operators.append(plain(model, ACTIVATION_OP[activation], [middle], [out]))
                    counts["fused activation"] += 1
        subgraph.operators = operators
    for i in transpose_conv:
        model.operatorCodes[i].version = min(model.operatorCodes[i].version, 3)
    used = {o.opcodeIndex for g in model.subgraphs for o in g.operators}
    if any(i in used for i in relu01):
        raise SystemExit("RELU_0_TO_1 still used")
    return counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source")
    parser.add_argument("target")
    args = parser.parse_args()
    source = Path(args.source)
    target = Path(args.target)
    source = source if source.is_absolute() else MODELS / source
    target = target if target.is_absolute() else MODELS / target
    model = fu.read_model(str(source))
    counts = rewrite(model)
    fu.write_model(model, str(target))
    print("%s: %s, %.2f MB" % (target.name, counts, target.stat().st_size / 1e6))


if __name__ == "__main__":
    main()
