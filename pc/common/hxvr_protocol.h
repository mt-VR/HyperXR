// Wire format between the HyperXR phone app and the PC (mirror of
// core/src/main/kotlin/com/hyperxr/steamvr/HxvrProtocol.kt). One TCP connection, little endian,
// each message: u32 length (type + payload) | u8 type | payload.
#pragma once
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace hxvr {

constexpr uint16_t kPort = 9944;
constexpr uint32_t kMagic = 0x52565848;  // "HXVR"
constexpr uint16_t kVersion = 2;
enum Codec : uint8_t { CODEC_H264 = 1, CODEC_HEVC = 2 };

enum MsgType : uint8_t { HELLO = 1, CONFIG = 2, VIDEO = 3, HAPTIC = 4, IDR_REQUEST = 5, TRACKING = 10 };

enum Buttons : uint32_t {
    BTN_PRIMARY = 1u << 0, BTN_SECONDARY = 1u << 1, BTN_MENU = 1u << 4, BTN_SYSTEM = 1u << 5,
    BTN_STICK_CLICK = 1u << 6, BTN_STICK_TOUCH = 1u << 7, BTN_TRIGGER_TOUCH = 1u << 8,
    BTN_GRIP_TOUCH = 1u << 9, BTN_THUMBREST_TOUCH = 1u << 10,
};
enum CtrlFlags : uint8_t { CTRL_CONNECTED = 1, CTRL_HAND_TRACKING = 2, CTRL_POSE_VALID = 4 };

struct Hello {
    uint16_t version = 0, eyeWidth = 0, eyeHeight = 0, refreshHz = 0;
    float tanLeft = 1, tanRight = 1, tanUp = 1, tanDown = 1, ipd = 0.063f;
    std::string name;
    uint8_t codecs = CODEC_H264;  // version 2: decoders the phone has at this size
    uint16_t maxMbps = 0;         // version 2: the most the phone wants streamed (0 = no limit)
};

struct Controller {
    uint8_t flags = 0;
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};  // px py pz qx qy qz qw
    uint32_t buttons = 0;
    float trigger = 0, grip = 0, joyX = 0, joyY = 0;
    float curls[5] = {};
};

struct Tracking {
    uint64_t timeNs = 0;
    float head[7] = {0, 0, 0, 0, 0, 0, 1};
    Controller controllers[2];
};
constexpr size_t kTrackingSize = 8 + 28 + 2 * (1 + 28 + 4 + 16 + 20);

class Reader {
public:
    Reader(const uint8_t* p, size_t n) : p_(p), n_(n) {}
    template <typename T> bool get(T& v) {
        if (n_ - i_ < sizeof(T)) return false;
        std::memcpy(&v, p_ + i_, sizeof(T)); i_ += sizeof(T); return true;
    }
    size_t remaining() const { return n_ - i_; }
    const uint8_t* cursor() const { return p_ + i_; }
private:
    const uint8_t* p_; size_t n_; size_t i_ = 0;
};

class Writer {
public:
    explicit Writer(MsgType t) { buf_.resize(4); put<uint8_t>(t); }
    template <typename T> Writer& put(T v) {
        size_t o = buf_.size(); buf_.resize(o + sizeof(T)); std::memcpy(buf_.data() + o, &v, sizeof(T)); return *this;
    }
    Writer& bytes(const uint8_t* p, size_t n) { buf_.insert(buf_.end(), p, p + n); return *this; }
    std::vector<uint8_t>& finish() { uint32_t n = uint32_t(buf_.size() - 4); std::memcpy(buf_.data(), &n, 4); return buf_; }
private:
    std::vector<uint8_t> buf_;
};

inline bool ParseHello(const uint8_t* p, size_t n, Hello& h) {
    Reader r(p, n);
    uint32_t magic = 0; uint8_t len = 0;
    if (!r.get(magic) || magic != kMagic) return false;
    if (!(r.get(h.version) && r.get(h.eyeWidth) && r.get(h.eyeHeight) && r.get(h.refreshHz) && r.get(h.tanLeft) &&
          r.get(h.tanRight) && r.get(h.tanUp) && r.get(h.tanDown) && r.get(h.ipd) && r.get(len))) return false;
    if (r.remaining() < len) return false;
    h.name.assign(reinterpret_cast<const char*>(r.cursor()), len);
    Reader tail(r.cursor() + len, r.remaining() - len);
    if (!tail.get(h.codecs) || h.codecs == 0) h.codecs = CODEC_H264;
    tail.get(h.maxMbps);
    return true;
}

inline bool ParseTracking(const uint8_t* p, size_t n, Tracking& t) {
    if (n < kTrackingSize) return false;
    Reader r(p, n);
    r.get(t.timeNs);
    for (float& f : t.head) r.get(f);
    for (Controller& c : t.controllers) {
        r.get(c.flags);
        for (float& f : c.pose) r.get(f);
        r.get(c.buttons); r.get(c.trigger); r.get(c.grip); r.get(c.joyX); r.get(c.joyY);
        for (float& f : c.curls) r.get(f);
    }
    return true;
}

inline std::vector<uint8_t> MakeConfig(uint16_t w, uint16_t h, uint16_t hz, uint32_t kbps, uint8_t codec, bool restartPending) {
    Writer wr(CONFIG);
    wr.put(w).put(h).put(hz).put(kbps).put<uint8_t>(codec).put<uint8_t>(restartPending ? 1 : 0);
    return wr.finish();
}

inline std::vector<uint8_t> MakeVideo(uint64_t frameId, uint64_t trackingNs, const float q[4], bool key, const uint8_t* data, size_t n) {
    Writer wr(VIDEO);
    wr.put(frameId).put(trackingNs).put(q[0]).put(q[1]).put(q[2]).put(q[3]).put<uint8_t>(key ? 1 : 0).bytes(data, n);
    return wr.finish();
}

inline std::vector<uint8_t> MakeHaptic(uint8_t hand, float duration, float frequency, float amplitude) {
    Writer wr(HAPTIC);
    wr.put(hand).put(duration).put(frequency).put(amplitude);
    return wr.finish();
}

}  // namespace hxvr
