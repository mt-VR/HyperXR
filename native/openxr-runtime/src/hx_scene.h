// The room of Meta's scene model (XR_FB_scene), built from a floor outline: the area drawn in
// HyperGuardian, or a 4 x 4 m room around the player when there is none. A phone in a viewer
// cannot scan a room, so this is the room it is told about.
//
// Conventions of the scene model: a plane entity's surface is the XY plane of its own space with
// +Z the normal (into the room for walls, up for the floor, down for the ceiling); its 2D bounding
// box and boundary are in that plane. The floor is at y = 0 of the stage space.
#pragma once

#include <math.h>
#include <stdint.h>
#include <string.h>

#include "hx_runtime.h"
#include "hx_hands.h"

namespace hxscene {

constexpr int MAX_POLY = 32;
constexpr int MAX_PLANES = 2 + MAX_POLY;

struct Room {
    int count;                   // outline corners (>= 3)
    float x[MAX_POLY], z[MAX_POLY];
    float height;                // floor to ceiling
};

enum Kind { ANCHOR = 0, ROOM = 1, FLOOR = 2, CEILING = 3, WALL = 4 };

struct Plane {
    int kind;                    // FLOOR, CEILING or WALL
    XrPosef pose;                // stage space
    float minX, minY, maxX, maxY;  // 2D bounding box in the plane
};

/** A 4 x 4 m room centred on the player, 2.6 m high. */
inline void defaultRoom(Room* r) {
    static const float x[4] = {-2, 2, 2, -2}, z[4] = {2, 2, -2, -2};
    r->count = 4;
    for (int i = 0; i < 4; i++) { r->x[i] = x[i]; r->z[i] = z[i]; }
    r->height = 2.6f;
}

/** Whether [r] is a usable outline: 3..MAX_POLY corners, some area, a sensible height. */
inline bool valid(const Room& r) {
    if (r.count < 3 || r.count > MAX_POLY || !(r.height > 1.0f && r.height < 10.0f)) return false;
    float a = 0;
    for (int i = 0; i < r.count; i++) { int j = (i + 1) % r.count; a += r.x[i] * r.z[j] - r.x[j] * r.z[i]; }
    return fabsf(a) * 0.5f > 0.5f;  // at least half a square metre
}

inline XrQuaternionf quat(hxhands::V x, hxhands::V y, hxhands::V z) {
    hxhands::Q q = hxhands::fromAxes(x, y, z);
    return {q.x, q.y, q.z, q.w};
}

/**
 * The floor ([0]), the ceiling ([1]) and one wall per outline edge ([2..]); [floorOutline] and
 * [ceilingOutline] get the outline in each plane's own XY coordinates. Returns the plane count.
 */
inline int build(const Room& r, Plane* planes, XrVector2f* floorOutline, XrVector2f* ceilingOutline) {
    float cx = 0, cz = 0;
    for (int i = 0; i < r.count; i++) { cx += r.x[i]; cz += r.z[i]; }
    cx /= r.count; cz /= r.count;
    // floor: +Z up, +X along world X, so +Y is world -Z
    Plane& f = planes[0];
    f.kind = FLOOR;
    f.pose = {quat({1, 0, 0}, {0, 0, -1}, {0, 1, 0}), {cx, 0, cz}};
    // ceiling: +Z down, +X along world X, so +Y is world +Z
    Plane& c = planes[1];
    c.kind = CEILING;
    c.pose = {quat({1, 0, 0}, {0, 0, 1}, {0, -1, 0}), {cx, r.height, cz}};
    f.minX = f.minY = c.minX = c.minY = 1e9f;
    f.maxX = f.maxY = c.maxX = c.maxY = -1e9f;
    for (int i = 0; i < r.count; i++) {
        float lx = r.x[i] - cx;
        floorOutline[i] = {lx, -(r.z[i] - cz)};
        ceilingOutline[i] = {lx, r.z[i] - cz};
        for (int k = 0; k < 2; k++) {
            Plane& p = k == 0 ? f : c;
            const XrVector2f& v = k == 0 ? floorOutline[i] : ceilingOutline[i];
            p.minX = fminf(p.minX, v.x); p.maxX = fmaxf(p.maxX, v.x);
            p.minY = fminf(p.minY, v.y); p.maxY = fmaxf(p.maxY, v.y);
        }
    }
    // walls: centred on each edge, half way up; +Z into the room, +Y up
    for (int i = 0; i < r.count; i++) {
        int j = (i + 1) % r.count;
        float dx = r.x[j] - r.x[i], dz = r.z[j] - r.z[i];
        float len = sqrtf(dx * dx + dz * dz);
        Plane& w = planes[2 + i];
        w.kind = WALL;
        float mx = (r.x[i] + r.x[j]) * 0.5f, mz = (r.z[i] + r.z[j]) * 0.5f;
        hxhands::V n = hxhands::norm({-dz, 0, dx}, {0, 0, 1});
        if (n.x * (cx - mx) + n.z * (cz - mz) < 0) n = hxhands::mul(n, -1);
        hxhands::V y = {0, 1, 0};
        w.pose = {quat(hxhands::cross(y, n), y, n), {mx, r.height * 0.5f, mz}};
        w.minX = -len * 0.5f; w.maxX = len * 0.5f;
        w.minY = -r.height * 0.5f; w.maxY = r.height * 0.5f;
    }
    return 2 + r.count;
}

/** A stable UUID for a scene entity: the same room gives the same UUIDs from run to run. */
inline XrUuidEXT sceneUuid(const Room& r, int kind, int index) {
    uint64_t h1 = 0xcbf29ce484222325ull, h2 = 0x84222325cbf29ce4ull;
    auto mix = [&](const void* p, size_t n) {
        const uint8_t* b = static_cast<const uint8_t*>(p);
        for (size_t i = 0; i < n; i++) { h1 = (h1 ^ b[i]) * 0x100000001b3ull; h2 = (h2 ^ b[i]) * 0x100000001b3ull + 0x9e37; }
    };
    mix(&kind, sizeof(kind)); mix(&index, sizeof(index));
    mix(&r.count, sizeof(r.count)); mix(r.x, sizeof(float) * r.count); mix(r.z, sizeof(float) * r.count); mix(&r.height, sizeof(r.height));
    XrUuidEXT u;
    memcpy(u.data, &h1, 8); memcpy(u.data + 8, &h2, 8);
    u.data[6] = static_cast<uint8_t>((u.data[6] & 0x0f) | 0x50);  // version 5 style (name-based)
    u.data[8] = static_cast<uint8_t>((u.data[8] & 0x3f) | 0x80);
    return u;
}

}  // namespace hxscene
