// Hands from the HyperMagic app to its OpenXR runtime running inside a game.
//
// While an OpenXR game runs, HyperMagic's hand bridge service tracks the hands with the phone's
// camera and publishes them in a small shared file (HxHandShared), which the runtime maps through
// content://com.hypermagic.runtime/hands. Plain C layout, the same on both sides; a sequence
// counter (odd while writing) lets the reader take a consistent copy without locks.
//
// The runtime turns the 21 landmarks (wrist + 4 per finger) into OpenXR's 26 hand joints
// (XR_EXT_hand_tracking): palm and metacarpal joints are placed along the palm, every joint gets
// an orientation from its bone and the back of the hand.
#pragma once

#include <stdint.h>
#include <math.h>

#define HX_HANDS_MAGIC 0x31484858u  // "XHH1"
#define HX_HANDS_LANDMARKS 21

struct HxHandShared {
    uint32_t magic;
    uint32_t seq;                 // odd while the app writes
    int64_t timeNs[2];            // CLOCK_MONOTONIC time the joints are for; [0] left, [1] right
    uint32_t tracked[2];
    float confidence[2];
    float pinch[2];               // 0..1 thumb-index closure
    float joints[2][HX_HANDS_LANDMARKS][3];  // camera space, metres: x right, y up, -z forward
    // written by the runtime (the reader): the app runs the camera only while a game wants hands
    int64_t readerAliveNs;        // a runtime instance exists (CLOCK_MONOTONIC)
    int64_t readerWantsHandsNs;   // the game has a hand tracker
};

#ifdef __cplusplus
namespace hxhands {

enum { WRIST = 0, THUMB_CMC = 1, INDEX_MCP = 5, MIDDLE_MCP = 9, RING_MCP = 13, PINKY_MCP = 17 };

struct V { float x, y, z; };
inline V v(const float* p) { return {p[0], p[1], p[2]}; }
inline V sub(V a, V b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline V add(V a, V b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline V mul(V a, float s) { return {a.x * s, a.y * s, a.z * s}; }
inline float dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline V cross(V a, V b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
inline float len(V a) { return sqrtf(dot(a, a)); }
inline V norm(V a, V fallback) { float n = len(a); return n > 1e-6f ? mul(a, 1.0f / n) : fallback; }
inline V lerp(V a, V b, float t) { return add(a, mul(sub(b, a), t)); }

struct Q { float x, y, z, w; };

/** Rotation whose columns are the axes x, y, z (orthonormal, right-handed). */
inline Q fromAxes(V x, V y, V z) {
    float m00 = x.x, m11 = y.y, m22 = z.z, tr = m00 + m11 + m22;
    Q q;
    if (tr > 0) {
        float s = sqrtf(tr + 1.0f) * 2;
        q = {(y.z - z.y) / s, (z.x - x.z) / s, (x.y - y.x) / s, 0.25f * s};
    } else if (m00 > m11 && m00 > m22) {
        float s = sqrtf(1.0f + m00 - m11 - m22) * 2;
        q = {0.25f * s, (y.x + x.y) / s, (z.x + x.z) / s, (y.z - z.y) / s};
    } else if (m11 > m22) {
        float s = sqrtf(1.0f + m11 - m00 - m22) * 2;
        q = {(y.x + x.y) / s, 0.25f * s, (z.y + y.z) / s, (z.x - x.z) / s};
    } else {
        float s = sqrtf(1.0f + m22 - m00 - m11) * 2;
        q = {(z.x + x.z) / s, (z.y + y.z) / s, 0.25f * s, (x.y - y.x) / s};
    }
    float n = sqrtf(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    return {q.x / n, q.y / n, q.z / n, q.w / n};
}

/**
 * Orientation of a bone running from [from] to [to] (towards the fingertip) on a hand whose back
 * faces [dorsal]: +Z points back along the bone, +Y out of the back of the hand (OpenXR's
 * convention for hand joints).
 */
inline Q boneOrientation(V from, V to, V dorsal) {
    V z = norm(sub(from, to), {0, 0, 1});
    V y = norm(sub(dorsal, mul(z, dot(dorsal, z))), {0, 1, 0});
    if (fabsf(dot(y, z)) > 0.99f) y = norm(cross(z, {1, 0, 0}), {0, 1, 0});
    V x = cross(y, z);
    return fromAxes(x, y, z);
}

struct Joint { V p; Q q; float radius; };

/**
 * 21 landmarks -> the 26 joints of XR_HAND_JOINT_SET_DEFAULT_EXT, in the landmarks' space.
 * Returns false when the landmarks are degenerate (no palm).
 */
inline bool toXrJoints(const float lm[HX_HANDS_LANDMARKS][3], bool left, Joint out[26]) {
    V wrist = v(lm[WRIST]), idx = v(lm[INDEX_MCP]), mid = v(lm[MIDDLE_MCP]), pinky = v(lm[PINKY_MCP]);
    float palmLen = len(sub(mid, wrist));
    if (!(palmLen > 0.02f && palmLen < 0.3f)) return false;
    // back of the hand: the palm plane's normal, signed by the hand (index and little finger
    // swap sides between a left and a right hand)
    V n = cross(sub(idx, wrist), sub(pinky, wrist));
    V dorsal = norm(left ? n : mul(n, -1), {0, 1, 0});
    float scale = palmLen / 0.095f;  // radii follow the measured hand size
    auto set = [&](int j, V p, Q q, float r) { out[j] = {p, q, r * scale}; };
    // palm: centre of the middle metacarpal; wrist with the palm's orientation
    Q palmQ = boneOrientation(wrist, mid, dorsal);
    set(0, lerp(wrist, mid, 0.5f), palmQ, 0.032f);
    set(1, wrist, palmQ, 0.027f);
    // thumb: metacarpal (CMC), proximal (MCP), distal (IP), tip
    for (int k = 0; k < 4; k++) {
        V p = v(lm[THUMB_CMC + k]);
        V a = k < 3 ? p : v(lm[THUMB_CMC + 2]);
        V b = k < 3 ? v(lm[THUMB_CMC + k + 1]) : p;
        static const float r[4] = {0.019f, 0.015f, 0.012f, 0.010f};
        set(2 + k, p, boneOrientation(a, b, dorsal), r[k]);
    }
    // fingers: metacarpal base (along the palm, near the wrist), proximal (MCP), intermediate
    // (PIP), distal (DIP), tip
    static const int base[4] = {INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP};
    static const float fr[5] = {0.021f, 0.011f, 0.010f, 0.009f, 0.008f};
    for (int f = 0; f < 4; f++) {
        int j0 = 6 + f * 5;
        V mcp = v(lm[base[f]]);
        V meta = lerp(wrist, mcp, 0.12f);
        set(j0, meta, boneOrientation(meta, mcp, dorsal), fr[0]);
        for (int k = 0; k < 4; k++) {
            V p = v(lm[base[f] + k]);
            V a = k < 3 ? p : v(lm[base[f] + 2]);
            V b = k < 3 ? v(lm[base[f] + k + 1]) : p;
            set(j0 + 1 + k, p, boneOrientation(a, b, dorsal), fr[1 + k]);
        }
    }
    return true;
}

}  // namespace hxhands
#endif
