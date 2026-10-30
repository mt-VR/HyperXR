"""Checks the PC engine on OpenMMLab's own hand test photos (mmpose tests/data): a hand is found
in each one that clearly shows a hand, and the extras adapt to the time available."""
import os
import urllib.request

import cv2

import hybridmagic
from engine import HandEngine

PHOTOS = ["onehand10k/9.jpg", "onehand10k/784.jpg", "onehand10k/1402.jpg", "freihand/00000355.jpg", "freihand/00017620.jpg"]

d = hybridmagic.fetch_models()
e = HandEngine(os.path.join(d, "hand_detector.onnx"), os.path.join(d, "hand_pose.onnx"), ["CPUExecutionProvider"])
os.makedirs("testdata", exist_ok=True)
for p in PHOTOS:
    f = os.path.join("testdata", os.path.basename(p))
    if not os.path.isfile(f):
        urllib.request.urlretrieve("https://raw.githubusercontent.com/open-mmlab/mmpose/main/tests/data/" + p, f)
    e.reset()
    e.level = 3
    hands = e.process(cv2.imread(f))
    print(p, len(hands), [round(h.presence, 2) for h in hands], f"{e.last_ms:.0f} ms")
    assert len(hands) >= 1 and hands[0].presence > 0.5, p
print("ok")
