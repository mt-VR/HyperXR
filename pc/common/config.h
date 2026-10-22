// Settings shared by the HyperXR PC app and the SteamVR driver: %APPDATA%\HyperXR\pc.ini
// (key=value lines). The driver also writes status.txt there for the app to show.
#pragma once
#include <windows.h>
#include <shlobj.h>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <string>

namespace hxvr {

inline std::wstring DataDir() {
    wchar_t* base = nullptr;
    std::wstring dir;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, nullptr, &base))) { dir = base; CoTaskMemFree(base); }
    dir += L"\\HyperXR";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir;
}

struct Config {
    std::string phoneIp;
    int bitrateMbps = 40;
    int preset = 0;  // perf.h: 0 automatic, 1 weak, 2 weakest, 3 medium, 4 strong
    // display parameters, filled from the phone's first hello (SteamVR restarts once to apply them)
    int eyeWidth = 1280, eyeHeight = 1280, refreshHz = 72;
    float tanLeft = 1.0f, tanRight = 1.0f, tanUp = 1.0f, tanDown = 1.0f, ipd = 0.063f;

    static std::wstring Path() { return DataDir() + L"\\pc.ini"; }

    void Load() {
        FILE* f = _wfopen(Path().c_str(), L"r");
        if (!f) return;
        std::map<std::string, std::string> kv;
        char line[512];
        while (fgets(line, sizeof line, f)) {
            std::string s(line);
            while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ')) s.pop_back();
            auto eq = s.find('=');
            if (eq != std::string::npos) kv[s.substr(0, eq)] = s.substr(eq + 1);
        }
        fclose(f);
        auto i = [&](const char* k, int& v) { if (kv.count(k)) v = atoi(kv[k].c_str()); };
        auto fl = [&](const char* k, float& v) { if (kv.count(k)) v = float(atof(kv[k].c_str())); };
        if (kv.count("phone_ip")) phoneIp = kv["phone_ip"];
        i("bitrate_mbps", bitrateMbps); i("preset", preset); i("eye_width", eyeWidth); i("eye_height", eyeHeight); i("refresh_hz", refreshHz);
        fl("tan_left", tanLeft); fl("tan_right", tanRight); fl("tan_up", tanUp); fl("tan_down", tanDown); fl("ipd", ipd);
    }

    void Save() const {
        FILE* f = _wfopen(Path().c_str(), L"w");
        if (!f) return;
        fprintf(f, "preset=%d\n", preset);
        fprintf(f, "phone_ip=%s\nbitrate_mbps=%d\neye_width=%d\neye_height=%d\nrefresh_hz=%d\n", phoneIp.c_str(), bitrateMbps, eyeWidth, eyeHeight, refreshHz);
        fprintf(f, "tan_left=%.5f\ntan_right=%.5f\ntan_up=%.5f\ntan_down=%.5f\nipd=%.5f\n", tanLeft, tanRight, tanUp, tanDown, ipd);
        fclose(f);
    }
};

inline std::wstring StatusPath() { return DataDir() + L"\\status.txt"; }

}  // namespace hxvr
