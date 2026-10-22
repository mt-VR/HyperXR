#include "encoder.h"

#include <mferror.h>
#include <cstring>
#include <vector>

#define SAFE_RELEASE(p) do { if (p) { (p)->Release(); (p) = nullptr; } } while (0)

namespace {
// ICodecAPI's IID (MinGW's headers do not attach it to the interface)
const GUID kIID_ICodecAPI = {0x901db4c7, 0x31ce, 0x41a2, {0x85, 0xdc, 0x8f, 0xa0, 0xbf, 0x41, 0xb8, 0xda}};

std::string Hex(HRESULT hr) { char b[32]; snprintf(b, sizeof b, "0x%08lX", (unsigned long)hr); return b; }

std::string Narrow(const wchar_t* w) {
    if (!w) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    std::string s(n > 0 ? n - 1 : 0, '\0');
    if (n > 1) WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    return s;
}

void SetU32(ICodecAPI* api, const GUID& key, ULONG value) {
    if (!api) return;
    VARIANT v; VariantInit(&v); v.vt = VT_UI4; v.ulVal = value;
    api->SetValue(&key, &v);
}

void SetBool(ICodecAPI* api, const GUID& key, bool value) {
    if (!api) return;
    VARIANT v; VariantInit(&v); v.vt = VT_BOOL; v.boolVal = value ? VARIANT_TRUE : VARIANT_FALSE;
    api->SetValue(&key, &v);
}
}  // namespace

bool VideoEncoder::Init(ID3D11Device* device, int width, int height, int fps, int kbps, bool hevc, Output out, std::string& error) {
    hevc_ = hevc;
    device_ = device; device_->AddRef();
    device_->GetImmediateContext(&context_);
    width_ = width & ~1; height_ = height & ~1; fps_ = fps; out_ = std::move(out);
    frameDuration_ = 10000000LL / fps;
    if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_LITE))) { error = "Media Foundation indisponível"; return false; }
    if (!InitConverter(error)) return false;
    std::string hwError;
    if (!InitMft(true, hwError)) {
        std::string swError;
        if (!InitMft(false, swError)) { error = std::string("nenhum codificador ") + (hevc_ ? "HEVC" : "H.264") + " (GPU: " + hwError + "; software: " + swError + ")"; return false; }
    }
    // bitrate and latency settings
    SetU32(codecApi_, CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
    SetU32(codecApi_, CODECAPI_AVEncCommonMeanBitRate, ULONG(kbps) * 1000);
    SetBool(codecApi_, CODECAPI_AVLowLatencyMode, true);
    SetU32(codecApi_, CODECAPI_AVEncMPVDefaultBPictureCount, 0);
    SetU32(codecApi_, CODECAPI_AVEncMPVGOPSize, ULONG(fps * 2));  // a key frame every 2 s heals lost data
    SetU32(codecApi_, CODECAPI_AVEncCommonQualityVsSpeed, 0);      // fastest preset: latency over quality
    mft_->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
    mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    running_ = true;
    thread_ = std::thread([this] { if (hardware_) AsyncLoop(); else SyncLoop(); });
    return true;
}

bool VideoEncoder::InitConverter(std::string& error) {
    if (FAILED(device_->QueryInterface(__uuidof(ID3D11VideoDevice), (void**)&vdev_)) ||
        FAILED(context_->QueryInterface(__uuidof(ID3D11VideoContext), (void**)&vctx_))) {
        error = "a GPU não tem processador de vídeo D3D11"; return false;
    }
    D3D11_VIDEO_PROCESSOR_CONTENT_DESC cd = {};
    cd.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    cd.InputWidth = width_; cd.InputHeight = height_; cd.OutputWidth = width_; cd.OutputHeight = height_;
    cd.InputFrameRate = {UINT(fps_), 1}; cd.OutputFrameRate = {UINT(fps_), 1};
    cd.Usage = D3D11_VIDEO_USAGE_OPTIMAL_SPEED;
    HRESULT hr = vdev_->CreateVideoProcessorEnumerator(&cd, &venum_);
    if (SUCCEEDED(hr)) hr = vdev_->CreateVideoProcessor(venum_, 0, &vproc_);
    if (FAILED(hr)) { error = "processador de vídeo " + Hex(hr); return false; }
    // full-range RGB in, BT.709 limited-range YUV out (what the phone's decoder expects)
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE in = {}; in.RGB_Range = 0;
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE outCs = {}; outCs.YCbCr_Matrix = 1; outCs.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
    vctx_->VideoProcessorSetStreamColorSpace(vproc_, 0, &in);
    vctx_->VideoProcessorSetOutputColorSpace(vproc_, &outCs);
    vctx_->VideoProcessorSetStreamAutoProcessingMode(vproc_, 0, FALSE);
    for (int i = 0; i < kSlots; i++) {
        D3D11_TEXTURE2D_DESC d = {};
        d.Width = width_; d.Height = height_; d.MipLevels = 1; d.ArraySize = 1; d.Format = DXGI_FORMAT_NV12;
        d.SampleDesc.Count = 1; d.Usage = D3D11_USAGE_DEFAULT; d.BindFlags = D3D11_BIND_RENDER_TARGET;
        hr = device_->CreateTexture2D(&d, nullptr, &nv12_[i]);
        if (FAILED(hr)) { error = "textura NV12 " + Hex(hr); return false; }
        D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC od = {}; od.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
        hr = vdev_->CreateVideoProcessorOutputView(nv12_[i], venum_, &od, &outView_[i]);
        if (FAILED(hr)) { error = "saída NV12 " + Hex(hr); return false; }
    }
    return true;
}

bool VideoEncoder::InitMft(bool hardware, std::string& error) {
    MFT_REGISTER_TYPE_INFO inInfo = {MFMediaType_Video, MFVideoFormat_NV12};
    MFT_REGISTER_TYPE_INFO outInfo = {MFMediaType_Video, hevc_ ? MFVideoFormat_HEVC : MFVideoFormat_H264};
    IMFActivate** acts = nullptr; UINT32 count = 0;
    UINT32 flags = (hardware ? MFT_ENUM_FLAG_HARDWARE : MFT_ENUM_FLAG_SYNCMFT) | MFT_ENUM_FLAG_SORTANDFILTER;
    HRESULT hr = MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, flags, &inInfo, &outInfo, &acts, &count);
    if (FAILED(hr) || count == 0) { error = "não encontrado"; return false; }
    for (UINT32 i = 0; i < count && !mft_; i++) {
        IMFTransform* t = nullptr;
        if (FAILED(acts[i]->ActivateObject(IID_PPV_ARGS(&t)))) continue;
        wchar_t* fname = nullptr; UINT32 len = 0;
        if (SUCCEEDED(acts[i]->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &fname, &len))) { name_ = Narrow(fname); CoTaskMemFree(fname); }
        IMFAttributes* attrs = nullptr;
        if (SUCCEEDED(t->GetAttributes(&attrs)) && attrs) {
            if (hardware) attrs->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE);
            attrs->SetUINT32(MF_LOW_LATENCY, TRUE);
            attrs->Release();
        }
        if (hardware) {
            if (!manager_) {
                MFCreateDXGIDeviceManager(&resetToken_, &manager_);
                if (manager_) manager_->ResetDevice(device_, resetToken_);
            }
            if (FAILED(t->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, reinterpret_cast<ULONG_PTR>(manager_)))) { t->Release(); continue; }
        }
        IMFMediaType* ot = nullptr; MFCreateMediaType(&ot);
        ot->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        ot->SetGUID(MF_MT_SUBTYPE, hevc_ ? MFVideoFormat_HEVC : MFVideoFormat_H264);
        ot->SetUINT32(MF_MT_AVG_BITRATE, 20000000);
        MFSetAttributeSize(ot, MF_MT_FRAME_SIZE, width_, height_);
        MFSetAttributeRatio(ot, MF_MT_FRAME_RATE, fps_, 1);
        MFSetAttributeRatio(ot, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        ot->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        ot->SetUINT32(MF_MT_MPEG2_PROFILE, hevc_ ? UINT32(eAVEncH265VProfile_Main_420_8) : UINT32(eAVEncH264VProfile_Main));
        hr = t->SetOutputType(0, ot, 0);
        ot->Release();
        if (FAILED(hr)) { error = "tipo de saída " + Hex(hr); t->Release(); continue; }
        IMFMediaType* it = nullptr; MFCreateMediaType(&it);
        it->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        it->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
        MFSetAttributeSize(it, MF_MT_FRAME_SIZE, width_, height_);
        MFSetAttributeRatio(it, MF_MT_FRAME_RATE, fps_, 1);
        it->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        hr = t->SetInputType(0, it, 0);
        it->Release();
        if (FAILED(hr)) { error = "tipo de entrada " + Hex(hr); t->Release(); continue; }
        mft_ = t;
    }
    for (UINT32 i = 0; i < count; i++) acts[i]->Release();
    CoTaskMemFree(acts);
    if (!mft_) return false;
    hardware_ = hardware;
    mft_->QueryInterface(kIID_ICodecAPI, reinterpret_cast<void**>(&codecApi_));
    if (hardware) mft_->QueryInterface(IID_PPV_ARGS(&events_));
    MFT_OUTPUT_STREAM_INFO si = {};
    mft_->GetOutputStreamInfo(0, &si);
    providesSamples_ = (si.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
    outputSize_ = si.cbSize ? si.cbSize : DWORD(width_ * height_ * 3 / 2);
    if (!hardware) {
        D3D11_TEXTURE2D_DESC d = {};
        d.Width = width_; d.Height = height_; d.MipLevels = 1; d.ArraySize = 1; d.Format = DXGI_FORMAT_NV12;
        d.SampleDesc.Count = 1; d.Usage = D3D11_USAGE_STAGING; d.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        if (FAILED(device_->CreateTexture2D(&d, nullptr, &staging_))) { error = "leitura NV12"; return false; }
    }
    return true;
}

void VideoEncoder::SetBitrate(int kbps) {
    SetU32(codecApi_, CODECAPI_AVEncCommonMeanBitRate, ULONG(kbps) * 1000);
}

void VideoEncoder::Submit(ID3D11Texture2D* rgba, uint64_t tag) {
    if (!running_) return;
    if (rgba != lastInput_) {
        SAFE_RELEASE(inView_);
        D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC id = {}; id.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
        if (FAILED(vdev_->CreateVideoProcessorInputView(rgba, venum_, &id, &inView_))) return;
        lastInput_ = rgba;
    }
    int slot = nextSlot_; nextSlot_ = (nextSlot_ + 1) % kSlots;
    D3D11_VIDEO_PROCESSOR_STREAM st = {}; st.Enable = TRUE; st.pInputSurface = inView_;
    if (FAILED(vctx_->VideoProcessorBlt(vproc_, outView_[slot], 0, 1, &st))) return;
    {
        std::lock_guard<std::mutex> l(mutex_);
        // the encoder takes the newest frame: an old one waiting would only add latency
        while (pending_.size() >= 2) pending_.pop_front();
        pending_.push_back({slot, tag});
    }
    cv_.notify_one();
}

bool VideoEncoder::FeedInput(int slot, uint64_t tag) {
    IMFSample* sample = nullptr;
    IMFMediaBuffer* buffer = nullptr;
    HRESULT hr;
    if (hardware_) {
        hr = MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), nv12_[slot], 0, FALSE, &buffer);
        if (FAILED(hr)) return false;
        IMF2DBuffer* b2 = nullptr; DWORD len = 0;
        if (SUCCEEDED(buffer->QueryInterface(IID_PPV_ARGS(&b2)))) { b2->GetContiguousLength(&len); b2->Release(); }
        buffer->SetCurrentLength(len);
    } else {
        // software encoder: read the NV12 image back into system memory
        context_->CopyResource(staging_, nv12_[slot]);
        D3D11_MAPPED_SUBRESOURCE m = {};
        if (FAILED(context_->Map(staging_, 0, D3D11_MAP_READ, 0, &m))) return false;
        DWORD size = DWORD(width_ * height_ * 3 / 2);
        MFCreateMemoryBuffer(size, &buffer);
        BYTE* dst = nullptr;
        buffer->Lock(&dst, nullptr, nullptr);
        const BYTE* src = static_cast<const BYTE*>(m.pData);
        for (int y = 0; y < height_; y++) memcpy(dst + y * width_, src + y * m.RowPitch, width_);
        const BYTE* uv = src + m.RowPitch * height_;
        for (int y = 0; y < height_ / 2; y++) memcpy(dst + width_ * height_ + y * width_, uv + y * m.RowPitch, width_);
        buffer->Unlock();
        buffer->SetCurrentLength(size);
        context_->Unmap(staging_, 0);
    }
    MFCreateSample(&sample);
    sample->AddBuffer(buffer);
    sample->SetSampleTime(sampleTime_);
    sample->SetSampleDuration(frameDuration_);
    tags_[sampleTime_] = tag;
    while (tags_.size() > 32) tags_.erase(tags_.begin());
    sampleTime_ += frameDuration_;
    if (keyRequested_.exchange(false)) SetU32(codecApi_, CODECAPI_AVEncVideoForceKeyFrame, 1);
    hr = mft_->ProcessInput(0, sample, 0);
    sample->Release(); buffer->Release();
    return SUCCEEDED(hr);
}

void VideoEncoder::Emit(IMFSample* s) {
    LONGLONG t = 0; s->GetSampleTime(&t);
    UINT32 clean = 0; s->GetUINT32(MFSampleExtension_CleanPoint, &clean);
    uint64_t tag = 0;
    auto it = tags_.find(t);
    if (it != tags_.end()) { tag = it->second; tags_.erase(it); }
    IMFMediaBuffer* b = nullptr;
    if (FAILED(s->ConvertToContiguousBuffer(&b))) return;
    BYTE* data = nullptr; DWORD len = 0;
    if (SUCCEEDED(b->Lock(&data, nullptr, &len))) { out_(data, len, clean != 0, tag); b->Unlock(); }
    b->Release();
}

void VideoEncoder::DrainOutput() {
    while (running_) {
        MFT_OUTPUT_DATA_BUFFER ob = {};
        IMFSample* own = nullptr;
        if (!providesSamples_) {
            IMFMediaBuffer* mb = nullptr;
            MFCreateMemoryBuffer(outputSize_, &mb);
            MFCreateSample(&own); own->AddBuffer(mb); mb->Release();
            ob.pSample = own;
        }
        DWORD status = 0;
        HRESULT hr = mft_->ProcessOutput(0, 1, &ob, &status);
        if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
            IMFMediaType* t = nullptr;
            if (SUCCEEDED(mft_->GetOutputAvailableType(0, 0, &t))) { mft_->SetOutputType(0, t, 0); t->Release(); }
            if (own) own->Release();
            continue;
        }
        if (SUCCEEDED(hr) && ob.pSample) Emit(ob.pSample);
        if (ob.pEvents) ob.pEvents->Release();
        if (ob.pSample && ob.pSample != own) ob.pSample->Release();
        if (own) own->Release();
        // the async encoder gives one output per event; the sync one until it needs more input
        if (FAILED(hr) || hardware_) return;
    }
}

void VideoEncoder::AsyncLoop() {
    while (running_) {
        IMFMediaEvent* ev = nullptr;
        if (FAILED(events_->GetEvent(0, &ev))) break;
        MediaEventType type = MEUnknown;
        ev->GetType(&type);
        ev->Release();
        if (type == METransformNeedInput) {
            Pending p{};
            {
                std::unique_lock<std::mutex> l(mutex_);
                cv_.wait(l, [this] { return !pending_.empty() || !running_; });
                if (!running_) break;
                p = pending_.front(); pending_.pop_front();
            }
            FeedInput(p.slot, p.tag);
        } else if (type == METransformHaveOutput) {
            DrainOutput();
        }
    }
}

void VideoEncoder::SyncLoop() {
    while (running_) {
        Pending p{};
        {
            std::unique_lock<std::mutex> l(mutex_);
            cv_.wait(l, [this] { return !pending_.empty() || !running_; });
            if (!running_) break;
            p = pending_.front(); pending_.pop_front();
        }
        if (FeedInput(p.slot, p.tag)) DrainOutput();
    }
}

void VideoEncoder::Shutdown() {
    if (running_.exchange(false)) {
        cv_.notify_all();
        if (mft_) mft_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
        if (events_) {
            // unblock GetEvent: the async encoder posts an event once it is told to drain
            mft_->ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
        }
        if (thread_.joinable()) thread_.join();
    }
    SAFE_RELEASE(events_); SAFE_RELEASE(codecApi_); SAFE_RELEASE(mft_); SAFE_RELEASE(manager_);
    SAFE_RELEASE(inView_);
    for (int i = 0; i < kSlots; i++) { SAFE_RELEASE(outView_[i]); SAFE_RELEASE(nv12_[i]); }
    SAFE_RELEASE(staging_); SAFE_RELEASE(vproc_); SAFE_RELEASE(venum_); SAFE_RELEASE(vctx_); SAFE_RELEASE(vdev_);
    SAFE_RELEASE(context_); SAFE_RELEASE(device_);
    MFShutdown();
}
