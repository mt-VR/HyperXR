# EfficientViT's linear attention divides by (sum + 1e-15); in fp16 (GPU / NPU) 1e-15 is 0 and gives
# 0/0 = NaN. Sets that tiny constant to 1e-4 (representable in fp16, negligible next to the sums).
import sys, numpy as np, onnx
from onnx import numpy_helper
m = onnx.load(sys.argv[1])
for i, init in enumerate(m.graph.initializer):
    a = numpy_helper.to_array(init)
    if a.size == 1 and 0 < abs(float(a.ravel()[0])) < 1e-10:
        m.graph.initializer[i].CopyFrom(numpy_helper.from_array(np.full(a.shape, 1e-4, a.dtype), init.name))
        print("eps", init.name)
onnx.save(m, sys.argv[2])
