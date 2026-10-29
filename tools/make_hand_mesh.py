"""Builds the skinned human arm and hand of the VR mode (app/src/main/assets/models/hand_skin.bin).

The shape is a signed distance field made of smooth-blended round cones (finger bones, forearm,
upper arm), a rounded palm with the thenar and hypothenar pads, knuckles, the wrist bone and a
T-shirt sleeve; marching cubes turns it into a surface, which is then reduced. Every vertex gets
normals from the field, ambient occlusion (the creases between fingers), a nail mask and up to four
bone weights for the 19 bones the app moves from the tracked joints.

Rest pose: right arm pointing forward (-z), palm down (-y), thumb to the left (-x), metres.
Run: python3 tools/make_hand_mesh.py app/src/main/assets/models/hand_skin.bin
"""
import struct
import sys

import numpy as np
import pyfqmr
from skimage import measure

J = np.array([
    [0.000, 0.000, 0.000],      # 0 wrist
    [-0.019, -0.006, -0.020],   # 1 thumb CMC
    [-0.041, -0.010, -0.046],   # 2 thumb MCP
    [-0.057, -0.009, -0.072],   # 3 thumb IP
    [-0.069, -0.007, -0.095],   # 4 thumb tip
    [-0.024, 0.000, -0.088],    # 5 index MCP
    [-0.027, 0.000, -0.130],    # 6 PIP
    [-0.029, 0.000, -0.155],    # 7 DIP
    [-0.030, 0.000, -0.176],    # 8 tip
    [-0.004, 0.000, -0.092],    # 9 middle MCP
    [-0.004, 0.000, -0.138],
    [-0.004, 0.000, -0.166],
    [-0.004, 0.000, -0.189],
    [0.015, 0.000, -0.087],     # 13 ring MCP
    [0.017, 0.000, -0.130],
    [0.019, 0.000, -0.156],
    [0.020, 0.000, -0.177],
    [0.032, -0.002, -0.078],    # 17 pinky MCP
    [0.037, -0.002, -0.110],
    [0.040, -0.002, -0.129],
    [0.043, -0.002, -0.147],
    [0.000, 0.000, 0.265],      # 21 elbow
    [0.000, 0.000, 0.560],      # 22 shoulder
], dtype=np.float64)
ELBOW, SHOULDER = 21, 22

# finger segments: (a, b, ra, rb); the last segment ends its round cap at the tip joint
FINGER_R = {
    0: [(0.0145, 0.0115), (0.0112, 0.0102), (0.0098, 0.0084)],
    1: [(0.0100, 0.0090), (0.0088, 0.0080), (0.0078, 0.0068)],
    2: [(0.0103, 0.0093), (0.0090, 0.0082), (0.0080, 0.0070)],
    3: [(0.0097, 0.0088), (0.0086, 0.0079), (0.0076, 0.0067)],
    4: [(0.0085, 0.0078), (0.0076, 0.0070), (0.0068, 0.0060)],
}
CHAINS = [[1, 2, 3, 4], [5, 6, 7, 8], [9, 10, 11, 12], [13, 14, 15, 16], [17, 18, 19, 20]]


def dot(a, b):
    return np.sum(a * b, axis=-1)


def round_cone(p, a, b, r1, r2):
    """Exact distance to a round cone (Inigo Quilez)."""
    ba = b - a
    l2 = float(ba @ ba)
    rr = r1 - r2
    a2 = l2 - rr * rr
    il2 = 1.0 / l2
    pa = p - a
    y = pa @ ba
    z = y - l2
    q = pa * l2 - np.outer(y, ba)
    x2 = dot(q, q)
    y2 = y * y * l2
    z2 = z * z * l2
    k = np.sign(rr) * rr * rr * x2
    d = (np.sqrt(np.maximum(x2 * a2 * il2, 0)) + y * rr) * il2 - r1
    d = np.where(np.sign(y) * a2 * y2 < k, np.sqrt(x2 + y2) * il2 - r1, d)
    d = np.where(np.sign(z) * a2 * z2 > k, np.sqrt(x2 + z2) * il2 - r2, d)
    return d


def ellipsoid(p, c, r):
    q = (p - c) / r
    return (np.sqrt(dot(q, q)) - 1.0) * r.min()


def sphere(p, c, r):
    return np.sqrt(dot(p - c, p - c)) - r


def smin(a, b, k):
    h = np.maximum(k - np.abs(a - b), 0.0) / k
    return np.minimum(a, b) - h * h * k * 0.25


def palm_box(p):
    # palm frame = world axes in the rest pose; centre between the wrist and the knuckles
    cz = -0.047
    x = p[:, 0] - 0.003
    y = p[:, 1] + 0.0015
    z = p[:, 2] - cz
    t = np.clip((-p[:, 2] - 0.008) / 0.075, 0, 1)
    hx = 0.027 + 0.0095 * t
    y = y - 0.0035 * (1 - np.clip((x / 0.036) ** 2, 0, 1))   # the back of the hand arches
    hy = np.full_like(x, 0.0118)
    hz = np.full_like(x, 0.039)
    r = 0.0095
    qx = np.abs(x) - (hx - r)
    qy = np.abs(y) - (hy - r)
    qz = np.abs(z) - (hz - r)
    out = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2 + np.maximum(qz, 0) ** 2)
    return out + np.minimum(np.maximum(qx, np.maximum(qy, qz)), 0) - r


def finger_segments():
    """(bone index, a, b, ra, rb) of every finger and thumb segment."""
    out = []
    bone = 4
    for f, chain in enumerate(CHAINS):
        for s in range(3):
            a = J[chain[s]].copy()
            b = J[chain[s + 1]].copy()
            ra, rb = FINGER_R[f][s]
            if s == 2:   # the fingertip's surface ends at the tip joint
                d = b - a
                b = b - d / np.linalg.norm(d) * rb * 0.9
            out.append((bone, a, b, ra, rb))
            bone += 1
    return out


SEGS = finger_segments()


def regions(p):
    """Distance to each body part: the field is their blend; the nearest one owns a vertex."""
    flat = p.copy()
    flat[:, 1] = flat[:, 1] / 0.9   # fingers a little wider than tall
    parts = {}
    # arm
    fa = p.copy()
    t = np.clip(p[:, 2] / 0.26, 0, 1)
    fa[:, 0] = fa[:, 0] / (1.34 - 0.22 * t)
    fa[:, 1] = fa[:, 1] / (0.96 + 0.06 * t)
    fore = round_cone(fa, np.array([0, 0, 0.004]), J[ELBOW], 0.0225, 0.037)
    fore = smin(fore, ellipsoid(p, np.array([0.004, 0.006, 0.185]), np.array([0.046, 0.041, 0.085])), 0.03)
    fore = smin(fore, sphere(p, np.array([0.026, 0.005, 0.010]), 0.0052), 0.010)   # the wrist bone
    parts['fore'] = fore
    upper = round_cone(p, J[ELBOW], J[SHOULDER], 0.040, 0.047)
    upper = smin(upper, sphere(p, np.array([0.012, 0.030, 0.262]), 0.013), 0.018)  # the elbow
    parts['upper'] = upper
    palm = palm_box(p)
    palm = smin(palm, ellipsoid(p, np.array([0.029, -0.006, -0.042]), np.array([0.012, 0.010, 0.030])), 0.012)  # hypothenar
    for c in (5, 9, 13, 17):   # knuckles
        palm = smin(palm, sphere(p, J[c] + np.array([0, 0.005, 0.004]), 0.0088), 0.007)
    parts['palm'] = palm
    thenar = ellipsoid(p, np.array([-0.023, -0.009, -0.036]), np.array([0.017, 0.012, 0.027]))
    for bone, a, b, ra, rb in SEGS:
        d = round_cone(flat, a * [1, 1 / 0.9, 1], b * [1, 1 / 0.9, 1], ra, rb)
        if bone in (4,):
            d = smin(d, thenar, 0.012)
        # a slightly thicker joint at the start of the middle and last bones
        if bone not in (4, 7, 10, 13, 16):
            d = smin(d, sphere(p, a, ra * 1.025), 0.005)
        parts[bone] = d
    return parts


def field(p):
    parts = regions(p)
    hand = parts['palm']
    for bone, *_ in SEGS:
        k = 0.016 if bone == 4 else (0.0085 if bone in (7, 10, 13, 16) else 0.003)
        hand = smin(hand, parts[bone], k)
    arm = smin(parts['fore'], parts['upper'], 0.03)
    body = smin(hand, arm, 0.022)
    # the T-shirt sleeve over the upper arm
    sleeve = round_cone(p, np.array([0, 0.004, 0.37]), J[SHOULDER], 0.0505, 0.058)
    return np.minimum(body, sleeve)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def build(out_path, cell=0.0021, target_faces=17000):
    lo = np.array([-0.095, -0.072, -0.200])
    hi = np.array([0.095, 0.072, 0.600])
    n = np.ceil((hi - lo) / cell).astype(int) + 1
    xs = [lo[i] + np.arange(n[i]) * cell for i in range(3)]
    vol = np.empty((n[0], n[1], n[2]), dtype=np.float32)
    gy, gz = np.meshgrid(xs[1], xs[2], indexing='ij')
    for i, x in enumerate(xs[0]):
        pts = np.stack([np.full(gy.size, x), gy.ravel(), gz.ravel()], axis=1)
        vol[i] = field(pts).reshape(gy.shape)
    verts, faces, _, _ = measure.marching_cubes(vol, 0.0, spacing=(cell, cell, cell))
    verts += lo
    # marching cubes winds faces for an outward normal along the gradient of increasing value;
    # our field grows outward, so flip to counter-clockwise from outside
    faces = faces[:, ::-1]
    print('marching cubes:', len(verts), 'vertices', len(faces), 'faces')
    s = pyfqmr.Simplify()
    s.setMesh(verts, faces)
    s.simplify_mesh(target_count=target_faces, aggressiveness=5, preserve_border=True, verbose=False)
    verts, faces, _ = s.getMesh()
    verts = verts.astype(np.float64)
    print('reduced:', len(verts), 'vertices', len(faces), 'faces')

    # normals from the field
    e = 0.0006
    nrm = np.zeros_like(verts)
    for k in range(3):
        d = np.zeros(3)
        d[k] = e
        nrm[:, k] = field(verts + d) - field(verts - d)
    nrm /= np.linalg.norm(nrm, axis=1, keepdims=True) + 1e-12
    # make sure the faces wind outward (normals and winding agree)
    v0, v1, v2 = verts[faces[:, 0]], verts[faces[:, 1]], verts[faces[:, 2]]
    fn = np.cross(v1 - v0, v2 - v0)
    if np.mean(dot(fn, nrm[faces[:, 0]])) < 0:
        faces = faces[:, ::-1]

    # ambient occlusion: how much the field closes in along the normal
    occ = np.zeros(len(verts))
    for dlt in (0.003, 0.006, 0.011):
        occ += np.clip((dlt - field(verts + nrm * dlt)) / dlt, 0, 1) / 3
    ao = np.clip(1.0 - occ * 1.15, 0.3, 1.0)

    parts = regions(verts)
    names = ['upper', 'fore', 'palm'] + [b for b, *_ in SEGS]
    dist = np.stack([parts[k] for k in names], axis=1)
    primary = np.argmin(dist, axis=1)

    # nails on the back of the last bones
    nail = np.zeros(len(verts))
    for bone, a, b, ra, rb in SEGS:
        if bone not in (6, 9, 12, 15, 18):
            continue
        tipj = J[CHAINS[(bone - 4) // 3][3]]
        axis = tipj - a
        L = np.linalg.norm(axis)
        axis /= L
        rel = verts - a
        t = rel @ axis / L
        side = np.cross(axis, [0, 1, 0])
        side /= np.linalg.norm(side)
        up = np.cross(side, axis)
        r = ra + (rb - ra) * np.clip(t, 0, 1)
        lx = (rel @ side) / r
        ly = (rel @ up) / r
        m = smoothstep(0.30, 0.42, t) * (1 - smoothstep(0.96, 1.04, t)) * smoothstep(0.35, 0.6, ly) * (1 - smoothstep(0.62, 0.82, np.abs(lx)))
        nail = np.maximum(nail, m * (np.argmin(dist, axis=1) == names.index(bone)))
    cloth = smoothstep(0.366, 0.372, verts[:, 2]) * (field(verts) <= 0.0006)
    cloth = np.maximum(cloth, (round_cone(verts, np.array([0, 0.004, 0.37]), J[SHOULDER], 0.0505, 0.058) < 0.0008).astype(float))

    # bone weights: the nearest part, blended with its neighbours across each joint
    NB = 19
    W = np.zeros((len(verts), NB))
    parent = {4: 3, 7: 3, 10: 3, 13: 3, 16: 3}
    for bone, *_ in SEGS:
        if bone not in parent:
            parent[bone] = bone - 1
    child = {}
    for b_, p_ in parent.items():
        if p_ != 3:
            child[p_] = b_
    seg_of = {b: (a, bb, ra, rb) for b, a, bb, ra, rb in SEGS}
    for i in range(len(verts)):
        v = verts[i]
        k = names[primary[i]]
        w = {}
        if k == 'upper':
            s_ = v[2] - J[ELBOW][2]      # distance above the elbow
            wf = 0.5 * (1 - smoothstep(0, 0.035, s_))
            w[0] = 1 - wf
            w[1] = wf
        elif k == 'fore':
            t = np.clip((J[ELBOW][2] - v[2]) / (J[ELBOW][2] - J[0][2]), 0, 1)   # 0 elbow .. 1 wrist
            ww = smoothstep(0.15, 0.95, t)
            to_elbow = v[2] - J[ELBOW][2]
            wu = 0.5 * smoothstep(-0.035, 0, to_elbow)
            wp = 0.5 * smoothstep(-0.022, 0.006, -v[2])
            rest = 1 - wu - wp
            w[0] = wu
            w[1] = rest * (1 - ww)
            w[2] = rest * ww
            w[3] = wp
        elif k == 'palm':
            wf = 0.5 * (1 - smoothstep(0, 0.022, -v[2]))     # back to the forearm at the wrist
            best, bw = None, 0.0
            for fb in (7, 10, 13, 16, 4):
                a, bb, ra, rb = seg_of[fb]
                ax = (bb - a) / np.linalg.norm(bb - a)
                s_ = (v - a) @ ax
                lat = np.linalg.norm(v - a - ax * s_)
                if lat > ra * 1.8:
                    continue
                lam = 0.024 if fb == 4 else 0.016
                x = 0.5 * smoothstep(-lam, 0.002, s_)
                if x > bw:
                    best, bw = fb, x
            w[2] = wf
            w[3] = 1 - wf - bw
            if best is not None:
                w[best] = bw
        else:
            b = k
            a, bb, ra, rb = seg_of[b]
            L = np.linalg.norm(bb - a)
            ax = (bb - a) / L
            s0 = (v - a) @ ax
            lam = 0.02 if b == 4 else ra * 0.95
            wp = 0.5 * (1 - smoothstep(-0.002, lam, s0))
            wc = 0.0
            if b in child:
                lam2 = rb * 0.95
                wc = 0.5 * (1 - smoothstep(-0.002, lam2, L - s0))
            w[parent[b]] = wp
            if b in child:
                w[child[b]] = wc
            w[b] = 1 - wp - wc
        for bi, x in w.items():
            W[i, bi] += max(x, 0.0)
    W /= W.sum(axis=1, keepdims=True)
    idx = np.argsort(-W, axis=1)[:, :4]
    wts = np.take_along_axis(W, idx, axis=1)
    wts /= wts.sum(axis=1, keepdims=True)

    with open(out_path, 'wb') as f:
        f.write(struct.pack('<5i', 0x4D485848, 1, len(verts), faces.size, len(J)))
        f.write(J.astype('<f4').tobytes())
        data = np.concatenate([verts, nrm, nail[:, None], cloth[:, None], ao[:, None], idx.astype(np.float64), wts], axis=1)
        f.write(data.astype('<f4').tobytes())
        f.write(faces.astype('<i4').ravel().tobytes())
    print('wrote', out_path, len(verts), 'vertices', len(faces), 'faces')
    return verts, faces, nrm, nail, cloth, ao


if __name__ == '__main__':
    build(sys.argv[1] if len(sys.argv) > 1 else 'hand_skin.bin')
