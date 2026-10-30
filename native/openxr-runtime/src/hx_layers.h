// Ray casting for the quad, cylinder and equirect layers, in the layer's own space (the layer's
// pose at the origin). The compositor's fragment shader (platform_android.cpp, kShape) does the
// same steps per pixel; this C++ copy is what the host tests check.
#pragma once

#include <math.h>

#include "hx_runtime.h"

namespace hxlayers {

/**
 * Texture coordinate (0..1 over the layer's sub-image) hit by the ray o + t d (layer space), or
 * false when the ray misses the layer.
 */
inline bool hit(const HxComposeQuad& l, const float o[3], const float d[3], float uv[2]) {
    if (l.shape == HX_SHAPE_QUAD) {
        if (fabsf(d[2]) < 1e-6f) return false;
        float s = -o[2] / d[2];
        if (s <= 0) return false;
        uv[0] = (o[0] + d[0] * s) / l.size.width + 0.5f;
        uv[1] = (o[1] + d[1] * s) / l.size.height + 0.5f;
    } else if (l.shape == HX_SHAPE_CYLINDER) {
        // vertical cylinder around the layer's Y axis; from inside, the far wall is seen
        float a = d[0] * d[0] + d[2] * d[2];
        if (a < 1e-9f) return false;
        float b = 2 * (o[0] * d[0] + o[2] * d[2]);
        float c = o[0] * o[0] + o[2] * o[2] - l.radius * l.radius;
        float disc = b * b - 4 * a * c;
        if (disc < 0) return false;
        float t = (-b + sqrtf(disc)) / (2 * a);
        if (c > 0) t = (-b - sqrtf(disc)) / (2 * a);  // outside: the near wall
        if (t <= 0) return false;
        float px = o[0] + d[0] * t, py = o[1] + d[1] * t, pz = o[2] + d[2] * t;
        float angle = atan2f(px, -pz);  // 0 straight ahead (-Z), positive to the right
        float height = l.radius * l.centralAngle / l.aspectRatio;
        uv[0] = angle / l.centralAngle + 0.5f;
        uv[1] = py / height + 0.5f;
    } else {
        // sphere around the layer's origin; radius 0: infinitely far, only the direction counts
        float px = d[0], py = d[1], pz = d[2];
        if (l.radius > 0) {
            float b = 2 * (o[0] * d[0] + o[1] * d[1] + o[2] * d[2]);
            float a = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
            float c = o[0] * o[0] + o[1] * o[1] + o[2] * o[2] - l.radius * l.radius;
            float disc = b * b - 4 * a * c;
            if (disc < 0) return false;
            float t = (-b + sqrtf(disc)) / (2 * a);
            if (t <= 0) return false;
            px = o[0] + d[0] * t; py = o[1] + d[1] * t; pz = o[2] + d[2] * t;
        }
        float n = sqrtf(px * px + py * py + pz * pz);
        if (n < 1e-9f) return false;
        float lon = atan2f(px, -pz);
        float lat = asinf(fmaxf(-1.0f, fminf(1.0f, py / n)));
        uv[0] = lon / l.centralAngle + 0.5f;
        uv[1] = (lat - l.lowerAngle) / (l.upperAngle - l.lowerAngle);
    }
    return uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1;
}

}  // namespace hxlayers
