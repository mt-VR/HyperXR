// H.264 encoding of SteamVR's frames: D3D11 video processor (RGBA -> NV12 on the GPU) feeding the
// Media Foundation hardware encoder (NVIDIA, AMD or Intel); the Microsoft software encoder is the
// fallback when no hardware encoder exists.
#pragma once
#include <d3d11.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>
#include <codecapi.h>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <thread>

class VideoEncoder {
public:
    using Output = std::function<void(const uint8_t* data, size_t size, bool keyFrame, uint64_t tag)>;

    /** [hevc] picks H.265 (half the bitrate for the same picture), else H.264. */
    bool Init(ID3D11Device* device, int width, int height, int fps, int kbps, bool hevc, Output out, std::string& error);
    /** Changes the target bitrate while running (adaptive bitrate). */
    void SetBitrate(int kbps);
    bool Hevc() const { return hevc_; }
    /** Converts [rgba] (a texture of this device, width x height) and queues it; [tag] comes back with the output. */
    void Submit(ID3D11Texture2D* rgba, uint64_t tag);
    void RequestKeyFrame() { keyRequested_ = true; }
    void Shutdown();
    const std::string& Name() const { return name_; }
    bool Hardware() const { return hardware_; }

private:
    bool InitConverter(std::string& error);
    bool InitMft(bool hardware, std::string& error);
    void AsyncLoop();
    void SyncLoop();
    bool FeedInput(int slot, uint64_t tag);
    void DrainOutput();
    void Emit(IMFSample* s);

    ID3D11Device* device_ = nullptr;
    ID3D11DeviceContext* context_ = nullptr;
    ID3D11VideoDevice* vdev_ = nullptr;
    ID3D11VideoContext* vctx_ = nullptr;
    ID3D11VideoProcessorEnumerator* venum_ = nullptr;
    ID3D11VideoProcessor* vproc_ = nullptr;
    static constexpr int kSlots = 4;
    ID3D11Texture2D* nv12_[kSlots] = {};
    ID3D11VideoProcessorOutputView* outView_[kSlots] = {};
    ID3D11Texture2D* staging_ = nullptr;  // software path readback
    ID3D11Texture2D* lastInput_ = nullptr;
    ID3D11VideoProcessorInputView* inView_ = nullptr;
    int nextSlot_ = 0;

    IMFTransform* mft_ = nullptr;
    IMFMediaEventGenerator* events_ = nullptr;
    ICodecAPI* codecApi_ = nullptr;
    IMFDXGIDeviceManager* manager_ = nullptr;
    UINT resetToken_ = 0;
    bool hardware_ = false;
    bool hevc_ = false;
    bool providesSamples_ = false;
    DWORD outputSize_ = 0;
    std::string name_;

    int width_ = 0, height_ = 0, fps_ = 72;
    int64_t frameDuration_ = 0;
    int64_t sampleTime_ = 0;
    Output out_;
    std::map<int64_t, uint64_t> tags_;

    std::mutex mutex_;
    std::condition_variable cv_;
    struct Pending { int slot; uint64_t tag; };
    std::deque<Pending> pending_;
    std::atomic<bool> running_{false};
    std::atomic<bool> keyRequested_{false};
    std::thread thread_;
};
