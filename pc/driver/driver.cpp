// HyperXR SteamVR driver: the phone is the headset. It reports the phone's ARCore head pose,
// emulates two Quest 3 Touch controllers (driven by Joy-Cons or by the tracked hands), receives
// SteamVR's frames through a virtual display, encodes them to H.264 and streams them back.
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d10.h>
#include <dxgi1_6.h>
#include <mmsystem.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdarg>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "openvr_driver.h"
#include "../common/config.h"
#include "../common/perf.h"
#include "../common/hxvr_protocol.h"
#include "../common/discovery.h"
#include "encoder.h"

using namespace vr;

// SteamVR (MSVC) and this driver (MinGW) disagree on where a member function's struct return goes;
// openvr_driver.h declares those methods with the return buffer as an argument under MinGW.
#if defined(__MINGW32__) && defined(_WIN64)
#define HX_POSE_RETURN(expr) DriverPose_t* GetPose(DriverPose_t* out) override { *out = (expr); return out; }
#else
#define HX_POSE_RETURN(expr) DriverPose_t GetPose() override { return (expr); }
#endif

namespace {

double NowSeconds() {
    static LARGE_INTEGER freq = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return f; }();
    LARGE_INTEGER c; QueryPerformanceCounter(&c);
    return double(c.QuadPart) / double(freq.QuadPart);
}

void Log(const char* fmt, ...) {
    char buf[1024];
    va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    if (VRDriverLog()) VRDriverLog()->Log(buf);
}

HmdQuaternion_t Quat(const float* q) { return {q[3], q[0], q[1], q[2]}; }

/** Shared state between SteamVR's threads and the network thread. */
struct Status {
    std::mutex m;
    std::string phone = "aguardando o celular";
    std::string encoder = "-";
    std::string gpu = "-";
    std::string error;
    bool restart = false;  // the PC app restarts SteamVR when this is set (no click needed)
    double fps = 0, mbps = 0, targetMbps = 0;
    void Write() {
        std::lock_guard<std::mutex> l(m);
        FILE* f = _wfopen(hxvr::StatusPath().c_str(), L"w");
        if (!f) return;
        fprintf(f, "gpu=%s\n", gpu.c_str());
        fprintf(f, "phone=%s\nencoder=%s\nfps=%.0f\nmbps=%.1f\ntarget=%.0f\nerror=%s\n", phone.c_str(), encoder.c_str(), fps, mbps, targetMbps, error.c_str());
        fprintf(f, "restart=%d\n", restart ? 1 : 0);
        fclose(f);
    }
};
Status g_status;

// ------------------------------------------------------------------ network
class Link {
public:
    std::function<void(const hxvr::Hello&)> onHello;
    std::function<void(const hxvr::Tracking&)> onTracking;
    std::function<void()> onKeyFrameRequest;
    std::function<void()> onConnected;

    void Start(const std::string& phoneIp) {
        phoneIp_ = phoneIp;
        WSADATA w; WSAStartup(MAKEWORD(2, 2), &w);
        running_ = true;
        thread_ = std::thread([this] { Run(); });
    }

    void Stop() {
        running_ = false;
        CloseSocket();
        if (listen_ != INVALID_SOCKET) { closesocket(listen_); listen_ = INVALID_SOCKET; }
        if (thread_.joinable()) thread_.join();
    }

    bool Connected() const { return sock_ != INVALID_SOCKET; }

    /** Sends a whole message; video frames are skipped while the socket is backed up. */
    bool Send(const std::vector<uint8_t>& msg) {
        std::lock_guard<std::mutex> l(sendMutex_);
        SOCKET s = sock_;
        if (s == INVALID_SOCKET) return false;
        size_t off = 0;
        while (off < msg.size()) {
            int n = send(s, reinterpret_cast<const char*>(msg.data() + off), int(msg.size() - off), 0);
            if (n <= 0) return false;
            off += size_t(n);
        }
        return true;
    }

private:
    void CloseSocket() {
        std::lock_guard<std::mutex> l(sendMutex_);
        if (sock_ != INVALID_SOCKET) { closesocket(sock_); sock_ = INVALID_SOCKET; }
    }

    static void Tune(SOCKET s) {
        BOOL one = TRUE; setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&one), sizeof one);
        int big = 4 << 20; setsockopt(s, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&big), sizeof big);
    }

    /** Outgoing connection to the phone (preferred: the Windows firewall allows it). */
    static SOCKET Dial(const std::string& ip) {
        if (ip.empty()) return INVALID_SOCKET;
        SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        sockaddr_in a = {}; a.sin_family = AF_INET; a.sin_port = htons(hxvr::kPort);
        if (inet_pton(AF_INET, ip.c_str(), &a.sin_addr) != 1) { closesocket(s); return INVALID_SOCKET; }
        u_long nb = 1; ioctlsocket(s, FIONBIO, &nb);
        connect(s, reinterpret_cast<sockaddr*>(&a), sizeof a);
        fd_set wr; FD_ZERO(&wr); FD_SET(s, &wr);
        timeval tv = {1, 0};
        if (select(0, nullptr, &wr, nullptr, &tv) != 1) { closesocket(s); return INVALID_SOCKET; }
        int err = 0; int len = sizeof err;
        getsockopt(s, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&err), &len);
        if (err != 0) { closesocket(s); return INVALID_SOCKET; }
        nb = 0; ioctlsocket(s, FIONBIO, &nb);
        return s;
    }

    /** Incoming connection from the phone (works when the firewall allows SteamVR in). */
    SOCKET AcceptOne() {
        if (listen_ == INVALID_SOCKET) {
            listen_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
            BOOL one = TRUE; setsockopt(listen_, SOL_SOCKET, SO_REUSEADDR, reinterpret_cast<const char*>(&one), sizeof one);
            sockaddr_in a = {}; a.sin_family = AF_INET; a.sin_port = htons(hxvr::kPort); a.sin_addr.s_addr = INADDR_ANY;
            if (bind(listen_, reinterpret_cast<sockaddr*>(&a), sizeof a) != 0 || listen(listen_, 2) != 0) { closesocket(listen_); listen_ = INVALID_SOCKET; return INVALID_SOCKET; }
        }
        fd_set rd; FD_ZERO(&rd); FD_SET(listen_, &rd);
        timeval tv = {0, 200000};
        if (select(0, &rd, nullptr, nullptr, &tv) != 1) return INVALID_SOCKET;
        return accept(listen_, nullptr, nullptr);
    }

    bool ReadAll(SOCKET s, uint8_t* p, size_t n) {
        while (n > 0) {
            int r = recv(s, reinterpret_cast<char*>(p), int(n), 0);
            if (r <= 0) return false;
            p += r; n -= size_t(r);
        }
        return true;
    }

    /**
     * Once a second, while no phone is connected: a discovery beacon (the phone's answer gives its
     * address), and the IP in pc.ini again (the app may have saved a new one since SteamVR started).
     */
    void Look() {
        std::string ip, name;
        if (discovery_.Poll(150, ip, name) && ip != foundIp_) {
            foundIp_ = ip;
            Log("HyperMagic: phone %s found at %s\n", name.c_str(), ip.c_str());
        }
        hxvr::Config c; c.Load();
        phoneIp_ = c.phoneIp;
        std::string where = !foundIp_.empty() ? "achado na rede em " + foundIp_ + ", conectando"
                          : !phoneIp_.empty() ? "procurando o celular (" + phoneIp_ + ") e na rede"
                                              : "procurando o celular na rede";
        { std::lock_guard<std::mutex> l(g_status.m); g_status.phone = where; }
        g_status.Write();
    }

    void Run() {
        double lastLook = -10;
        while (running_) {
            if (NowSeconds() - lastLook > 1.0) { Look(); lastLook = NowSeconds(); }
            SOCKET s = Dial(foundIp_);
            if (s == INVALID_SOCKET && phoneIp_ != foundIp_) s = Dial(phoneIp_);
            if (s == INVALID_SOCKET) s = AcceptOne();
            if (s == INVALID_SOCKET) { std::this_thread::sleep_for(std::chrono::milliseconds(100)); continue; }
            Tune(s);
            { std::lock_guard<std::mutex> l(sendMutex_); sock_ = s; }
            Log("HyperMagic: phone connected\n");
            if (onConnected) onConnected();
            std::vector<uint8_t> buf;
            while (running_) {
                uint32_t len = 0;
                if (!ReadAll(s, reinterpret_cast<uint8_t*>(&len), 4) || len == 0 || len > (1u << 20)) break;
                buf.resize(len);
                if (!ReadAll(s, buf.data(), len)) break;
                const uint8_t* p = buf.data() + 1; size_t n = len - 1;
                switch (buf[0]) {
                    case hxvr::HELLO: { hxvr::Hello h; if (hxvr::ParseHello(p, n, h) && onHello) onHello(h); break; }
                    case hxvr::TRACKING: { hxvr::Tracking t; if (hxvr::ParseTracking(p, n, t) && onTracking) onTracking(t); break; }
                    case hxvr::IDR_REQUEST: if (onKeyFrameRequest) onKeyFrameRequest(); break;
                    default: break;
                }
            }
            CloseSocket();
            { std::lock_guard<std::mutex> l(g_status.m); g_status.phone = "desconectado"; }
            g_status.Write();
            Log("HyperMagic: phone disconnected\n");
        }
    }

    std::string phoneIp_, foundIp_;
    hxvr::Discovery discovery_;
    std::atomic<bool> running_{false};
    SOCKET sock_ = INVALID_SOCKET;
    SOCKET listen_ = INVALID_SOCKET;
    std::mutex sendMutex_;
    std::thread thread_;
};

// ------------------------------------------------------------------ controllers (Quest 3 Touch)
class TouchController : public ITrackedDeviceServerDriver {
public:
    explicit TouchController(bool left) : left_(left) {}

    EVRInitError Activate(uint32_t id) override {
        id_ = id;
        auto* props = VRProperties();
        PropertyContainerHandle_t c = props->TrackedDeviceToPropertyContainer(id);
        // Identify as Meta Quest 3 Touch Plus so games load their Touch bindings and models
        props->SetStringProperty(c, Prop_TrackingSystemName_String, "oculus");
        props->SetStringProperty(c, Prop_ManufacturerName_String, "Oculus");
        props->SetStringProperty(c, Prop_ModelNumber_String, left_ ? "Meta Quest 3 (Left Controller)" : "Meta Quest 3 (Right Controller)");
        props->SetStringProperty(c, Prop_ControllerType_String, "oculus_touch");
        props->SetStringProperty(c, Prop_InputProfilePath_String, "{oculus}/input/touch_profile.json");
        props->SetStringProperty(c, Prop_RenderModelName_String, left_ ? "oculus_quest2_controller_left" : "oculus_quest2_controller_right");
        props->SetStringProperty(c, Prop_SerialNumber_String, left_ ? "HXR-TOUCH-L" : "HXR-TOUCH-R");
        props->SetInt32Property(c, Prop_ControllerRoleHint_Int32, left_ ? TrackedControllerRole_LeftHand : TrackedControllerRole_RightHand);
        auto* in = VRDriverInput();
        auto b = [&](const char* path, VRInputComponentHandle_t& h) { in->CreateBooleanComponent(c, path, &h); };
        auto s1 = [&](const char* path, VRInputComponentHandle_t& h) { in->CreateScalarComponent(c, path, &h, VRScalarType_Absolute, VRScalarUnits_NormalizedOneSided); };
        auto s2 = [&](const char* path, VRInputComponentHandle_t& h) { in->CreateScalarComponent(c, path, &h, VRScalarType_Absolute, VRScalarUnits_NormalizedTwoSided); };
        b(left_ ? "/input/x/click" : "/input/a/click", primary_);
        b(left_ ? "/input/x/touch" : "/input/a/touch", primaryTouch_);
        b(left_ ? "/input/y/click" : "/input/b/click", secondary_);
        b(left_ ? "/input/y/touch" : "/input/b/touch", secondaryTouch_);
        b(left_ ? "/input/menu/click" : "/input/system/click", menu_);
        b("/input/joystick/click", stickClick_);
        b("/input/joystick/touch", stickTouch_);
        s2("/input/joystick/x", stickX_);
        s2("/input/joystick/y", stickY_);
        s1("/input/trigger/value", trigger_);
        b("/input/trigger/click", triggerClick_);
        b("/input/trigger/touch", triggerTouch_);
        s1("/input/grip/value", grip_);
        b("/input/grip/touch", gripTouch_);
        b("/input/thumbrest/touch", thumbrest_);
        in->CreateHapticComponent(c, "/output/haptic", &haptic_);
        return VRInitError_None;
    }

    void Deactivate() override { id_ = k_unTrackedDeviceIndexInvalid; }
    void EnterStandby() override {}
    void* GetComponent(const char*) override { return nullptr; }
    void DebugRequest(const char*, char* resp, uint32_t size) override { if (size) resp[0] = 0; }
    HX_POSE_RETURN(pose_)

    uint32_t Id() const { return id_; }
    VRInputComponentHandle_t Haptic() const { return haptic_; }

    void Update(const hxvr::Controller& c) {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        DriverPose_t p = {};
        p.qWorldFromDriverRotation = {1, 0, 0, 0};
        p.qDriverFromHeadRotation = {1, 0, 0, 0};
        bool valid = (c.flags & hxvr::CTRL_CONNECTED) && (c.flags & hxvr::CTRL_POSE_VALID);
        p.deviceIsConnected = (c.flags & hxvr::CTRL_CONNECTED) != 0;
        p.poseIsValid = valid;
        p.result = valid ? TrackingResult_Running_OK : TrackingResult_Running_OutOfRange;
        p.vecPosition[0] = c.pose[0]; p.vecPosition[1] = c.pose[1]; p.vecPosition[2] = c.pose[2];
        p.qRotation = Quat(c.pose + 3);
        p.poseTimeOffset = -0.03;
        pose_ = p;
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, pose_, sizeof(DriverPose_t));
        auto* in = VRDriverInput();
        uint32_t bt = c.buttons;
        in->UpdateBooleanComponent(primary_, bt & hxvr::BTN_PRIMARY, 0);
        in->UpdateBooleanComponent(primaryTouch_, bt & hxvr::BTN_PRIMARY, 0);
        in->UpdateBooleanComponent(secondary_, bt & hxvr::BTN_SECONDARY, 0);
        in->UpdateBooleanComponent(secondaryTouch_, bt & hxvr::BTN_SECONDARY, 0);
        in->UpdateBooleanComponent(menu_, bt & (hxvr::BTN_MENU | hxvr::BTN_SYSTEM), 0);
        in->UpdateBooleanComponent(stickClick_, bt & hxvr::BTN_STICK_CLICK, 0);
        in->UpdateBooleanComponent(stickTouch_, bt & (hxvr::BTN_STICK_TOUCH | hxvr::BTN_STICK_CLICK), 0);
        in->UpdateScalarComponent(stickX_, c.joyX, 0);
        in->UpdateScalarComponent(stickY_, c.joyY, 0);
        in->UpdateScalarComponent(trigger_, c.trigger, 0);
        in->UpdateBooleanComponent(triggerClick_, c.trigger > 0.75f, 0);
        in->UpdateBooleanComponent(triggerTouch_, (bt & hxvr::BTN_TRIGGER_TOUCH) || c.trigger > 0.05f, 0);
        in->UpdateScalarComponent(grip_, c.grip, 0);
        in->UpdateBooleanComponent(gripTouch_, (bt & hxvr::BTN_GRIP_TOUCH) || c.grip > 0.05f, 0);
        in->UpdateBooleanComponent(thumbrest_, bt & hxvr::BTN_THUMBREST_TOUCH, 0);
    }

private:
    bool left_;
    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    DriverPose_t pose_ = {};
    VRInputComponentHandle_t primary_ = 0, primaryTouch_ = 0, secondary_ = 0, secondaryTouch_ = 0, menu_ = 0, stickClick_ = 0, stickTouch_ = 0,
        stickX_ = 0, stickY_ = 0, trigger_ = 0, triggerClick_ = 0, triggerTouch_ = 0, grip_ = 0, gripTouch_ = 0, thumbrest_ = 0, haptic_ = 0;
};

// ------------------------------------------------------------------ headset
class PhoneHmd : public ITrackedDeviceServerDriver, public IVRDisplayComponent, public IVRVirtualDisplay {
public:
    PhoneHmd(const hxvr::Config& cfg, Link* link) : cfg_(cfg), link_(link) {
        eyeW_ = cfg.eyeWidth & ~1; eyeH_ = cfg.eyeHeight & ~1;
    }

    EVRInitError Activate(uint32_t id) override {
        id_ = id;
        auto* props = VRProperties();
        PropertyContainerHandle_t c = props->TrackedDeviceToPropertyContainer(id);
        props->SetStringProperty(c, Prop_ModelNumber_String, "HyperMagic (celular)");
        props->SetStringProperty(c, Prop_ManufacturerName_String, "HyperMagic");
        props->SetStringProperty(c, Prop_TrackingSystemName_String, "hyperxr");
        props->SetStringProperty(c, Prop_SerialNumber_String, "HXR-HMD");
        props->SetFloatProperty(c, Prop_UserIpdMeters_Float, cfg_.ipd);
        props->SetFloatProperty(c, Prop_UserHeadToEyeDepthMeters_Float, 0.f);
        props->SetFloatProperty(c, Prop_DisplayFrequency_Float, float(cfg_.refreshHz));
        props->SetFloatProperty(c, Prop_SecondsFromVsyncToPhotons_Float, 0.03f);
        props->SetBoolProperty(c, Prop_IsOnDesktop_Bool, false);
        props->SetBoolProperty(c, Prop_DisplayDebugMode_Bool, false);
        props->SetUint64Property(c, Prop_CurrentUniverseId_Uint64, 4242);
        props->SetBoolProperty(c, Prop_HasDisplayComponent_Bool, true);
        props->SetBoolProperty(c, Prop_HasVirtualDisplayComponent_Bool, true);
        props->SetBoolProperty(c, Prop_HasCameraComponent_Bool, false);
        props->SetBoolProperty(c, Prop_HasDriverDirectModeComponent_Bool, false);
        // a virtual display has no real vsync: SteamVR paces itself on our VsyncEvent calls
        props->SetBoolProperty(c, Prop_DriverDirectModeSendsVsyncEvents_Bool, true);
        // SteamVR must render on the GPU we encode on: on a laptop with two GPUs it would otherwise
        // pick one and hand us frames we can't open. The fast GPU it is.
        adapter_ = PickAdapter();
        if (adapter_) {
            DXGI_ADAPTER_DESC ad; adapter_->GetDesc(&ad);
            uint64_t luid = (uint64_t(uint32_t(ad.AdapterLuid.HighPart)) << 32) | ad.AdapterLuid.LowPart;
            props->SetUint64Property(c, Prop_GraphicsAdapterLuid_Uint64, luid);
        }
        pose_.poseIsValid = false;
        pose_.deviceIsConnected = true;
        pose_.result = TrackingResult_Uninitialized;
        pose_.qWorldFromDriverRotation = {1, 0, 0, 0};
        pose_.qDriverFromHeadRotation = {1, 0, 0, 0};
        pose_.qRotation = {1, 0, 0, 0};
        vsyncThread_ = std::thread([this] { VsyncLoop(); });
        return VRInitError_None;
    }

    void Deactivate() override {
        running_ = false;
        if (vsyncThread_.joinable()) vsyncThread_.join();
        if (encoder_) { encoder_->Shutdown(); delete encoder_; encoder_ = nullptr; }
        for (auto& s : shared_) if (s.tex) s.tex->Release();
        shared_.clear();
        if (copy_) { copy_->Release(); copy_ = nullptr; }
        if (query_) { query_->Release(); query_ = nullptr; }
        if (context_) { context_->Release(); context_ = nullptr; }
        if (device_) { device_->Release(); device_ = nullptr; }
        if (adapter_) { adapter_->Release(); adapter_ = nullptr; }
        id_ = k_unTrackedDeviceIndexInvalid;
    }

    void EnterStandby() override {}
    void* GetComponent(const char* name) override {
        if (!strcmp(name, IVRDisplayComponent_Version)) return static_cast<IVRDisplayComponent*>(this);
        if (!strcmp(name, IVRVirtualDisplay_Version)) return static_cast<IVRVirtualDisplay*>(this);
        return nullptr;
    }
    void DebugRequest(const char*, char* resp, uint32_t size) override { if (size) resp[0] = 0; }
    DriverPose_t CurrentPose() { std::lock_guard<std::mutex> l(poseMutex_); return pose_; }
    HX_POSE_RETURN(CurrentPose())

    // ---- display (undistorted: the phone applies its own lens correction)
    void GetWindowBounds(int32_t* x, int32_t* y, uint32_t* w, uint32_t* h) override { *x = 0; *y = 0; *w = eyeW_ * 2; *h = eyeH_; }
    bool IsDisplayOnDesktop() override { return false; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t* w, uint32_t* h) override { *w = eyeW_; *h = eyeH_; }
    void GetEyeOutputViewport(EVREye eye, uint32_t* x, uint32_t* y, uint32_t* w, uint32_t* h) override {
        *x = eye == Eye_Left ? 0 : eyeW_; *y = 0; *w = eyeW_; *h = eyeH_;
    }
    void GetProjectionRaw(EVREye eye, float* left, float* right, float* top, float* bottom) override {
        // phone sends the left eye; the right eye is its mirror image
        float l = cfg_.tanLeft, r = cfg_.tanRight;
        if (eye == Eye_Right) std::swap(l, r);
        *left = -l; *right = r; *top = -cfg_.tanUp; *bottom = cfg_.tanDown;
    }
    static DistortionCoordinates_t NoDistortion(float u, float v) {
        DistortionCoordinates_t d = {};
        d.rfRed[0] = d.rfGreen[0] = d.rfBlue[0] = u;
        d.rfRed[1] = d.rfGreen[1] = d.rfBlue[1] = v;
        return d;
    }
#if defined(__MINGW32__) && defined(_WIN64)
    DistortionCoordinates_t* ComputeDistortion(DistortionCoordinates_t* out, EVREye, float u, float v) override { *out = NoDistortion(u, v); return out; }
#else
    DistortionCoordinates_t ComputeDistortion(EVREye, float u, float v) override { return NoDistortion(u, v); }
#endif
    bool ComputeInverseDistortion(HmdVector2_t* out, EVREye, uint32_t, float u, float v) override { out->v[0] = u; out->v[1] = v; return true; }

    // ---- virtual display: SteamVR hands over each finished frame here
    void Present(const PresentInfo_t* info, uint32_t) override {
        if (!EnsureDevice()) return;
        ID3D11Texture2D* src = OpenShared(info->backbufferTextureHandle);
        if (!src) return;
        D3D11_TEXTURE2D_DESC d; src->GetDesc(&d);
        if (!copy_ || copyW_ != d.Width || copyH_ != d.Height) {
            if (copy_) copy_->Release();
            D3D11_TEXTURE2D_DESC cd = d;
            cd.Format = RgbFormat(d.Format); cd.MiscFlags = 0; cd.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
            cd.Usage = D3D11_USAGE_DEFAULT; cd.CPUAccessFlags = 0; cd.MipLevels = 1; cd.ArraySize = 1; cd.SampleDesc.Count = 1;
            if (FAILED(device_->CreateTexture2D(&cd, nullptr, &copy_))) { copy_ = nullptr; return; }
            copyW_ = d.Width; copyH_ = d.Height;
            StartEncoder(int(d.Width), int(d.Height));
        } else if (restartEncoder_.exchange(false)) {
            StartEncoder(int(copyW_), int(copyH_));
        } else if (announce_.exchange(false) && encoder_) {
            link_->Send(hxvr::MakeConfig(uint16_t(cfg_.eyeWidth), uint16_t(cfg_.eyeHeight), uint16_t(cfg_.refreshHz), uint32_t(kbps_),
                                         encoder_->Hevc() ? hxvr::CODEC_HEVC : hxvr::CODEC_H264, false));
            encoder_->RequestKeyFrame();
        }
        context_->CopyResource(copy_, src);
        context_->End(query_);
        context_->Flush();
        presentPending_ = true;
        if (keyFrame_.exchange(false) && encoder_) encoder_->RequestKeyFrame();
        if (encoder_ && link_->Connected()) encoder_->Submit(copy_, RenderedPoseTag());
        frames_++;
    }

    void WaitForPresent() override {
        if (!presentPending_ || !query_) return;
        // SteamVR may reuse its backbuffer once our copy has finished on the GPU
        BOOL done = FALSE;
        double until = NowSeconds() + 0.05;
        // poll gently: spinning here would steal a core from the game on a 4-core laptop
        while (context_->GetData(query_, &done, sizeof done, 0) != S_OK && NowSeconds() < until) Sleep(1);
        presentPending_ = false;
    }

    bool GetTimeSinceLastVsync(float* seconds, uint64_t* frameCounter) override {
        double now = NowSeconds();
        double period = 1.0 / cfg_.refreshHz;
        double since = std::fmod(now - vsyncBase_, period);
        *seconds = float(since);
        *frameCounter = uint64_t((now - vsyncBase_) / period);
        return true;
    }

    // ---- tracking from the phone
    void UpdateHead(const hxvr::Tracking& t) {
        if (id_ == k_unTrackedDeviceIndexInvalid) return;
        DriverPose_t p = {};
        p.qWorldFromDriverRotation = {1, 0, 0, 0};
        p.qDriverFromHeadRotation = {1, 0, 0, 0};
        p.deviceIsConnected = true; p.poseIsValid = true; p.result = TrackingResult_Running_OK;
        p.vecPosition[0] = t.head[0]; p.vecPosition[1] = t.head[1]; p.vecPosition[2] = t.head[2];
        p.qRotation = Quat(t.head + 3);
        {
            std::lock_guard<std::mutex> l(poseMutex_);
            pose_ = p;
            history_.push_back({NowSeconds(), t.timeNs, {t.head[3], t.head[4], t.head[5], t.head[6]}});
            while (history_.size() > 64) history_.pop_front();
        }
        VRServerDriverHost()->TrackedDevicePoseUpdated(id_, p, sizeof(DriverPose_t));
    }

    void RequestKeyFrame() { if (encoder_) encoder_->RequestKeyFrame(); }
    /** From the network thread: the encoder makes the next frame a key frame. */
    void RequestKeyFrameAsync() { keyFrame_ = true; }

    /** What the phone decodes and the most it wants: applied by restarting the encoder. */
    void SetStream(uint8_t codecs, int maxMbps) {
        bool hevc = (codecs & hxvr::CODEC_HEVC) != 0;
        int maxKbps = maxMbps > 0 ? maxMbps * 1000 : 1000000;
        if (hevc != wantHevc_.load() || maxKbps != maxKbps_.load()) {
            wantHevc_ = hevc; maxKbps_ = maxKbps;
            restartEncoder_ = true;
        } else {
            // same stream: the phone (re)connected and needs the codec and a key frame (sent on
            // SteamVR's present thread, which owns the encoder)
            announce_ = true;
        }
    }
    uint32_t Id() const { return id_; }

private:
    struct PoseSample { double at; uint64_t timeNs; float q[4]; };

    /** The pose SteamVR most likely rendered this frame with: the one reported about a frame ago. */
    uint64_t RenderedPoseTag() {
        std::lock_guard<std::mutex> l(poseMutex_);
        if (history_.empty()) return 0;
        double target = NowSeconds() - 1.0 / cfg_.refreshHz;
        const PoseSample* best = &history_.back();
        for (auto& s : history_) if (std::fabs(s.at - target) < std::fabs(best->at - target)) best = &s;
        uint64_t tag = nextTag_++;
        tagged_[tag % kTagRing] = *best;
        return tag;
    }

    static DXGI_FORMAT RgbFormat(DXGI_FORMAT f) {
        switch (f) {
            case DXGI_FORMAT_B8G8R8A8_TYPELESS: case DXGI_FORMAT_B8G8R8A8_UNORM_SRGB: case DXGI_FORMAT_B8G8R8A8_UNORM: return DXGI_FORMAT_B8G8R8A8_UNORM;
            default: return DXGI_FORMAT_R8G8B8A8_UNORM;
        }
    }

    /** The high-performance GPU (the discrete one on a laptop), or the first one. */
    static IDXGIAdapter1* PickAdapter() {
        IDXGIFactory1* f1 = nullptr;
        if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), (void**)&f1))) return nullptr;
        IDXGIAdapter1* a = nullptr;
        IDXGIFactory6* f6 = nullptr;
        if (SUCCEEDED(f1->QueryInterface(__uuidof(IDXGIFactory6), (void**)&f6))) {
            f6->EnumAdapterByGpuPreference(0, DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE, __uuidof(IDXGIAdapter1), (void**)&a);
            f6->Release();
        }
        if (!a) f1->EnumAdapters1(0, &a);
        f1->Release();
        return a;
    }

    bool EnsureDevice() {
        if (device_) return true;
        D3D_FEATURE_LEVEL fl[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
        // on the adapter SteamVR was told to render on (Prop_GraphicsAdapterLuid_Uint64)
        HRESULT hr = D3D11CreateDevice(adapter_, adapter_ ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                       D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                                       fl, 2, D3D11_SDK_VERSION, &device_, nullptr, &context_);
        if (FAILED(hr)) { SetError("Direct3D 11 indisponível"); return false; }
        ID3D10Multithread* mt = nullptr;
        if (SUCCEEDED(device_->QueryInterface(__uuidof(ID3D10Multithread), (void**)&mt))) { mt->SetMultithreadProtected(TRUE); mt->Release(); }
        D3D11_QUERY_DESC qd = {D3D11_QUERY_EVENT, 0};
        device_->CreateQuery(&qd, &query_);
        return true;
    }

    ID3D11Texture2D* OpenShared(SharedTextureHandle_t handle) {
        for (auto& s : shared_) if (s.handle == handle) return s.tex;
        ID3D11Texture2D* tex = nullptr;
        if (FAILED(device_->OpenSharedResource(reinterpret_cast<HANDLE>(handle), __uuidof(ID3D11Texture2D), (void**)&tex))) {
            SetError("não foi possível abrir a imagem do SteamVR (GPU diferente?)");
            return nullptr;
        }
        shared_.push_back({handle, tex});
        return tex;
    }

    void StartEncoder(int w, int h) {
        if (encoder_) { encoder_->Shutdown(); delete encoder_; encoder_ = nullptr; }
        std::string err;
        auto out = [this](const uint8_t* data, size_t n, bool key, uint64_t tag) {
            const PoseSample& s = tagged_[tag % kTagRing];
            bytes_ += n;
            double t0 = NowSeconds();
            link_->Send(hxvr::MakeVideo(frameId_++, s.timeNs, s.q, key, data, n));
            AdaptBitrate(NowSeconds() - t0);
        };
        // HEVC halves the bitrate for the same picture: use it when the phone decodes it
        bool hevc = wantHevc_.load();
        kbps_ = std::min(cfg_.bitrateMbps * 1000, maxKbps_.load());
        for (int attempt = 0; attempt < 2 && !encoder_; attempt++) {
            auto* e = new VideoEncoder();
            if (e->Init(device_, w, h, cfg_.refreshHz, kbps_, hevc, out, err)) { encoder_ = e; break; }
            delete e;
            if (!hevc) break;
            hevc = false;  // no HEVC encoder on this GPU: H.264
        }
        if (!encoder_) { SetError("codificador: " + err); return; }
        encoder_->RequestKeyFrame();
        link_->Send(hxvr::MakeConfig(uint16_t(cfg_.eyeWidth), uint16_t(cfg_.eyeHeight), uint16_t(cfg_.refreshHz), uint32_t(kbps_),
                                     hevc ? hxvr::CODEC_HEVC : hxvr::CODEC_H264, false));
        std::lock_guard<std::mutex> l(g_status.m);
        g_status.encoder = encoder_->Name() + (hevc ? " HEVC" : " H.264") + (encoder_->Hardware() ? " (GPU)" : " (software, lento)");
    }

    /**
     * Adaptive bitrate: a send that blocks means Wi-Fi cannot keep up, so the bitrate drops 15%
     * at once; when sends are quick for 2 s it climbs back 5% at a time up to the limit.
     */
    void AdaptBitrate(double sendSeconds) {
        double frame = 1.0 / cfg_.refreshHz;
        sendAvg_ = sendAvg_ * 0.9 + sendSeconds * 0.1;
        double now = NowSeconds();
        if (sendAvg_ > frame * 0.5 && now - lastRateChange_ > 0.5) {
            kbps_ = std::max(4000, int(kbps_ * 0.85));
            lastRateChange_ = now;
        } else if (sendAvg_ < frame * 0.15 && now - lastRateChange_ > 2.0) {
            int cap = std::min(cfg_.bitrateMbps * 1000, maxKbps_.load());
            if (kbps_ >= cap) return;
            kbps_ = std::min(cap, int(kbps_ * 1.05) + 100);
            lastRateChange_ = now;
        } else return;
        if (encoder_) encoder_->SetBitrate(kbps_);
    }

    void SetError(const std::string& e) {
        Log("HyperMagic: %s\n", e.c_str());
        { std::lock_guard<std::mutex> l(g_status.m); g_status.error = e; }
        g_status.Write();
    }

    /** SteamVR paces its compositor on these events (a virtual display has no real vsync). */
    void VsyncLoop() {
        timeBeginPeriod(1);
        vsyncBase_ = NowSeconds();
        double next = vsyncBase_;
        double lastStats = vsyncBase_;
        uint64_t lastFrames = 0, lastBytes = 0;
        while (running_) {
            next += 1.0 / cfg_.refreshHz;
            double wait = next - NowSeconds();
            // 1 ms timer resolution (timeBeginPeriod) makes Sleep precise enough; no busy waiting
            if (wait > 0.0015) Sleep(DWORD((wait - 0.001) * 1000));
            while (NowSeconds() < next) Sleep(0);
            VRServerDriverHost()->VsyncEvent(0);
            double now = NowSeconds();
            if (now - lastStats > 1.0) {
                uint64_t f = frames_, b = bytes_;
                {
                    std::lock_guard<std::mutex> l(g_status.m);
                    g_status.fps = double(f - lastFrames) / (now - lastStats);
                    g_status.mbps = double(b - lastBytes) * 8 / 1e6 / (now - lastStats);
                    g_status.targetMbps = kbps_ / 1000.0;
                }
                g_status.Write();
                lastFrames = f; lastBytes = b; lastStats = now;
            }
        }
    }

    hxvr::Config cfg_;
    Link* link_;
    uint32_t eyeW_, eyeH_;
    uint32_t id_ = k_unTrackedDeviceIndexInvalid;
    std::mutex poseMutex_;
    DriverPose_t pose_ = {};
    std::deque<PoseSample> history_;
    static constexpr int kTagRing = 64;
    PoseSample tagged_[kTagRing] = {};
    uint64_t nextTag_ = 0;
    uint64_t frameId_ = 0;

    IDXGIAdapter1* adapter_ = nullptr;
    ID3D11Device* device_ = nullptr;
    ID3D11DeviceContext* context_ = nullptr;
    ID3D11Query* query_ = nullptr;
    struct Shared { SharedTextureHandle_t handle; ID3D11Texture2D* tex; };
    std::vector<Shared> shared_;
    ID3D11Texture2D* copy_ = nullptr;
    UINT copyW_ = 0, copyH_ = 0;
    VideoEncoder* encoder_ = nullptr;
    bool presentPending_ = false;
    std::atomic<bool> wantHevc_{false}, restartEncoder_{false}, announce_{false}, keyFrame_{false};
    std::atomic<int> maxKbps_{1000000};
    int kbps_ = 40000;
    double sendAvg_ = 0, lastRateChange_ = 0;

    std::atomic<bool> running_{true};
    std::thread vsyncThread_;
    double vsyncBase_ = 0;
    std::atomic<uint64_t> frames_{0}, bytes_{0};
};

// ------------------------------------------------------------------ provider
class Provider : public IServerTrackedDeviceProvider {
public:
    EVRInitError Init(IVRDriverContext* ctx) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(ctx);
        cfg_.Load();
        ApplySteamVrSettings();
        hmd_ = new PhoneHmd(cfg_, &link_);
        left_ = new TouchController(true);
        right_ = new TouchController(false);
        VRServerDriverHost()->TrackedDeviceAdded("HXR-HMD", TrackedDeviceClass_HMD, hmd_);
        VRServerDriverHost()->TrackedDeviceAdded("HXR-TOUCH-L", TrackedDeviceClass_Controller, left_);
        VRServerDriverHost()->TrackedDeviceAdded("HXR-TOUCH-R", TrackedDeviceClass_Controller, right_);

        link_.onConnected = [this] {
            {
                std::lock_guard<std::mutex> l(g_status.m);
                g_status.phone = "conectado"; g_status.error.clear();
            }
            g_status.Write();
        };
        link_.onHello = [this](const hxvr::Hello& h) { OnHello(h); };
        link_.onTracking = [this](const hxvr::Tracking& t) {
            hmd_->UpdateHead(t);
            left_->Update(t.controllers[0]);
            right_->Update(t.controllers[1]);
        };
        link_.onKeyFrameRequest = [this] { hmd_->RequestKeyFrameAsync(); };
        link_.Start(cfg_.phoneIp);
        g_status.Write();
        Log("HyperMagic driver started (phone %s, eye %dx%d @ %d Hz)\n", cfg_.phoneIp.c_str(), cfg_.eyeWidth, cfg_.eyeHeight, cfg_.refreshHz);
        return VRInitError_None;
    }

    void Cleanup() override {
        link_.Stop();
        VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    }

    const char* const* GetInterfaceVersions() override { return k_InterfaceVersions; }

    void RunFrame() override {
        VREvent_t ev;
        while (VRServerDriverHost()->PollNextEvent(&ev, sizeof ev)) {
            if (ev.eventType != VREvent_Input_HapticVibration) continue;
            const auto& hv = ev.data.hapticVibration;
            uint8_t hand = hv.componentHandle == left_->Haptic() ? 0 : hv.componentHandle == right_->Haptic() ? 1 : 255;
            if (hand != 255) link_.Send(hxvr::MakeHaptic(hand, hv.fDurationSeconds, hv.fFrequency, hv.fAmplitude));
        }
    }

    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

private:
    /**
     * Keeps SteamVR from adding work: our render size is used as is (no automatic supersampling),
     * and on integrated graphics the SteamVR Home environment, which alone can saturate an Intel
     * UHD, is turned off (the dashboard still works).
     */
    void ApplySteamVrSettings() {
        auto* st = VRSettings();
        if (!st) return;
        st->SetBool(k_pch_SteamVR_Section, k_pch_SteamVR_SupersampleManualOverride_Bool, true);
        st->SetFloat(k_pch_SteamVR_Section, k_pch_SteamVR_SupersampleScale_Float, 1.0f);
        int preset = hxvr::ResolvePreset(cfg_.preset);
        if (preset == hxvr::PRESET_WEAK || preset == hxvr::PRESET_WEAKEST) {
            st->SetBool(k_pch_SteamVR_Section, "enableHomeApp", false);
            st->SetBool(k_pch_SteamVR_Section, k_pch_SteamVR_MotionSmoothing_Bool, false);
        }
        bool integrated = false;
        std::wstring gpu = hxvr::PrimaryGpu(&integrated);
        std::lock_guard<std::mutex> l(g_status.m);
        char name[256]; WideCharToMultiByte(CP_UTF8, 0, gpu.c_str(), -1, name, sizeof name, nullptr, nullptr);
        g_status.gpu = std::string(name) + (integrated ? " (integrada)" : "");
    }

    /** The phone describes its screen; a different size or field of view needs one SteamVR restart. */
    void OnHello(const hxvr::Hello& h) {
        {
            std::lock_guard<std::mutex> l(g_status.m);
            g_status.phone = "conectado: " + h.name;
        }
        g_status.Write();
        // the PC renders and streams at its preset's share of what the phone asked for; the phone
        // upscales and reprojects to its own refresh rate
        hxvr::PresetParams pp = hxvr::Params(hxvr::ResolvePreset(cfg_.preset));
        int eyeW = std::max(320, int(h.eyeWidth * pp.scale) / 16 * 16);
        int eyeH = std::max(320, int(h.eyeHeight * pp.scale) / 8 * 8);
        int fps = pp.fps > 0 ? std::min<int>(pp.fps, h.refreshHz) : h.refreshHz;
        bool changed = eyeW != cfg_.eyeWidth || eyeH != cfg_.eyeHeight || fps != cfg_.refreshHz ||
                       std::fabs(h.tanLeft - cfg_.tanLeft) > 0.01f || std::fabs(h.tanRight - cfg_.tanRight) > 0.01f ||
                       std::fabs(h.tanUp - cfg_.tanUp) > 0.01f || std::fabs(h.tanDown - cfg_.tanDown) > 0.01f;
        if (!changed) { hmd_->SetStream(h.codecs, h.maxMbps); return; }
        link_.Send(hxvr::MakeConfig(uint16_t(eyeW), uint16_t(eyeH), uint16_t(fps), uint32_t(cfg_.bitrateMbps * 1000), hxvr::CODEC_H264, true));
        cfg_.eyeWidth = eyeW; cfg_.eyeHeight = eyeH; cfg_.refreshHz = fps;
        cfg_.tanLeft = h.tanLeft; cfg_.tanRight = h.tanRight; cfg_.tanUp = h.tanUp; cfg_.tanDown = h.tanDown; cfg_.ipd = h.ipd;
        hxvr::Config saved; saved.Load();  // keep the IP and bitrate the app may have changed
        saved.eyeWidth = cfg_.eyeWidth; saved.eyeHeight = cfg_.eyeHeight; saved.refreshHz = cfg_.refreshHz;
        saved.tanLeft = cfg_.tanLeft; saved.tanRight = cfg_.tanRight; saved.tanUp = cfg_.tanUp; saved.tanDown = cfg_.tanDown; saved.ipd = cfg_.ipd;
        saved.Save();
        Log("HyperMagic: phone %dx%d @ %d Hz -> rendering %dx%d @ %d fps; restarting SteamVR\n", h.eyeWidth, h.eyeHeight, h.refreshHz, eyeW, eyeH, fps);
        { std::lock_guard<std::mutex> l(g_status.m); g_status.restart = true; g_status.phone = "reiniciando o SteamVR para a tela do celular"; }
        g_status.Write();
        VRServerDriverHost()->RequestRestart("O HyperMagic ajustou o SteamVR para a tela do seu celular.", nullptr, nullptr, nullptr);
    }

    hxvr::Config cfg_;
    Link link_;
    PhoneHmd* hmd_ = nullptr;
    TouchController* left_ = nullptr;
    TouchController* right_ = nullptr;
};

Provider g_provider;

}  // namespace

extern "C" __declspec(dllexport) void* HmdDriverFactory(const char* interfaceName, int* returnCode) {
    if (!strcmp(interfaceName, IServerTrackedDeviceProvider_Version)) return &g_provider;
    if (returnCode) *returnCode = VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
