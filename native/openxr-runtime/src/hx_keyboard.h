// The virtual keyboard of XR_META_virtual_keyboard, made by the runtime: the layout (keys and
// what they type), its picture (a texture drawn here with a small bitmap font and stored as a PNG)
// and its model (a binary glTF with that texture on a thin plate). The app loads the model through
// XR_FB_render_model, places it with the keyboard's space and sends its rays / fingertips; the
// runtime finds the key and sends the text.
//
// Keyboard space: the keys lie on the XY plane, +Z towards the user, +Y up the keyboard; metres
// at scale 1.
#pragma once

#include <stdint.h>
#include <stdio.h>
#include <string.h>

namespace hxkeyboard {

constexpr float kWidth = 0.56f, kHeight = 0.20f;
constexpr int kTexW = 1024, kTexH = 366;  // the same aspect as the keyboard

enum KeyKind { KEY_CHAR, KEY_SHIFT, KEY_BACKSPACE, KEY_ENTER, KEY_SPACE };
struct Key { float x0, y0, x1, y1; int kind; char ch; };  // rectangle in keyboard space

/** The layout: 5 rows, numbers to space bar. Returns the number of keys written to [out] (<= 48). */
inline int layout(Key* out) {
    static const char* rows[4] = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"};
    const float pad = 0.004f, rowH = kHeight / 5, keyW = kWidth / 10.5f;
    int n = 0;
    auto add = [&](float cx, float cy, float w, int kind, char ch) {
        out[n++] = {cx - w / 2 + pad, cy - rowH / 2 + pad, cx + w / 2 - pad, cy + rowH / 2 - pad, kind, ch};
    };
    for (int r = 0; r < 4; r++) {
        float cy = kHeight / 2 - rowH * (r + 0.5f);
        int len = static_cast<int>(strlen(rows[r]));
        float start = -len * keyW / 2;
        if (r == 3) {
            add(-kWidth / 2 + keyW * 0.75f, cy, keyW * 1.5f, KEY_SHIFT, 0);
            add(kWidth / 2 - keyW * 0.75f, cy, keyW * 1.5f, KEY_BACKSPACE, 0);
        }
        for (int i = 0; i < len; i++) add(start + keyW * (i + 0.5f), cy, keyW, KEY_CHAR, rows[r][i]);
    }
    float cy = -kHeight / 2 + rowH / 2;
    add(-kWidth / 2 + keyW * 0.75f, cy, keyW * 1.5f, KEY_CHAR, ',');
    add(-kWidth / 2 + keyW * 2.0f, cy, keyW, KEY_CHAR, '.');
    add(0, cy, keyW * 5.0f, KEY_SPACE, ' ');
    add(kWidth / 2 - keyW * 1.0f, cy, keyW * 2.0f, KEY_ENTER, 0);
    return n;
}

/** The key at (x, y) in keyboard space (scale 1), or -1. */
inline int keyAt(const Key* keys, int n, float x, float y) {
    for (int i = 0; i < n; i++) if (x >= keys[i].x0 && x <= keys[i].x1 && y >= keys[i].y0 && y <= keys[i].y1) return i;
    return -1;
}

// 5x7 bitmap font: digits, lowercase letters, ',' '.' and three symbols (shift, backspace, enter)
inline const uint8_t* glyph(char c) {
    static const uint8_t digits[10][7] = {
        {14, 17, 19, 21, 25, 17, 14}, {4, 12, 4, 4, 4, 4, 14}, {14, 17, 1, 2, 4, 8, 31}, {31, 2, 4, 2, 1, 17, 14}, {2, 6, 10, 18, 31, 2, 2},
        {31, 16, 30, 1, 1, 17, 14}, {6, 8, 16, 30, 17, 17, 14}, {31, 1, 2, 4, 8, 8, 8}, {14, 17, 17, 14, 17, 17, 14}, {14, 17, 17, 15, 1, 2, 12}};
    static const uint8_t letters[26][7] = {
        {0, 0, 14, 1, 15, 17, 15}, {16, 16, 22, 25, 17, 17, 30}, {0, 0, 14, 16, 16, 17, 14}, {1, 1, 13, 19, 17, 17, 15}, {0, 0, 14, 17, 31, 16, 14},
        {6, 9, 8, 28, 8, 8, 8}, {0, 15, 17, 17, 15, 1, 14}, {16, 16, 22, 25, 17, 17, 17}, {4, 0, 12, 4, 4, 4, 14}, {2, 0, 6, 2, 2, 18, 12},
        {16, 16, 18, 20, 24, 20, 18}, {12, 4, 4, 4, 4, 4, 14}, {0, 0, 26, 21, 21, 17, 17}, {0, 0, 22, 25, 17, 17, 17}, {0, 0, 14, 17, 17, 17, 14},
        {0, 0, 30, 17, 30, 16, 16}, {0, 0, 13, 19, 15, 1, 1}, {0, 0, 22, 25, 16, 16, 16}, {0, 0, 14, 16, 14, 1, 30}, {8, 8, 28, 8, 8, 9, 6},
        {0, 0, 17, 17, 17, 19, 13}, {0, 0, 17, 17, 17, 10, 4}, {0, 0, 17, 17, 21, 21, 10}, {0, 0, 17, 10, 4, 10, 17}, {0, 0, 17, 17, 15, 1, 14},
        {0, 0, 31, 2, 4, 8, 31}};
    static const uint8_t comma[7] = {0, 0, 0, 0, 12, 4, 8}, dot[7] = {0, 0, 0, 0, 0, 12, 12};
    static const uint8_t shift[7] = {4, 14, 31, 14, 14, 14, 0}, back[7] = {0, 4, 12, 31, 12, 4, 0}, enter[7] = {1, 1, 9, 25, 31, 8, 0};
    if (c >= '0' && c <= '9') return digits[c - '0'];
    if (c >= 'a' && c <= 'z') return letters[c - 'a'];
    if (c == ',') return comma;
    if (c == '.') return dot;
    if (c == 1) return shift;
    if (c == 2) return back;
    if (c == 3) return enter;
    return nullptr;
}

/** The keyboard's picture, RGBA8, kTexW x kTexH, row 0 at the top. */
inline void drawTexture(uint8_t* rgba) {
    auto fill = [&](int x0, int y0, int x1, int y1, uint8_t r, uint8_t g, uint8_t b) {
        for (int y = y0 < 0 ? 0 : y0; y < y1 && y < kTexH; y++)
            for (int x = x0 < 0 ? 0 : x0; x < x1 && x < kTexW; x++) {
                uint8_t* p = rgba + (y * kTexW + x) * 4;
                p[0] = r; p[1] = g; p[2] = b; p[3] = 255;
            }
    };
    fill(0, 0, kTexW, kTexH, 24, 26, 30);
    Key keys[48];
    int n = layout(keys);
    auto px = [](float x) { return static_cast<int>((x / kWidth + 0.5f) * kTexW); };
    auto py = [](float y) { return static_cast<int>((0.5f - y / kHeight) * kTexH); };
    for (int i = 0; i < n; i++) {
        const Key& k = keys[i];
        int x0 = px(k.x0), x1 = px(k.x1), y0 = py(k.y1), y1 = py(k.y0);
        bool special = k.kind != KEY_CHAR && k.kind != KEY_SPACE;
        fill(x0, y0, x1, y1, special ? 58 : 70, special ? 62 : 74, special ? 72 : 84);
        char label = k.kind == KEY_SHIFT ? 1 : k.kind == KEY_BACKSPACE ? 2 : k.kind == KEY_ENTER ? 3 : k.ch;
        const uint8_t* g = glyph(label);
        if (!g) continue;
        const int s = 5;  // pixels per font dot
        int gx = (x0 + x1) / 2 - 5 * s / 2, gy = (y0 + y1) / 2 - 7 * s / 2;
        for (int row = 0; row < 7; row++)
            for (int col = 0; col < 5; col++)
                if (g[row] & (16 >> col)) fill(gx + col * s, gy + row * s, gx + (col + 1) * s, gy + (row + 1) * s, 235, 238, 245);
    }
}

// ---------------------------------------------------------------- PNG (stored, no compression)
inline uint32_t crc32(const uint8_t* d, size_t n, uint32_t c = 0xFFFFFFFFu) {
    for (size_t i = 0; i < n; i++) { c ^= d[i]; for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u))); }
    return c;
}
inline void be32(uint8_t* p, uint32_t v) { p[0] = static_cast<uint8_t>(v >> 24); p[1] = static_cast<uint8_t>(v >> 16); p[2] = static_cast<uint8_t>(v >> 8); p[3] = static_cast<uint8_t>(v); }

/** The size of the PNG of an RGBA8 image (null [out]: size only). */
inline size_t png(const uint8_t* rgba, int w, int h, uint8_t* out) {
    const size_t raw = static_cast<size_t>(h) * (1 + static_cast<size_t>(w) * 4);   // filter byte + pixels per row
    const size_t blocks = (raw + 65534) / 65535;
    const size_t zlen = 2 + raw + blocks * 5 + 4;
    const size_t total = 8 + (12 + 13) + (12 + zlen) + 12;
    if (!out) return total;
    uint8_t* p = out;
    static const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    memcpy(p, sig, 8); p += 8;
    auto chunk = [&](const char* type, size_t len, auto body) {
        be32(p, static_cast<uint32_t>(len)); memcpy(p + 4, type, 4);
        body(p + 8);
        be32(p + 8 + len, crc32(p + 4, len + 4) ^ 0xFFFFFFFFu);
        p += 12 + len;
    };
    chunk("IHDR", 13, [&](uint8_t* d) { be32(d, static_cast<uint32_t>(w)); be32(d + 4, static_cast<uint32_t>(h)); d[8] = 8; d[9] = 6; d[10] = d[11] = d[12] = 0; });
    chunk("IDAT", zlen, [&](uint8_t* d) {
        d[0] = 0x78; d[1] = 0x01;
        uint8_t* q = d + 2;
        uint32_t a = 1, b = 0;
        size_t left = raw, row = 0, col = 0;  // walk the filtered stream: per row a 0 filter byte, then pixels
        while (left > 0) {
            size_t len = left < 65535 ? left : 65535;
            left -= len;
            q[0] = left == 0 ? 1 : 0;
            q[1] = static_cast<uint8_t>(len); q[2] = static_cast<uint8_t>(len >> 8);
            q[3] = static_cast<uint8_t>(~len); q[4] = static_cast<uint8_t>(~len >> 8);
            q += 5;
            for (size_t i = 0; i < len; i++) {
                uint8_t v = col == 0 ? 0 : rgba[row * static_cast<size_t>(w) * 4 + col - 1];
                *q++ = v;
                a = (a + v) % 65521; b = (b + a) % 65521;
                if (++col == 1 + static_cast<size_t>(w) * 4) { col = 0; row++; }
            }
        }
        be32(q, (b << 16) | a);
    });
    chunk("IEND", 0, [&](uint8_t*) {});
    return total;
}

// ---------------------------------------------------------------- glTF
inline void put32(uint8_t* p, uint32_t x) { memcpy(p, &x, 4); }

/** The keyboard's .glb (a textured plate with the keys drawn on it) into [out]; size only with null. */
inline size_t glb(uint8_t* out, size_t capacity) {
    static uint8_t rgba[kTexW * kTexH * 4];
    static bool drawn = false;
    if (!drawn) { drawTexture(rgba); drawn = true; }
    const size_t pngBytes = png(rgba, kTexW, kTexH, nullptr);
    // the plate: 4 vertices facing +Z
    const float hw = kWidth / 2, hh = kHeight / 2;
    const float pos[4][3] = {{-hw, -hh, 0}, {hw, -hh, 0}, {hw, hh, 0}, {-hw, hh, 0}};
    const float nrm[4][3] = {{0, 0, 1}, {0, 0, 1}, {0, 0, 1}, {0, 0, 1}};
    const float uv[4][2] = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
    const uint16_t idx[6] = {0, 1, 2, 0, 2, 3};
    const uint32_t posB = sizeof(pos), nrmB = sizeof(nrm), uvB = sizeof(uv), idxB = sizeof(idx);
    const uint32_t geo = posB + nrmB + uvB + idxB;  // a multiple of 4 already
    const uint32_t binBytes = static_cast<uint32_t>((geo + pngBytes + 3) & ~static_cast<size_t>(3));
    char json[2048];
    int jl = snprintf(json, sizeof(json),
        "{\"asset\":{\"version\":\"2.0\",\"generator\":\"HyperMagic runtime\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
        "\"nodes\":[{\"name\":\"keyboard\",\"mesh\":0}],"
        "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"material\":0}]}],"
        "\"materials\":[{\"name\":\"keys\",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},\"metallicFactor\":0,\"roughnessFactor\":0.8}}],"
        "\"textures\":[{\"source\":0,\"sampler\":0}],\"samplers\":[{\"magFilter\":9729,\"minFilter\":9729}],"
        "\"images\":[{\"bufferView\":4,\"mimeType\":\"image/png\"}],"
        "\"buffers\":[{\"byteLength\":%u}],"
        "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":%u},{\"buffer\":0,\"byteOffset\":%u,\"byteLength\":%u},"
        "{\"buffer\":0,\"byteOffset\":%u,\"byteLength\":%u},{\"buffer\":0,\"byteOffset\":%u,\"byteLength\":%u},"
        "{\"buffer\":0,\"byteOffset\":%u,\"byteLength\":%u}],"
        "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\",\"min\":[%.4f,%.4f,0],\"max\":[%.4f,%.4f,0]},"
        "{\"bufferView\":1,\"componentType\":5126,\"count\":4,\"type\":\"VEC3\"},{\"bufferView\":2,\"componentType\":5126,\"count\":4,\"type\":\"VEC2\"},"
        "{\"bufferView\":3,\"componentType\":5123,\"count\":6,\"type\":\"SCALAR\"}]}",
        binBytes, posB, posB, nrmB, posB + nrmB, uvB, posB + nrmB + uvB, idxB, geo, static_cast<uint32_t>(pngBytes), -hw, -hh, hw, hh);
    if (jl <= 0 || jl >= static_cast<int>(sizeof(json))) return 0;
    const uint32_t jsonBytes = (static_cast<uint32_t>(jl) + 3u) & ~3u;
    const size_t total = 12 + 8 + jsonBytes + 8 + binBytes;
    if (!out) return total;
    if (capacity < total) return 0;
    uint8_t* p = out;
    put32(p, 0x46546C67u); put32(p + 4, 2); put32(p + 8, static_cast<uint32_t>(total)); p += 12;
    put32(p, jsonBytes); put32(p + 4, 0x4E4F534Au); p += 8;
    memcpy(p, json, static_cast<size_t>(jl)); memset(p + jl, ' ', jsonBytes - static_cast<uint32_t>(jl)); p += jsonBytes;
    put32(p, binBytes); put32(p + 4, 0x004E4942u); p += 8;
    memcpy(p, pos, posB); memcpy(p + posB, nrm, nrmB); memcpy(p + posB + nrmB, uv, uvB); memcpy(p + posB + nrmB + uvB, idx, idxB);
    png(rgba, kTexW, kTexH, p + geo);
    memset(p + geo + pngBytes, 0, binBytes - geo - pngBytes);
    return total;
}

}  // namespace hxkeyboard
