"""HybridMagic's hand tracker for the PC: the phone's RTMPose pipeline (OpenMMLab, Apache-2.0),
with the work a phone cannot afford.

The phone (RtmHandEngine.kt) runs the detector only while a hand is missing, at 320 px, one pose
inference per hand. Here, on the PC's GPU:
  - the detector runs on every frame, and a second time on the centre of the image zoomed 2x, so
    small and far hands are found at once;
  - every hand is inferred twice, as seen and mirrored (flip test-time augmentation), and the two
    answers are averaged: steadier joints, fewer wrong fingers;
  - a second pass re-crops each hand around the joints the first pass found, so the model sees
    the hand centred and at the size it was trained with;
  - joints are decoded below the pixel (the soft peak of SimCC instead of its arg max).
The geometry (crops, presence, thresholds) is the phone's own, so both behave the same way.

The extras are dropped one by one when a frame takes longer than the phone waits (a PC without a
usable GPU), and come back when there is time again: [level] 3 is everything, 0 is the phone's
own pipeline (still at PC speed).
"""
from __future__ import annotations

import time
from dataclasses import dataclass

import cv2
import numpy as np
import onnxruntime as ort

POSE_INPUT = 256
BINS = 512
SPLIT = 2.0
DET_INPUT = 320
JOINTS = 21
MIN_DETECTION = 0.3

# hand joints (MediaPipe order, as HandJoint.kt)
WRIST, INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP = 0, 5, 9, 13, 17
PALM = [WRIST, INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP]
PALM_X = np.array([0.0, -0.30, 0.0, 0.22, 0.45], np.float32)
PALM_Y = np.array([0.0, -0.92, -1.0, -0.91, -0.76], np.float32)

MEAN_RGB = np.array([123.675, 116.28, 103.53], np.float32)
STD_RGB = np.array([58.395, 57.12, 57.375], np.float32)


@dataclass
class Crop:
    cx: float
    cy: float
    side: float


@dataclass
class Hand:
    presence: float
    joints: np.ndarray  # (21, 2) frame pixels
    conf: np.ndarray    # (21,)


def crop_from_box(x0, y0, x1, y1) -> Crop:
    return Crop((x0 + x1) / 2, (y0 + y1) / 2, max(x1 - x0, y1 - y0) * 1.25)


def palm_scale(p: np.ndarray) -> float:
    """RtmHand.palmScale: the palm's length as if facing the camera (larger singular value of the
    affine fit of a palm template to the five knuckles)."""
    pts = p[PALM]
    pc = pts - pts.mean(axis=0)
    q = np.stack([PALM_X - PALM_X.mean(), PALM_Y - PALM_Y.mean()], axis=1)
    a = (pc.T @ q) @ np.linalg.inv(q.T @ q)
    big = float(np.linalg.svd(a, compute_uv=False)[0])
    return max(big, float(np.hypot(*(p[MIDDLE_MCP] - p[WRIST]))), 1.0)


def crop_from_joints(p: np.ndarray) -> Crop:
    x0, y0 = p.min(axis=0)
    x1, y1 = p.max(axis=0)
    c = crop_from_box(x0, y0, x1, y1)
    return Crop(c.cx, c.cy, max(c.side * 1.1, palm_scale(p) * 2.6))


def presence_of(conf: np.ndarray) -> float:
    return float(np.clip((conf.mean() - 0.45) / 0.2, 0.0, 1.0))


def providers() -> list[str]:
    """The GPU first: CUDA (NVIDIA), DirectML (any GPU on Windows), then the CPU."""
    have = ort.get_available_providers()
    order = ["CUDAExecutionProvider", "DmlExecutionProvider", "CoreMLExecutionProvider", "CPUExecutionProvider"]
    return [p for p in order if p in have] or have


def _session(path: str, provs: list[str]) -> ort.InferenceSession:
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    if "DmlExecutionProvider" in provs:
        # DirectML wants these off
        so.enable_mem_pattern = False
        so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    return ort.InferenceSession(path, so, providers=provs)


class HandEngine:
    def __init__(self, detector_path: str, pose_path: str, provs: list[str] | None = None):
        provs = provs or providers()
        self.det = _session(detector_path, provs)
        self.pose = _session(pose_path, provs)
        self.device = self.pose.get_providers()[0].replace("ExecutionProvider", "")
        self.tracks: list[Crop] = []
        self.last_ms = 0.0
        self.avg_ms = 0.0
        self.level = 3
        self._frame = 0
        # warm-up: the first GPU run compiles kernels
        self.pose.run(None, {"input": np.zeros((2, 3, POSE_INPUT, POSE_INPUT), np.float32)})
        self.det.run(None, {"input": np.zeros((1, 3, DET_INPUT, DET_INPUT), np.float32)})

    def reset(self):
        self.tracks = []

    # ------------------------------------------------------------------ detection
    def _detect(self, img: np.ndarray, x0: float = 0, y0: float = 0, zoom: float = 1.0) -> list[np.ndarray]:
        """RTMDet-nano on img (BGR) seen from (x0, y0) with [zoom]: boxes in img pixels, best first."""
        h, w = img.shape[:2]
        s = DET_INPUT / max(w / zoom, h / zoom) * 1.0
        m = np.array([[s, 0, -x0 * s], [0, s, -y0 * s]], np.float32)
        lb = cv2.warpAffine(img, m, (DET_INPUT, DET_INPUT), flags=cv2.INTER_LINEAR, borderValue=(114, 114, 114))
        # BGR planes, normalised with the same numbers as RGB but in BGR order (as trained)
        x = (lb.astype(np.float32) - MEAN_RGB[::-1]) / STD_RGB[::-1]
        x = x.transpose(2, 0, 1)[None]
        dets = self.det.run(None, {"input": np.ascontiguousarray(x)})[0][0]
        out = []
        for d in dets:
            if d[4] < MIN_DETECTION:
                continue
            out.append(np.array([d[0] / s + x0, d[1] / s + y0, d[2] / s + x0, d[3] / s + y0, d[4]], np.float32))
        out.sort(key=lambda b: -b[4])
        return out

    # ------------------------------------------------------------------ pose
    def _crop_tensor(self, rgb: np.ndarray, c: Crop) -> np.ndarray:
        k = POSE_INPUT / c.side
        m = np.array([[k, 0, -(c.cx - c.side / 2) * k], [0, k, -(c.cy - c.side / 2) * k]], np.float32)
        patch = cv2.warpAffine(rgb, m, (POSE_INPUT, POSE_INPUT), flags=cv2.INTER_LINEAR, borderValue=(0, 0, 0))
        return ((patch.astype(np.float32) - MEAN_RGB) / STD_RGB).transpose(2, 0, 1)

    @staticmethod
    def _soft_peak(v: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Per joint: the peak bin refined by a softmax over its neighbours, and the peak value."""
        idx = v.argmax(axis=1)
        val = v[np.arange(len(v)), idx]
        out = np.empty(len(v), np.float32)
        for j, b in enumerate(idx):
            lo, hi = max(0, b - 3), min(BINS, b + 4)
            seg = v[j, lo:hi]
            e = np.exp((seg - seg.max()) * 20.0)
            out[j] = float((e * np.arange(lo, hi)).sum() / e.sum())
        return out, val

    def _pose(self, rgb: np.ndarray, crops: list[Crop], flip: bool = True) -> list[tuple[np.ndarray, np.ndarray]]:
        """Joints and confidences for each crop, averaged with the mirrored view (one batch)."""
        if not crops:
            return []
        t = [self._crop_tensor(rgb, c) for c in crops]
        batch = np.stack(t + ([x[:, :, ::-1] for x in t] if flip else [])).astype(np.float32)
        sx, sy = self.pose.run(None, {"input": np.ascontiguousarray(batch)})
        n = len(crops)
        out = []
        for i, c in enumerate(crops):
            # the mirrored view's x axis runs backwards: bin b there is bin (BINS - 1 - b) here
            vx = (sx[i] + sx[n + i][:, ::-1]) / 2 if flip else sx[i]
            vy = (sy[i] + sy[n + i]) / 2 if flip else sy[i]
            bx, cxv = self._soft_peak(vx)
            by, cyv = self._soft_peak(vy)
            u = bx / SPLIT
            v = by / SPLIT
            joints = np.stack([c.cx - c.side / 2 + u / POSE_INPUT * c.side, c.cy - c.side / 2 + v / POSE_INPUT * c.side], axis=1)
            out.append((joints.astype(np.float32), np.minimum(cxv, cyv).astype(np.float32)))
        return out

    # ------------------------------------------------------------------ frame
    #: the phone waits 70 ms for an answer, Wi-Fi included: the PC aims well under that
    BUDGET_MS = 35.0

    def _adapt(self):
        a = 0.85 * self.avg_ms + 0.15 * self.last_ms if self.avg_ms else self.last_ms
        self.avg_ms = a
        if a > self.BUDGET_MS and self.level > 0:
            self.level -= 1
            self.avg_ms = 0.0
        elif a < self.BUDGET_MS * 0.45 and self.level < 3 and self._frame % 60 == 0:
            self.level += 1
            self.avg_ms = 0.0

    def process(self, bgr: np.ndarray) -> list[Hand]:
        t0 = time.perf_counter()
        self._frame += 1
        full = self.level
        h, w = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        crops = list(self.tracks)
        tracked = [True] * len(crops)
        # every frame: the whole image, and its centre zoomed 2x for small hands (at the lowest
        # level, like the phone: only while a hand is missing)
        boxes = []
        if full >= 1 or len(crops) < 2:
            boxes = self._detect(bgr)
            if full >= 1 and len(boxes) + len(crops) < 2:
                boxes += self._detect(bgr, w * 0.25, h * 0.25, 2.0)
        for b in boxes:
            if len(crops) >= 2:
                break
            c = crop_from_box(b[0], b[1], b[2], b[3])
            if all(np.hypot(o.cx - c.cx, o.cy - c.cy) >= o.side * 0.35 for o in crops):
                crops.append(c)
                tracked.append(False)
        first = self._pose(rgb, crops, flip=full >= 2)
        # second pass: each hand re-cropped around what the first pass found
        second = self._pose(rgb, [crop_from_joints(j) for j, _ in first], flip=True) if full >= 3 else first
        hands: list[Hand] = []
        self.tracks = []
        for k, c in enumerate(crops):
            j1, c1 = first[k]
            j2, c2 = second[k]
            p1, p2 = presence_of(c1), presence_of(c2)
            joints, conf, pres = (j2, c2, p2) if p2 >= p1 else (j1, c1, p1)
            # new hands must look clearly like one; tracked ones are kept through blur
            if pres < (0.25 if tracked[k] else 0.5):
                continue
            if any(np.hypot(*(o.joints[0] - joints[0])) < c.side * 0.15 for o in hands):
                continue
            hands.append(Hand(pres, joints, conf))
            self.tracks.append(crop_from_joints(joints))
        self.last_ms = (time.perf_counter() - t0) * 1000
        self._adapt()
        return hands
