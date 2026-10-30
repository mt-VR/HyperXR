// A whole body from what a phone in a viewer knows: the head, and the hands when the camera sees
// them (Meta's XR_FB_body_tracking / XR_META_body_tracking_full_body and Pico's XR_BD_body_tracking).
// Like the legs Meta's own body tracking makes up, everything else is inferred: the torso hangs
// below the head and turns with it, the arms reach the hands with a two-bone IK (or hang at rest),
// and the legs stand, or sit when the head is well below the standing eye height.
//
// The result uses Meta's full-body joint numbering (84 joints; the first 70 are XR_FB_body_tracking's);
// Pico's 24 SMPL-style joints are read from it.
#pragma once

#include <math.h>

#include "hx_handmesh.h"
#include "hx_hands.h"
#include "hx_runtime.h"

namespace hxbody {

constexpr int kMetaJoints = 84;   // XR_FULL_BODY_JOINT_COUNT_META
constexpr int kFbJoints = 70;     // XR_BODY_JOINT_COUNT_FB
constexpr int kBdJoints = 24;     // XR_BODY_JOINT_SET_FULL_BODY_JOINTS_BD

enum {
    ROOT = 0, HIPS, SPINE_LOWER, SPINE_MIDDLE, SPINE_UPPER, CHEST, NECK, HEAD,
    L_SHOULDER, L_SCAPULA, L_ARM_UPPER, L_ARM_LOWER, L_WRIST_TWIST,
    R_SHOULDER, R_SCAPULA, R_ARM_UPPER, R_ARM_LOWER, R_WRIST_TWIST,
    L_HAND = 18,   // 26 hand joints, XR_HAND_JOINT_* order (palm, wrist, thumb.., index.., ...)
    R_HAND = 44,
    L_UPPER_LEG = 70, L_LOWER_LEG, L_ANKLE_TWIST, L_ANKLE, L_SUBTALAR, L_TRANSVERSE, L_BALL,
    R_UPPER_LEG = 77, R_LOWER_LEG, R_ANKLE_TWIST, R_ANKLE, R_SUBTALAR, R_TRANSVERSE, R_BALL,
};

struct Input {
    XrPosef head;                     // stage space
    float standingEyeHeight;          // the user's eyes when standing (settings), metres
    bool handTracked[2];
    XrPosef hand[2][26];              // stage space, XR_HAND_JOINT_* order, when tracked
};

struct Output {
    XrPosef pose[kMetaJoints];
    bool tracked[kMetaJoints];        // seen (head, tracked hands); the rest is inferred
};

inline XrVector3f v3(float x, float y, float z) { return {x, y, z}; }
inline XrVector3f add(XrVector3f a, XrVector3f b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline XrVector3f sub(XrVector3f a, XrVector3f b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline XrVector3f mul(XrVector3f a, float s) { return {a.x * s, a.y * s, a.z * s}; }
inline float dot(XrVector3f a, XrVector3f b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline float len(XrVector3f a) { return sqrtf(dot(a, a)); }
inline XrVector3f norm(XrVector3f a, XrVector3f fb) { float n = len(a); return n > 1e-6f ? mul(a, 1.0f / n) : fb; }
inline XrVector3f cross(XrVector3f a, XrVector3f b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }

/** Rotation with -Z along [dir] and +Y as close to [up] as possible. */
inline XrQuaternionf along(XrVector3f dir, XrVector3f up) {
    hxhands::V z = hxhands::norm({-dir.x, -dir.y, -dir.z}, {0, 0, 1});
    hxhands::V u = {up.x, up.y, up.z};
    if (fabsf(hxhands::dot(z, hxhands::norm(u, {0, 1, 0}))) > 0.98f) u = {0, 0, 1};
    hxhands::V x = hxhands::norm(hxhands::cross(u, z), {1, 0, 0});
    hxhands::V y = hxhands::cross(z, x);
    hxhands::Q q = hxhands::fromAxes(x, y, z);
    return {q.x, q.y, q.z, q.w};
}

/** Elbow (knee) of a two-bone limb from [root] to [end], bending towards [pole]; [end] is pulled in when out of reach. */
inline XrVector3f twoBone(XrVector3f root, XrVector3f* end, float a, float b, XrVector3f pole) {
    XrVector3f d = sub(*end, root);
    float dist = len(d);
    XrVector3f dir = norm(d, v3(0, -1, 0));
    float lo = fabsf(a - b) + 1e-3f, hi = a + b - 1e-3f;
    if (dist > hi) { dist = hi; *end = add(root, mul(dir, dist)); }
    if (dist < lo) { dist = lo; *end = add(root, mul(dir, dist)); }
    float cosA = (a * a + dist * dist - b * b) / (2 * a * dist);
    cosA = cosA < -1 ? -1 : (cosA > 1 ? 1 : cosA);
    float sinA = sqrtf(1 - cosA * cosA);
    XrVector3f perp = norm(sub(pole, mul(dir, dot(pole, dir))), cross(dir, v3(1, 0, 0)));
    return add(root, add(mul(dir, a * cosA), mul(perp, a * sinA)));
}

/** A resting hand hanging from [wrist]: fingers down, the back of the hand facing out. */
inline void restHand(bool left, XrVector3f wrist, XrVector3f right, XrPosef out[26]) {
    static hxhandmesh::Mesh canonical[2];
    static bool built[2] = {false, false};
    int k = left ? 0 : 1;
    if (!built[k]) { hxhandmesh::build(left, &canonical[k]); built[k] = true; }
    // canonical hand: wrist at the origin, fingers -Z, back +Y; here fingers point down, back outwards
    XrVector3f outward = left ? mul(right, -1) : right;
    XrQuaternionf r = along(v3(0, -1, 0), outward);
    for (int j = 0; j < 26; j++) {
        const hxhands::Joint& b = canonical[k].bind[j];
        XrPosef local{{b.q.x, b.q.y, b.q.z, b.q.w}, {b.p.x, b.p.y, b.p.z}};
        out[j] = hx::compose(XrPosef{r, wrist}, local);
    }
}

inline void solve(const Input& in, Output* o) {
    const XrPosef& head = in.head;
    float s = in.standingEyeHeight / 1.6f;
    s = s < 0.6f ? 0.6f : (s > 1.3f ? 1.3f : s);
    XrQuaternionf yaw = hx::yawOnly(head.orientation);
    XrVector3f fwd = hx::qrot(yaw, v3(0, 0, -1)), right = hx::qrot(yaw, v3(1, 0, 0)), up = v3(0, 1, 0);
    for (int i = 0; i < kMetaJoints; i++) o->tracked[i] = false;
    auto put = [&](int j, XrVector3f p, XrQuaternionf q) { o->pose[j] = XrPosef{q, p}; };

    // head and torso, hanging below the head and turned with it
    XrVector3f back = mul(fwd, -0.08f * s);
    XrVector3f headC = add(head.position, back);
    put(HEAD, headC, head.orientation);
    o->tracked[HEAD] = true;
    bool seated = head.position.y < in.standingEyeHeight - 0.35f;
    XrVector3f neck = add(headC, mul(up, -0.13f * s));
    XrVector3f chest = add(neck, mul(up, -0.17f * s));
    XrVector3f spineUpper = add(chest, mul(up, -0.08f * s));
    XrVector3f spineMid = add(chest, mul(up, -0.18f * s));
    XrVector3f spineLow = add(chest, mul(up, -0.30f * s));
    XrVector3f hips = add(chest, mul(up, -0.40f * s));
    put(NECK, neck, yaw); put(CHEST, chest, yaw); put(SPINE_UPPER, spineUpper, yaw);
    put(SPINE_MIDDLE, spineMid, yaw); put(SPINE_LOWER, spineLow, yaw); put(HIPS, hips, yaw);
    put(ROOT, v3(hips.x, 0, hips.z), yaw);

    // arms: to the tracked hands, or hanging at rest
    for (int side = 0; side < 2; side++) {
        float sign = side == 0 ? -1.0f : 1.0f;
        int base = side == 0 ? L_SHOULDER : R_SHOULDER;
        int handBase = side == 0 ? L_HAND : R_HAND;
        XrVector3f shoulderIn = add(add(chest, mul(up, 0.04f * s)), mul(right, sign * 0.05f * s));
        XrVector3f shoulder = add(add(chest, mul(up, 0.05f * s)), mul(right, sign * 0.18f * s));
        XrPosef hand[26];
        XrVector3f wrist;
        if (in.handTracked[side]) {
            for (int j = 0; j < 26; j++) hand[j] = in.hand[side][j];
            wrist = hand[XR_HAND_JOINT_WRIST_EXT].position;
        } else {
            wrist = add(add(shoulder, mul(up, -0.52f * s)), mul(fwd, 0.04f * s));
        }
        XrVector3f reach = wrist;
        // elbows bend down, back and a little out
        XrVector3f pole = add(add(mul(up, -1), mul(fwd, -0.6f)), mul(right, sign * 0.4f));
        XrVector3f elbow = twoBone(shoulder, &reach, 0.28f * s, 0.27f * s, pole);
        if (!in.handTracked[side]) restHand(side == 0, reach, right, hand);
        XrVector3f twist = add(elbow, mul(sub(reach, elbow), 0.8f));
        put(base, shoulderIn, along(sub(shoulder, shoulderIn), up));
        put(base + 1, add(shoulderIn, mul(sub(shoulder, shoulderIn), 0.5f)), along(sub(shoulder, shoulderIn), up));
        put(base + 2, shoulder, along(sub(elbow, shoulder), fwd));
        put(base + 3, elbow, along(sub(reach, elbow), fwd));
        put(base + 4, twist, along(sub(reach, elbow), fwd));
        for (int j = 0; j < 26; j++) { o->pose[handBase + j] = hand[j]; o->tracked[handBase + j] = in.handTracked[side]; }
    }

    // legs: standing, or sitting with the thighs forward when the head is low
    for (int side = 0; side < 2; side++) {
        float sign = side == 0 ? -1.0f : 1.0f;
        int base = side == 0 ? L_UPPER_LEG : R_UPPER_LEG;
        XrVector3f hip = add(hips, mul(right, sign * 0.09f * s));
        XrVector3f knee, ankle;
        if (seated) {
            knee = add(hip, mul(fwd, 0.44f * s));
            ankle = v3(knee.x, knee.y - 0.42f * s, knee.z);
            if (ankle.y < 0.08f) ankle.y = 0.08f;
        } else {
            ankle = v3(hip.x, 0.08f, hip.z);
            ankle = add(ankle, mul(right, sign * 0.03f * s));
            knee = twoBone(hip, &ankle, 0.44f * s, 0.42f * s, fwd);
        }
        XrQuaternionf foot = along(fwd, up);
        put(base, hip, along(sub(knee, hip), fwd));
        put(base + 1, knee, along(sub(ankle, knee), fwd));
        put(base + 2, add(knee, mul(sub(ankle, knee), 0.85f)), along(sub(ankle, knee), fwd));
        put(base + 3, ankle, foot);
        put(base + 4, add(ankle, add(mul(up, -0.04f * s), mul(fwd, -0.02f * s))), foot);
        put(base + 5, add(ankle, add(mul(up, -0.06f * s), mul(fwd, 0.07f * s))), foot);
        put(base + 6, add(ankle, add(mul(up, -0.07f * s), mul(fwd, 0.14f * s))), foot);
    }
}

/** Parent of each full-body joint (-1: the root). */
inline int parentMeta(int j) {
    if (j == ROOT) return -1;
    if (j == HIPS) return ROOT;
    if (j >= SPINE_LOWER && j <= HEAD) return j - 1;
    if (j == L_SHOULDER || j == R_SHOULDER) return CHEST;
    if ((j > L_SHOULDER && j <= L_WRIST_TWIST) || (j > R_SHOULDER && j <= R_WRIST_TWIST)) return j - 1;
    if (j >= L_HAND && j < L_UPPER_LEG) {
        int base = j < R_HAND ? L_HAND : R_HAND, h = j - base;
        int lower = base == L_HAND ? L_ARM_LOWER : R_ARM_LOWER;
        if (h == XR_HAND_JOINT_WRIST_EXT) return lower;
        int hp = hxhandmesh::parentOf(h);
        return hp == 0x7FFFFFFF ? lower : base + hp;
    }
    if (j == L_UPPER_LEG || j == R_UPPER_LEG) return HIPS;
    if (j == L_ANKLE || j == R_ANKLE) return j - 2;  // the ankle twist and the ankle both hang from the lower leg
    return j - 1;
}

/** Pico's 24 joints (SMPL order) from the full body. */
inline void toBD(const Output& b, XrPosef out[kBdJoints], bool tracked[kBdJoints]) {
    static const int map[kBdJoints] = {
        HIPS, L_UPPER_LEG, R_UPPER_LEG, SPINE_LOWER, L_LOWER_LEG, R_LOWER_LEG, SPINE_MIDDLE, L_ANKLE, R_ANKLE,
        SPINE_UPPER, L_BALL, R_BALL, NECK, L_SHOULDER, R_SHOULDER, HEAD, L_ARM_UPPER, R_ARM_UPPER, L_ARM_LOWER, R_ARM_LOWER,
        L_HAND + XR_HAND_JOINT_WRIST_EXT, R_HAND + XR_HAND_JOINT_WRIST_EXT, L_HAND + XR_HAND_JOINT_PALM_EXT, R_HAND + XR_HAND_JOINT_PALM_EXT};
    for (int i = 0; i < kBdJoints; i++) { out[i] = b.pose[map[i]]; tracked[i] = b.tracked[map[i]]; }
}

}  // namespace hxbody
