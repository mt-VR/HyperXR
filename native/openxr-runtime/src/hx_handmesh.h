// The hand mesh and capsules of the Meta hand tracking extensions (XR_FB_hand_tracking_mesh,
// XR_FB_hand_tracking_capsules), built procedurally: an open hand in its bind pose, one capsule
// per bone (19 bones, the same ones the capsules extension reports), each capsule skinned to the
// joint its bone starts at. The bind pose comes from hxhands::toXrJoints on a canonical hand, so
// the mesh follows the joints the runtime reports exactly.
#pragma once

#include <math.h>
#include <string.h>

#include "hx_hands.h"

namespace hxhandmesh {

constexpr int kBones = 19;
constexpr int kSides = 8;                    // segments around each capsule
constexpr int kRings = 4;                    // cap ring, body start, body end, cap ring
constexpr int kVertsPerBone = 2 + kRings * kSides;
constexpr int kTrisPerBone = 2 * kSides + (kRings - 1) * kSides * 2;
constexpr int kVertices = kBones * kVertsPerBone;
constexpr int kIndices = kBones * kTrisPerBone * 3;
static_assert(kVertices < 32768, "indices are int16");

/** Bones as (from joint, to joint), XR_HAND_JOINT_* numbering: thumb 3, then 4 per finger. */
inline void bone(int i, int* from, int* to) {
    if (i < 3) { *from = 2 + i; *to = 3 + i; return; }
    int f = (i - 3) / 4, k = (i - 3) % 4;
    *from = 6 + f * 5 + k; *to = *from + 1;
}

/** Parent of each joint (palm and finger metacarpals hang from the wrist; the wrist is the root). */
inline int parentOf(int j) {
    if (j == 1) return 0x7FFFFFFF;           // XR_HAND_JOINT_MAX_ENUM_EXT: no parent
    if (j == 0 || j == 2 || j == 6 || j == 11 || j == 16 || j == 21) return 1;
    return j - 1;
}

/**
 * An open hand, palm down, fingers forward (-Z), wrist at the origin, metres. Average adult
 * proportions; the left hand is the mirror image of the right.
 */
inline void canonicalLandmarks(float lm[HX_HANDS_LANDMARKS][3], bool left) {
    static const float pts[HX_HANDS_LANDMARKS][3] = {
        {0, 0, 0},
        // thumb: out to the side and forward
        {-0.025f, -0.005f, -0.025f}, {-0.045f, -0.005f, -0.050f}, {-0.060f, -0.003f, -0.075f}, {-0.072f, 0, -0.095f},
        // index, middle, ring, little: MCP, PIP, DIP, tip
        {-0.025f, 0, -0.090f}, {-0.028f, 0, -0.130f}, {-0.030f, 0, -0.155f}, {-0.031f, 0, -0.177f},
        {-0.005f, 0, -0.095f}, {-0.005f, 0, -0.140f}, {-0.005f, 0, -0.168f}, {-0.005f, 0, -0.192f},
        {0.015f, 0, -0.090f}, {0.017f, 0, -0.132f}, {0.018f, 0, -0.158f}, {0.019f, 0, -0.180f},
        {0.033f, 0, -0.080f}, {0.037f, 0, -0.110f}, {0.039f, 0, -0.128f}, {0.041f, 0, -0.146f},
    };
    for (int i = 0; i < HX_HANDS_LANDMARKS; i++) {
        lm[i][0] = left ? -pts[i][0] : pts[i][0];
        lm[i][1] = pts[i][1];
        lm[i][2] = pts[i][2];
    }
}

struct V3 { float x, y, z; };
struct Mesh {
    hxhands::Joint bind[26];
    int parents[26];
    V3 pos[kVertices], normal[kVertices];
    float uv[kVertices][2];
    short blendIndex[kVertices];             // one joint per vertex (weight 1)
    short indices[kIndices];
};

inline V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline V3 operator*(V3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
inline float dot3(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline V3 cross3(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
inline V3 unit(V3 a) { float n = sqrtf(dot3(a, a)); return n > 1e-9f ? a * (1.0f / n) : V3{0, 1, 0}; }

/** Builds the mesh of one hand (false when the canonical hand is degenerate, which it is not). */
inline bool build(bool left, Mesh* m) {
    float lm[HX_HANDS_LANDMARKS][3];
    canonicalLandmarks(lm, left);
    if (!hxhands::toXrJoints(lm, left, m->bind)) return false;
    for (int j = 0; j < 26; j++) m->parents[j] = parentOf(j);
    int v = 0, n = 0;
    for (int b = 0; b < kBones; b++) {
        int ja, jb;
        bone(b, &ja, &jb);
        V3 a = {m->bind[ja].p.x, m->bind[ja].p.y, m->bind[ja].p.z};
        V3 c = {m->bind[jb].p.x, m->bind[jb].p.y, m->bind[jb].p.z};
        float ra = m->bind[ja].radius, rb = m->bind[jb].radius;
        V3 d = unit(c - a);
        V3 u = unit(cross3(d, fabsf(d.y) < 0.9f ? V3{0, 1, 0} : V3{1, 0, 0}));
        V3 w = cross3(d, u);
        int base = v;
        // poles, then rings: cap at a (45 degrees), body at a, body at c, cap at c
        m->pos[v] = a - d * ra; m->normal[v] = d * -1.0f; m->uv[v][0] = 0.5f; m->uv[v][1] = 0; m->blendIndex[v] = static_cast<short>(ja); v++;
        m->pos[v] = c + d * rb; m->normal[v] = d; m->uv[v][0] = 0.5f; m->uv[v][1] = 1; m->blendIndex[v] = static_cast<short>(ja); v++;
        const float s45 = 0.70710678f;
        V3 centres[kRings] = {a - d * (ra * s45), a, c, c + d * (rb * s45)};
        float radii[kRings] = {ra * s45, ra, rb, rb * s45};
        float along[kRings] = {-s45, 0, 0, s45};
        for (int r = 0; r < kRings; r++) {
            for (int k = 0; k < kSides; k++) {
                float t = 2.0f * static_cast<float>(M_PI) * k / kSides;
                V3 radial = u * cosf(t) + w * sinf(t);
                m->pos[v] = centres[r] + radial * radii[r];
                m->normal[v] = unit(radial * (r == 0 || r == 3 ? s45 : 1.0f) + d * along[r]);
                m->uv[v][0] = static_cast<float>(k) / kSides;
                m->uv[v][1] = (r + 1.0f) / (kRings + 1.0f);
                m->blendIndex[v] = static_cast<short>(ja);
                v++;
            }
        }
        auto ring = [&](int r, int k) { return static_cast<short>(base + 2 + r * kSides + (k % kSides)); };
        for (int k = 0; k < kSides; k++) {
            // cap at a (looking along -d), then the strips, then the cap at c; counter-clockwise from outside
            m->indices[n++] = static_cast<short>(base); m->indices[n++] = ring(0, k + 1); m->indices[n++] = ring(0, k);
            for (int r = 0; r + 1 < kRings; r++) {
                m->indices[n++] = ring(r, k); m->indices[n++] = ring(r, k + 1); m->indices[n++] = ring(r + 1, k + 1);
                m->indices[n++] = ring(r, k); m->indices[n++] = ring(r + 1, k + 1); m->indices[n++] = ring(r + 1, k);
            }
            m->indices[n++] = static_cast<short>(base + 1); m->indices[n++] = ring(kRings - 1, k); m->indices[n++] = ring(kRings - 1, k + 1);
        }
    }
    return v == kVertices && n == kIndices;
}

}  // namespace hxhandmesh
