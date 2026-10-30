// Controller render model for XR_FB_render_model: HyperMagic's own simple controller (a handle and
// a tracking ring), written as a binary glTF 2.0 file (.glb) in code. Meta's controller models are
// Meta's; this one is drawn from scratch. Model space: the controller's grip, -Z forward, +Y up.
#pragma once

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace hxrendermodel {

constexpr int kHandleSides = 16;
constexpr int kRingMajor = 24, kRingMinor = 8;
constexpr int kVertices = kHandleSides * 2 + 2 + kRingMajor * kRingMinor;
constexpr int kIndices = kHandleSides * 6 + kHandleSides * 6 + kRingMajor * kRingMinor * 6;
static_assert(kVertices < 65536, "indices are uint16");

struct Geometry {
    float pos[kVertices][3], normal[kVertices][3];
    uint16_t index[kIndices];
    float min[3], max[3];
};

inline void rotX(float a, float v[3]) {
    float c = cosf(a), s = sinf(a), y = v[1] * c - v[2] * s, z = v[1] * s + v[2] * c;
    v[1] = y; v[2] = z;
}

/** The controller of one hand: the ring leans out to the side of the thumb. */
inline void build(bool left, Geometry* g) {
    int v = 0, n = 0;
    const float pi = 3.14159265f;
    const float tilt = 0.6f;                 // the handle leans back like a held grip
    // handle: tapered cylinder from y = -0.06 to y = 0.04, tilted about X
    for (int r = 0; r < 2; r++) {
        float y = r == 0 ? -0.06f : 0.04f, rad = r == 0 ? 0.017f : 0.021f;
        for (int k = 0; k < kHandleSides; k++) {
            float a = 2 * pi * k / kHandleSides;
            float p[3] = {rad * cosf(a), y, rad * sinf(a)}, nn[3] = {cosf(a), 0, sinf(a)};
            rotX(tilt, p); rotX(tilt, nn);
            memcpy(g->pos[v], p, sizeof(p)); memcpy(g->normal[v], nn, sizeof(nn)); v++;
        }
    }
    int bottom = v, top = v + 1;
    float pb[3] = {0, -0.06f, 0}, pt[3] = {0, 0.04f, 0}, nb[3] = {0, -1, 0}, nt[3] = {0, 1, 0};
    rotX(tilt, pb); rotX(tilt, pt); rotX(tilt, nb); rotX(tilt, nt);
    memcpy(g->pos[v], pb, sizeof(pb)); memcpy(g->normal[v], nb, sizeof(nb)); v++;
    memcpy(g->pos[v], pt, sizeof(pt)); memcpy(g->normal[v], nt, sizeof(nt)); v++;
    for (int k = 0; k < kHandleSides; k++) {
        uint16_t a = static_cast<uint16_t>(k), b = static_cast<uint16_t>((k + 1) % kHandleSides);
        uint16_t c = static_cast<uint16_t>(kHandleSides + k), d = static_cast<uint16_t>(kHandleSides + (k + 1) % kHandleSides);
        // side (counter-clockwise from outside), then the two caps
        g->index[n++] = a; g->index[n++] = c; g->index[n++] = b;
        g->index[n++] = b; g->index[n++] = c; g->index[n++] = d;
        g->index[n++] = static_cast<uint16_t>(bottom); g->index[n++] = a; g->index[n++] = b;
        g->index[n++] = static_cast<uint16_t>(top); g->index[n++] = d; g->index[n++] = c;
    }
    // tracking ring: a torus around the top of the handle, leaning to the thumb side
    const float R = 0.045f, rr = 0.006f, side = left ? 0.025f : -0.025f;
    int ring0 = v;
    for (int i = 0; i < kRingMajor; i++) {
        float u = 2 * pi * i / kRingMajor;
        for (int j = 0; j < kRingMinor; j++) {
            float w = 2 * pi * j / kRingMinor;
            float nn[3] = {cosf(u) * cosf(w), sinf(w), sinf(u) * cosf(w)};
            float p[3] = {side + (R + rr * cosf(w)) * cosf(u), 0.05f + rr * sinf(w), (R + rr * cosf(w)) * sinf(u)};
            rotX(tilt, p); rotX(tilt, nn);
            memcpy(g->pos[v], p, sizeof(p)); memcpy(g->normal[v], nn, sizeof(nn)); v++;
        }
    }
    for (int i = 0; i < kRingMajor; i++)
        for (int j = 0; j < kRingMinor; j++) {
            auto at = [&](int a, int b) { return static_cast<uint16_t>(ring0 + (a % kRingMajor) * kRingMinor + (b % kRingMinor)); };
            g->index[n++] = at(i, j); g->index[n++] = at(i, j + 1); g->index[n++] = at(i + 1, j + 1);
            g->index[n++] = at(i, j); g->index[n++] = at(i + 1, j + 1); g->index[n++] = at(i + 1, j);
        }
    for (int c = 0; c < 3; c++) { g->min[c] = 1e9f; g->max[c] = -1e9f; }
    for (int i = 0; i < v; i++)
        for (int c = 0; c < 3; c++) { g->min[c] = fminf(g->min[c], g->pos[i][c]); g->max[c] = fmaxf(g->max[c], g->pos[i][c]); }
}

inline void put32(uint8_t* p, uint32_t x) { memcpy(p, &x, 4); }

/**
 * The .glb file of one controller into [out] (null: size only). Returns its size in bytes, or 0
 * when [capacity] is too small.
 */
inline size_t glb(bool left, uint8_t* out, size_t capacity) {
    static Geometry g;
    build(left, &g);
    const uint32_t posBytes = sizeof(g.pos), nrmBytes = sizeof(g.normal), idxBytes = sizeof(g.index);
    const uint32_t binBytes = (posBytes + nrmBytes + idxBytes + 3u) & ~3u;
    char json[2048];
    int jl = snprintf(json, sizeof(json),
        "{\"asset\":{\"version\":\"2.0\",\"generator\":\"HyperMagic runtime\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
        "\"nodes\":[{\"name\":\"%s\",\"mesh\":0}],"
        "\"meshes\":[{\"name\":\"controller\",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1},\"indices\":2,\"material\":0}]}],"
        "\"materials\":[{\"name\":\"body\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[0.16,0.16,0.18,1],\"metallicFactor\":0.1,\"roughnessFactor\":0.55}}],"
        "\"buffers\":[{\"byteLength\":%u}],"
        "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":%u,\"target\":34962},"
        "{\"buffer\":0,\"byteOffset\":%u,\"byteLength\":%u,\"target\":34962},"
        "{\"buffer\":0,\"byteOffset\":%u,\"byteLength\":%u,\"target\":34963}],"
        "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\",\"min\":[%.5f,%.5f,%.5f],\"max\":[%.5f,%.5f,%.5f]},"
        "{\"bufferView\":1,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\"},"
        "{\"bufferView\":2,\"componentType\":5123,\"count\":%d,\"type\":\"SCALAR\"}]}",
        left ? "left_controller" : "right_controller", binBytes, posBytes, posBytes, nrmBytes, posBytes + nrmBytes, idxBytes,
        kVertices, g.min[0], g.min[1], g.min[2], g.max[0], g.max[1], g.max[2], kVertices, kIndices);
    if (jl <= 0 || jl >= static_cast<int>(sizeof(json))) return 0;
    const uint32_t jsonBytes = (static_cast<uint32_t>(jl) + 3u) & ~3u;
    const size_t total = 12 + 8 + jsonBytes + 8 + binBytes;
    if (!out) return total;
    if (capacity < total) return 0;
    uint8_t* p = out;
    put32(p, 0x46546C67u); put32(p + 4, 2); put32(p + 8, static_cast<uint32_t>(total)); p += 12;        // "glTF", version 2
    put32(p, jsonBytes); put32(p + 4, 0x4E4F534Au); p += 8;                                            // "JSON"
    memcpy(p, json, static_cast<size_t>(jl)); memset(p + jl, ' ', jsonBytes - static_cast<uint32_t>(jl)); p += jsonBytes;
    put32(p, binBytes); put32(p + 4, 0x004E4942u); p += 8;                                             // "BIN\0"
    memcpy(p, g.pos, posBytes); memcpy(p + posBytes, g.normal, nrmBytes); memcpy(p + posBytes + nrmBytes, g.index, idxBytes);
    memset(p + posBytes + nrmBytes + idxBytes, 0, binBytes - posBytes - nrmBytes - idxBytes);
    return total;
}

}  // namespace hxrendermodel
