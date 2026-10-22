// How hard the PC works for SteamVR: render resolution (relative to the phone's stream) and frame
// rate. Integrated graphics (Intel UHD/Iris, small AMD APUs) get a lighter preset; the phone keeps
// head turns smooth at its own refresh rate by reprojecting the last frame, so the PC can render
// at 45 or 30 fps.
#pragma once
#include <windows.h>
#include <dxgi.h>
#include <string>

namespace hxvr {

enum Preset { PRESET_AUTO = 0, PRESET_WEAK = 1, PRESET_WEAKEST = 2, PRESET_MEDIUM = 3, PRESET_STRONG = 4 };

struct PresetParams {
    float scale;  // render/stream size relative to what the phone asked for
    int fps;      // 0 = the phone's refresh rate
};

inline PresetParams Params(int preset) {
    switch (preset) {
        case PRESET_WEAK: return {0.6f, 45};
        case PRESET_WEAKEST: return {0.5f, 30};
        case PRESET_MEDIUM: return {0.8f, 60};
        default: return {1.0f, 0};
    }
}

inline const wchar_t* PresetLabel(int preset) {
    switch (preset) {
        case PRESET_WEAK: return L"PC fraco (vídeo integrado): 60% da resolução, 45 fps";
        case PRESET_WEAKEST: return L"PC muito fraco: 50% da resolução, 30 fps";
        case PRESET_MEDIUM: return L"PC médio: 80% da resolução, 60 fps";
        case PRESET_STRONG: return L"PC forte: resolução e taxa do celular";
        default: return L"Automático (pela placa de vídeo)";
    }
}

/** The primary graphics adapter (the one SteamVR and the driver use) and whether it is integrated. */
inline std::wstring PrimaryGpu(bool* integrated) {
    *integrated = false;
    IDXGIFactory1* f = nullptr;
    if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), reinterpret_cast<void**>(&f)))) return L"?";
    IDXGIAdapter1* a = nullptr;
    std::wstring name = L"?";
    if (SUCCEEDED(f->EnumAdapters1(0, &a))) {
        DXGI_ADAPTER_DESC1 d;
        if (SUCCEEDED(a->GetDesc1(&d))) {
            name = d.Description;
            // Intel GPUs before Arc and AMD APUs have little or no dedicated memory
            bool intel = d.VendorId == 0x8086 && d.DedicatedVideoMemory < (1ull << 30);
            bool apu = d.VendorId == 0x1002 && d.DedicatedVideoMemory < (1ull << 30);
            *integrated = intel || apu;
        }
        a->Release();
    }
    f->Release();
    return name;
}

inline int ResolvePreset(int preset) {
    if (preset != PRESET_AUTO) return preset;
    bool integrated = false;
    PrimaryGpu(&integrated);
    return integrated ? PRESET_WEAK : PRESET_STRONG;
}

}  // namespace hxvr
