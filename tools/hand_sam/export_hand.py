# Exports EfficientViT-SAM L0 (Apache-2.0, mit-han-lab/efficientvit) with fixed shapes for TFLite:
#   encoder: RGB 0..1 (1,3,512,512) -> image embeddings (1,256,64,64)  (SAM normalisation folded in)
#   decoder: embeddings + 8 prompts (box corners + hand landmarks) -> mask logits (1,1,256,256), iou (1,1)
import sys, torch, torch.nn as nn
import types
# Triton (GPU kernels for training) is not needed to run or export the model
_stub = types.ModuleType("efficientvit.models.nn.triton_rms_norm"); _stub.TritonRMSNorm2dFunc = None
sys.modules["efficientvit.models.nn.triton_rms_norm"] = _stub
sys.path.insert(0, "src")
from efficientvit.sam_model_zoo import create_efficientvit_sam_model
sys.path.insert(0, "src/applications/efficientvit_sam/deployment/onnx")
from export_decoder import DecoderOnnxModel

sam = create_efficientvit_sam_model("efficientvit-sam-l0", True, "l0.pt").eval()

class Encoder(nn.Module):
    def __init__(s, m):
        super().__init__(); s.enc = m.image_encoder
        s.register_buffer("mean", torch.tensor([123.675, 116.28, 103.53]).view(1, 3, 1, 1) / 255)
        s.register_buffer("std", torch.tensor([58.395, 57.12, 57.375]).view(1, 3, 1, 1) / 255)
    def forward(s, x): return s.enc((x - s.mean) / s.std)

class Decoder(nn.Module):
    def __init__(s, m):
        super().__init__(); s.d = DecoderOnnxModel(m, return_single_mask=False)
    def forward(s, emb, coords, labels):
        masks, iou = s.d(emb, coords, labels)
        return masks[:, :1], iou[:, :1]     # the single-mask output (what SAM uses for several prompts)

enc, dec = Encoder(sam).eval(), Decoder(sam).eval()
x = torch.rand(1, 3, 512, 512)
with torch.no_grad():
    e = enc(x); print("embeddings", tuple(e.shape))
    c = torch.rand(1, 8, 2) * 1024; l = torch.tensor([[2., 3., 1., 1., 1., 1., 1., 1.]])
    m, i = dec(e, c, l); print("mask", tuple(m.shape), "iou", tuple(i.shape))
    torch.onnx.export(enc, (x,), "hand_sam_encoder.onnx", opset_version=17, input_names=["image"], output_names=["embeddings"], dynamo=False)
    torch.onnx.export(dec, (e, c, l), "hand_sam_decoder.onnx", opset_version=17, input_names=["embeddings", "points", "labels"], output_names=["mask", "iou"], dynamo=False)
print("exported")
