# Replaces every Erf node of an ONNX graph with tanh(c1*u + c3*u^3) (the tanh form of GELU), so the
# model needs no TensorFlow Flex op on the phone. Only the onnx library is used.
import sys, numpy as np, onnx
from onnx import helper, numpy_helper
m = onnx.load(sys.argv[1]); g = m.graph
c1 = numpy_helper.from_array(np.array(1.1283791670955126, np.float32), "erf_c1")
c3 = numpy_helper.from_array(np.array(0.10091, np.float32), "erf_c3")
g.initializer.extend([c1, c3])
out_nodes = []; n = 0
for node in g.node:
    if node.op_type != "Erf": out_nodes.append(node); continue
    u, y, p = node.input[0], node.output[0], f"erf{n}_"; n += 1
    out_nodes += [
        helper.make_node("Mul", [u, u], [p + "u2"]),
        helper.make_node("Mul", [p + "u2", u], [p + "u3"]),
        helper.make_node("Mul", [u, "erf_c1"], [p + "a"]),
        helper.make_node("Mul", [p + "u3", "erf_c3"], [p + "b"]),
        helper.make_node("Add", [p + "a", p + "b"], [p + "s"]),
        helper.make_node("Tanh", [p + "s"], [y]),
    ]
del g.node[:]; g.node.extend(out_nodes)
onnx.checker.check_model(m)
onnx.save(m, sys.argv[2]); print("replaced", n, "Erf nodes")
