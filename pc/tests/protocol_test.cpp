// Checks that the driver reads the phone's bytes and writes what the phone parses:
//   g++ -std=c++17 -o /tmp/hxvr_test pc/tests/protocol_test.cpp && /tmp/hxvr_test pc/tests/golden
// hello.bin, tracking.bin and idr.bin come from the phone's encoder (core HxvrGoldenTest); this
// writes config.bin, video.bin and haptic.bin, which that test parses.
#include "../common/hxvr_protocol.h"
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)
static bool near(float a, float b) { return std::fabs(a - b) < 1e-6f; }

static std::vector<uint8_t> load(const std::string& p) {
    std::ifstream f(p, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>()};
}
static void save(const std::string& p, const std::vector<uint8_t>& b) {
    std::ofstream f(p, std::ios::binary); f.write(reinterpret_cast<const char*>(b.data()), b.size());
}
/** A framed message: checks the length and returns the type and the payload. */
static bool frame(const std::vector<uint8_t>& m, uint8_t& type, const uint8_t*& p, size_t& n) {
    if (m.size() < 5) return false;
    uint32_t len; std::memcpy(&len, m.data(), 4);
    if (len != m.size() - 4) return false;
    type = m[4]; p = m.data() + 5; n = len - 1;
    return true;
}

int main(int argc, char** argv) {
    std::string dir = argc > 1 ? argv[1] : "pc/tests/golden";
    uint8_t type; const uint8_t* p; size_t n;

    auto hello = load(dir + "/hello.bin");
    CHECK(frame(hello, type, p, n) && type == hxvr::HELLO);
    hxvr::Hello h;
    CHECK(hxvr::ParseHello(p, n, h));
    CHECK(h.version == hxvr::kVersion && h.eyeWidth == 1664 && h.eyeHeight == 1840 && h.refreshHz == 72);
    CHECK(near(h.tanLeft, 1.1f) && near(h.tanRight, 0.9f) && near(h.tanUp, 1.2f) && near(h.tanDown, 1.25f) && near(h.ipd, 0.064f));
    CHECK(h.name == "Galaxy A54" && h.codecs == 3 && h.maxMbps == 40);

    auto tr = load(dir + "/tracking.bin");
    CHECK(frame(tr, type, p, n) && type == hxvr::TRACKING && n == hxvr::kTrackingSize);
    hxvr::Tracking t;
    CHECK(hxvr::ParseTracking(p, n, t));
    CHECK(t.timeNs == 123456789012345ULL && near(t.head[1], 1.6f) && near(t.head[4], 0.38268343f) && near(t.head[6], 0.9238795f));
    const auto& c = t.controllers[1];
    CHECK(c.flags == (hxvr::CTRL_CONNECTED | hxvr::CTRL_POSE_VALID) && near(c.pose[0], 0.25f) && near(c.pose[6], 1.f));
    CHECK(c.buttons == (hxvr::BTN_PRIMARY | hxvr::BTN_TRIGGER_TOUCH) && near(c.trigger, 0.9f) && near(c.grip, 0.25f));
    CHECK(near(c.joyX, -0.5f) && near(c.joyY, 0.75f) && near(c.curls[1], 0.8f));
    CHECK(t.controllers[0].flags == 0);

    auto idr = load(dir + "/idr.bin");
    CHECK(frame(idr, type, p, n) && type == hxvr::IDR_REQUEST && n == 0);

    save(dir + "/config.bin", hxvr::MakeConfig(1248, 1376, 60, 25000, hxvr::CODEC_HEVC, false));
    const float q[4] = {0, 0.70710677f, 0, 0.70710677f};
    const uint8_t nal[] = {0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0x65};
    save(dir + "/video.bin", hxvr::MakeVideo(42, 987654321, q, true, nal, sizeof nal));
    save(dir + "/haptic.bin", hxvr::MakeHaptic(1, 0.05f, 160.f, 0.8f));

    std::printf(failures ? "%d failures\n" : "protocol ok\n", failures);
    return failures ? 1 : 0;
}
