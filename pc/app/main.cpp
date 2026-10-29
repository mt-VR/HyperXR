// HyperXR PC: type the phone's IP, press the button, play. Registers the HyperXR driver with
// SteamVR, saves the settings the driver reads and starts SteamVR; then shows the driver status.
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <tlhelp32.h>

#include <atomic>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "../common/config.h"
#include "../common/discovery.h"
#include "../common/perf.h"

namespace {

enum { ID_PHONE_IP = 101, ID_BITRATE, ID_START, ID_REMOVE, ID_TIMER, ID_PRESET, ID_FIREWALL };
HWND g_phoneIp, g_bitrate, g_status, g_pcIp, g_preset, g_found;
HFONT g_font, g_bold;

// the phone the discovery thread found (pc/common/discovery.h)
std::mutex g_foundMutex;
std::string g_foundIp, g_foundName;
std::atomic<bool> g_running{true};

std::wstring Widen(const std::string& s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, nullptr, 0);
    std::wstring w(n > 0 ? n - 1 : 0, L'\0');
    if (n > 1) MultiByteToWideChar(CP_UTF8, 0, s.c_str(), -1, &w[0], n);
    return w;
}

std::string Narrow(const std::wstring& w) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, nullptr, 0, nullptr, nullptr);
    std::string s(n > 0 ? n - 1 : 0, '\0');
    if (n > 1) WideCharToMultiByte(CP_UTF8, 0, w.c_str(), -1, &s[0], n, nullptr, nullptr);
    return s;
}

std::wstring GetText(HWND h) {
    wchar_t buf[256]; GetWindowTextW(h, buf, 256);
    std::wstring s = buf;
    while (!s.empty() && s.back() == L' ') s.pop_back();
    while (!s.empty() && s.front() == L' ') s.erase(s.begin());
    return s;
}

/** IPv4 addresses of this PC's active network adapters (to type into the phone). */
std::wstring LocalIps() {
    ULONG size = 16 * 1024;
    std::vector<unsigned char> buf(size);
    auto* list = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr, list, &size) != NO_ERROR) return L"?";
    std::wstring out;
    for (auto* a = list; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
        for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
            char ip[64];
            auto* sin = reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr);
            inet_ntop(AF_INET, &sin->sin_addr, ip, sizeof ip);
            if (!strncmp(ip, "169.254.", 8)) continue;
            if (!out.empty()) out += L"   ";
            out += Widen(ip);
        }
    }
    return out.empty() ? L"sem rede" : out;
}

std::wstring ExeDir() {
    wchar_t path[MAX_PATH]; GetModuleFileNameW(nullptr, path, MAX_PATH);
    std::wstring s = path;
    return s.substr(0, s.find_last_of(L"\\/"));
}

std::wstring ReadFileW(const std::wstring& path) {
    FILE* f = _wfopen(path.c_str(), L"rb");
    if (!f) return L"";
    std::string s; char b[4096]; size_t n;
    while ((n = fread(b, 1, sizeof b, f)) > 0) s.append(b, n);
    fclose(f);
    return Widen(s);
}

std::wstring OpenVrPathsFile() {
    wchar_t local[MAX_PATH];
    if (!GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH)) return L"";
    return std::wstring(local) + L"\\openvr\\openvrpaths.vrpath";
}

/** SteamVR's install folder, from openvrpaths.vrpath ("runtime": ["C:\\...\\SteamVR"]). */
std::wstring SteamVrDir() {
    std::wstring j = ReadFileW(OpenVrPathsFile());
    size_t k = j.find(L"\"runtime\"");
    if (k == std::wstring::npos) return L"";
    size_t a = j.find(L'"', j.find(L'[', k) + 1);
    if (a == std::wstring::npos) return L"";
    std::wstring out;
    for (size_t i = a + 1; i < j.size() && j[i] != L'"'; i++) {
        if (j[i] == L'\\' && i + 1 < j.size()) { out += j[++i]; } else out += j[i];
    }
    return out;
}

DWORD Run(const std::wstring& exe, const std::wstring& args) {
    std::wstring cmd = L"\"" + exe + L"\" " + args;
    STARTUPINFOW si = {sizeof si}; si.dwFlags = STARTF_USESHOWWINDOW; si.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION pi = {};
    if (!CreateProcessW(nullptr, &cmd[0], nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) return DWORD(-1);
    WaitForSingleObject(pi.hProcess, 15000);
    DWORD code = 1; GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess); CloseHandle(pi.hThread);
    return code;
}

/** Registers (or removes) the driver folder next to this program with SteamVR's own tool. */
bool RegisterDriver(bool add, std::wstring& message) {
    std::wstring vr = SteamVrDir();
    if (vr.empty()) { message = L"SteamVR não encontrado. Instale o Steam e o SteamVR e abra o SteamVR uma vez."; return false; }
    std::wstring tool = vr + L"\\bin\\win64\\vrpathreg.exe";
    if (GetFileAttributesW(tool.c_str()) == INVALID_FILE_ATTRIBUTES) { message = L"vrpathreg.exe não encontrado em " + vr; return false; }
    std::wstring driver = ExeDir() + L"\\hyperxr";
    if (add && GetFileAttributesW((driver + L"\\driver.vrdrivermanifest").c_str()) == INVALID_FILE_ATTRIBUTES) {
        message = L"A pasta 'hyperxr' precisa ficar ao lado deste programa (extraia o zip inteiro)."; return false;
    }
    Run(tool, std::wstring(add ? L"adddriver" : L"removedriver") + L" \"" + driver + L"\"");
    std::wstring after = ReadFileW(OpenVrPathsFile());
    // vrpathreg writes the path with escaped backslashes
    std::wstring escaped;
    for (wchar_t c : driver) { escaped += c; if (c == L'\\') escaped += L'\\'; }
    bool present = after.find(escaped) != std::wstring::npos || after.find(driver) != std::wstring::npos;
    if (add && !present) { message = L"O SteamVR não aceitou o driver (feche o SteamVR e tente de novo)."; return false; }
    return true;
}

/** SteamVR's server process, where the driver runs. */
bool IsSteamVrRunning() {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return FindWindowW(nullptr, L"SteamVR Status") != nullptr;
    PROCESSENTRY32W e = {sizeof e};
    bool found = false;
    for (BOOL ok = Process32FirstW(snap, &e); ok && !found; ok = Process32NextW(snap, &e)) found = !_wcsicmp(e.szExeFile, L"vrserver.exe");
    CloseHandle(snap);
    return found;
}

void SetStatus(const std::wstring& s) { SetWindowTextW(g_status, s.c_str()); }

void Start() {
    hxvr::Config cfg; cfg.Load();
    std::wstring ip = GetText(g_phoneIp);
    in_addr a;
    // empty is fine: the driver finds the phone on the network by itself
    if (!ip.empty() && inet_pton(AF_INET, Narrow(ip).c_str(), &a) != 1) { SetStatus(L"O IP do celular não é válido (exemplo: 192.168.0.15). Deixe vazio para achar sozinho."); return; }
    cfg.phoneIp = Narrow(ip);
    int mbps = _wtoi(GetText(g_bitrate).c_str());
    cfg.bitrateMbps = mbps >= 5 && mbps <= 200 ? mbps : 40;
    int sel = int(SendMessageW(g_preset, CB_GETCURSEL, 0, 0));
    cfg.preset = sel >= 0 && sel <= hxvr::PRESET_STRONG ? sel : 0;
    cfg.Save();
    std::wstring msg;
    if (!RegisterDriver(true, msg)) { SetStatus(msg); return; }
    if (IsSteamVrRunning()) {
        SetStatus(L"Configurado. O SteamVR já está aberto: feche e abra de novo para usar o IP e o desempenho novos.");
        return;
    }
    ShellExecuteW(nullptr, L"open", L"steam://rungameid/250820", nullptr, nullptr, SW_SHOWNORMAL);
    SetStatus(L"Iniciando o SteamVR... No celular: abra HyperMagic > SteamVR, no mesmo Wi-Fi. Os dois se acham sozinhos.");
}

/**
 * Allows SteamVR's server (and this program) through the Windows firewall, for networks marked as
 * public, where Windows blocks incoming connections without asking. One UAC prompt.
 */
void AllowFirewall() {
    std::wstring vr = SteamVrDir();
    std::wstring server = vr.empty() ? L"" : vr + L"\\bin\\win64\\vrserver.exe";
    wchar_t self[MAX_PATH]; GetModuleFileNameW(nullptr, self, MAX_PATH);
    std::wstring cmd = L"/c netsh advfirewall firewall delete rule name=\"HyperMagic SteamVR\" & "
                       L"netsh advfirewall firewall add rule name=\"HyperMagic SteamVR\" dir=in action=allow protocol=TCP localport=9944 profile=any & "
                       L"netsh advfirewall firewall add rule name=\"HyperMagic SteamVR\" dir=in action=allow protocol=UDP localport=9945 profile=any & "
                       L"netsh advfirewall firewall add rule name=\"HyperMagic SteamVR\" dir=in action=allow program=\"" + std::wstring(self) + L"\" profile=any";
    if (!server.empty()) cmd += L" & netsh advfirewall firewall add rule name=\"HyperMagic SteamVR\" dir=in action=allow program=\"" + server + L"\" profile=any";
    SHELLEXECUTEINFOW sei = {sizeof sei};
    sei.fMask = SEE_MASK_NOCLOSEPROCESS; sei.lpVerb = L"runas"; sei.lpFile = L"cmd.exe"; sei.lpParameters = cmd.c_str(); sei.nShow = SW_HIDE;
    if (!ShellExecuteExW(&sei)) { SetStatus(L"O Windows não deixou mudar o firewall (é preciso aceitar o pedido de administrador)."); return; }
    WaitForSingleObject(sei.hProcess, 20000);
    DWORD code = 1; GetExitCodeProcess(sei.hProcess, &code); CloseHandle(sei.hProcess);
    SetStatus(code == 0 ? L"Firewall liberado para o SteamVR (portas 9944 e 9945)." : L"O netsh falhou; libere o vrserver.exe no Firewall do Windows manualmente.");
}

/** Beacons for the phone once a second; its answer fills the phone's IP (and pc.ini, for the driver). */
void DiscoveryThread() {
    hxvr::Discovery d;
    while (g_running) {
        std::string ip, name;
        if (d.Poll(400, ip, name)) { std::lock_guard<std::mutex> l(g_foundMutex); g_foundIp = ip; g_foundName = name; }
        for (int i = 0; i < 6 && g_running; i++) Sleep(100);
    }
}

/**
 * Closes SteamVR and opens it again. The driver asks for this once, when the phone's screen needs
 * a different render size; SteamVR's own restart waits for a click on its window.
 */
void RestartSteamVr() {
    static const wchar_t* kProcs[] = {L"vrcompositor.exe", L"vrdashboard.exe", L"vrwebhelper.exe", L"vrmonitor.exe", L"vrserver.exe", L"vrstartup.exe"};
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap != INVALID_HANDLE_VALUE) {
        PROCESSENTRY32W e = {sizeof e};
        for (BOOL ok = Process32FirstW(snap, &e); ok; ok = Process32NextW(snap, &e)) {
            for (auto* name : kProcs) {
                if (_wcsicmp(e.szExeFile, name)) continue;
                if (HANDLE h = OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE, e.th32ProcessID)) {
                    TerminateProcess(h, 0); WaitForSingleObject(h, 3000); CloseHandle(h);
                }
            }
        }
        CloseHandle(snap);
    }
    Sleep(1500);
    ShellExecuteW(nullptr, L"open", L"steam://rungameid/250820", nullptr, nullptr, SW_SHOWNORMAL);
}

/** Seconds since the driver last wrote status.txt (it writes about once a second), or -1. */
double StatusAge() {
    WIN32_FILE_ATTRIBUTE_DATA d;
    if (!GetFileAttributesExW(hxvr::StatusPath().c_str(), GetFileExInfoStandard, &d)) return -1;
    FILETIME now; GetSystemTimeAsFileTime(&now);
    ULARGE_INTEGER a, b;
    a.LowPart = d.ftLastWriteTime.dwLowDateTime; a.HighPart = d.ftLastWriteTime.dwHighDateTime;
    b.LowPart = now.dwLowDateTime; b.HighPart = now.dwHighDateTime;
    return b.QuadPart > a.QuadPart ? double(b.QuadPart - a.QuadPart) / 1e7 : 0;
}

void Remove() {
    std::wstring msg;
    if (RegisterDriver(false, msg)) SetStatus(L"Driver removido do SteamVR.");
    else SetStatus(msg);
}

/** Shows what the driver reports (status.txt), when SteamVR is running. */
void RefreshStatus() {
    SetWindowTextW(g_pcIp, (L"IP deste PC:  " + LocalIps()).c_str());
    std::string foundIp, foundName;
    { std::lock_guard<std::mutex> l(g_foundMutex); foundIp = g_foundIp; foundName = g_foundName; }
    if (!foundIp.empty()) {
        SetWindowTextW(g_found, (L"Celular achado na rede: " + Widen(foundName) + L"  (" + Widen(foundIp) + L")").c_str());
        // the driver reads pc.ini once a second while it waits for the phone
        if (Narrow(GetText(g_phoneIp)) != foundIp) {
            SetWindowTextW(g_phoneIp, Widen(foundIp).c_str());
            hxvr::Config cfg; cfg.Load();
            if (cfg.phoneIp != foundIp) { cfg.phoneIp = foundIp; cfg.Save(); }
        }
    } else {
        SetWindowTextW(g_found, L"Procurando o celular na rede (abra HyperMagic > SteamVR no celular, mesmo Wi-Fi)...");
    }
    if (!IsSteamVrRunning()) {
        SetStatus(foundIp.empty() ? L"SteamVR fechado." : L"SteamVR fechado. Toque em Conectar e iniciar o SteamVR.");
        return;
    }
    double age = StatusAge();
    if (age < 0 || age > 8) {
        SetStatus(L"O SteamVR está aberto, mas o driver HyperMagic não está rodando.\r\n"
                  L"Se o SteamVR acabou de abrir, aguarde. Senão: SteamVR > Configurações > Inicialização/Desligamento > "
                  L"Gerenciar complementos: ative o hyperxr (o SteamVR bloqueia drivers depois de um erro) e reinicie o SteamVR.");
        return;
    }
    std::wstring s = ReadFileW(hxvr::StatusPath());
    if (s.empty()) return;
    auto val = [&](const wchar_t* k) {
        size_t a = s.find(std::wstring(k) + L"=");
        if (a == std::wstring::npos) return std::wstring();
        a += wcslen(k) + 1;
        return s.substr(a, s.find(L'\n', a) - a);
    };
    std::wstring t = L"Celular: " + val(L"phone") + L"\r\nCodificador: " + val(L"encoder") + L"\r\nQuadros: " + val(L"fps") + L" por segundo   Vídeo: " + val(L"mbps") + L" Mb/s (alvo " + val(L"target") + L")";
    static ULONGLONG lastRestart = 0;
    if (val(L"restart") == L"1" && GetTickCount64() - lastRestart > 30000) {
        lastRestart = GetTickCount64();
        SetStatus(L"Reiniciando o SteamVR para a tela do celular (só na primeira vez)...");
        RestartSteamVr();
        return;
    }
    std::wstring err = val(L"error");
    if (!err.empty()) t += L"\r\nProblema: " + err;
    SetStatus(t);
}

HWND Label(HWND p, const wchar_t* text, int x, int y, int w, int h, HFONT f) {
    HWND l = CreateWindowW(L"STATIC", text, WS_CHILD | WS_VISIBLE, x, y, w, h, p, nullptr, nullptr, nullptr);
    SendMessageW(l, WM_SETFONT, (WPARAM)f, TRUE);
    return l;
}

LRESULT CALLBACK Proc(HWND w, UINT m, WPARAM wp, LPARAM lp) {
    switch (m) {
        case WM_CREATE: {
            hxvr::Config cfg; cfg.Load();
            Label(w, L"HyperMagic para SteamVR", 20, 16, 520, 30, g_bold);
            Label(w, L"1. No celular: abra HyperMagic > SteamVR (mesmo Wi-Fi deste PC). 2. Aqui: Conectar e iniciar o SteamVR. Os dois se acham sozinhos.", 20, 52, 520, 40, g_font);
            g_pcIp = Label(w, L"", 20, 94, 520, 20, g_font);
            g_found = Label(w, L"", 20, 112, 520, 20, g_font);
            Label(w, L"IP do celular:", 20, 136, 130, 22, g_font);
            g_phoneIp = CreateWindowW(L"EDIT", Widen(cfg.phoneIp).c_str(), WS_CHILD | WS_VISIBLE | WS_BORDER | ES_AUTOHSCROLL, 160, 132, 200, 26, w, (HMENU)ID_PHONE_IP, nullptr, nullptr);
            SendMessageW(g_phoneIp, WM_SETFONT, (WPARAM)g_font, TRUE);
            Label(w, L"Qualidade (Mb/s):", 20, 172, 130, 22, g_font);
            g_bitrate = CreateWindowW(L"EDIT", std::to_wstring(cfg.bitrateMbps).c_str(), WS_CHILD | WS_VISIBLE | WS_BORDER | ES_NUMBER, 160, 168, 80, 26, w, (HMENU)ID_BITRATE, nullptr, nullptr);
            SendMessageW(g_bitrate, WM_SETFONT, (WPARAM)g_font, TRUE);
            Label(w, L"(Wi-Fi 5 GHz: 40-80; Wi-Fi comum: 20-30)", 250, 172, 290, 22, g_font);
            Label(w, L"Desempenho do PC:", 20, 208, 130, 22, g_font);
            g_preset = CreateWindowW(L"COMBOBOX", L"", WS_CHILD | WS_VISIBLE | CBS_DROPDOWNLIST | WS_VSCROLL, 160, 204, 380, 200, w, (HMENU)ID_PRESET, nullptr, nullptr);
            SendMessageW(g_preset, WM_SETFONT, (WPARAM)g_font, TRUE);
            for (int p = 0; p <= hxvr::PRESET_STRONG; p++) SendMessageW(g_preset, CB_ADDSTRING, 0, (LPARAM)hxvr::PresetLabel(p));
            SendMessageW(g_preset, CB_SETCURSEL, cfg.preset, 0);
            {
                bool integrated = false;
                std::wstring gpu = hxvr::PrimaryGpu(&integrated);
                std::wstring note = L"Placa de vídeo: " + gpu + (integrated ? L" (integrada: o Automático usa o perfil de PC fraco)" : L"");
                Label(w, note.c_str(), 20, 236, 520, 22, g_font);
            }
            HWND go = CreateWindowW(L"BUTTON", L"Conectar e iniciar o SteamVR", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON, 20, 266, 260, 40, w, (HMENU)ID_START, nullptr, nullptr);
            SendMessageW(go, WM_SETFONT, (WPARAM)g_bold, TRUE);
            HWND fw = CreateWindowW(L"BUTTON", L"Liberar no firewall", WS_CHILD | WS_VISIBLE, 290, 266, 150, 40, w, (HMENU)ID_FIREWALL, nullptr, nullptr);
            SendMessageW(fw, WM_SETFONT, (WPARAM)g_font, TRUE);
            HWND rm = CreateWindowW(L"BUTTON", L"Remover", WS_CHILD | WS_VISIBLE, 450, 266, 90, 40, w, (HMENU)ID_REMOVE, nullptr, nullptr);
            SendMessageW(rm, WM_SETFONT, (WPARAM)g_font, TRUE);
            g_status = Label(w, L"", 20, 320, 520, 110, g_font);
            SetTimer(w, ID_TIMER, 1000, nullptr);
            std::thread(DiscoveryThread).detach();
            RefreshStatus();
            return 0;
        }
        case WM_COMMAND:
            if (LOWORD(wp) == ID_START) Start();
            else if (LOWORD(wp) == ID_REMOVE) Remove();
            else if (LOWORD(wp) == ID_FIREWALL) AllowFirewall();
            return 0;
        case WM_TIMER: RefreshStatus(); return 0;
        case WM_CTLCOLORSTATIC: SetBkMode((HDC)wp, TRANSPARENT); return (LRESULT)GetSysColorBrush(COLOR_WINDOW);
        case WM_DESTROY: g_running = false; PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(w, m, wp, lp);
}

}  // namespace

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR, int show) {
    WSADATA wsa; WSAStartup(MAKEWORD(2, 2), &wsa);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    INITCOMMONCONTROLSEX icc = {sizeof icc, ICC_STANDARD_CLASSES}; InitCommonControlsEx(&icc);
    g_font = CreateFontW(-15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    g_bold = CreateFontW(-18, 0, 0, 0, FW_SEMIBOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    WNDCLASSW wc = {};
    wc.lpfnWndProc = Proc; wc.hInstance = inst; wc.lpszClassName = L"HyperMagicPC";
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW); wc.hbrBackground = GetSysColorBrush(COLOR_WINDOW);
    RegisterClassW(&wc);
    HWND w = CreateWindowW(L"HyperMagicPC", L"HyperMagic PC", WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                           CW_USEDEFAULT, CW_USEDEFAULT, 580, 490, nullptr, nullptr, inst, nullptr);
    ShowWindow(w, show);
    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        if (!IsDialogMessageW(w, &msg)) { TranslateMessage(&msg); DispatchMessageW(&msg); }
    }
    return 0;
}
