// Finding the phone on the local network without typing its IP. The PC broadcasts "HXVR-PC 1" to
// UDP 9945 on every adapter; the HyperMagic phone app answers the sender with "HXVR-PHONE <name>".
// Only an outgoing broadcast and its unicast answer are involved, which the Windows firewall lets
// through without any rule (it allows unicast responses to broadcasts).
#pragma once
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>

#include <cstring>
#include <string>
#include <vector>

namespace hxvr {

constexpr uint16_t kDiscoveryPort = 9945;

/** Broadcast addresses (network order) of the active IPv4 adapters, plus 255.255.255.255. */
inline std::vector<uint32_t> BroadcastAddresses() {
    std::vector<uint32_t> out = {INADDR_BROADCAST};
    ULONG size = 16 * 1024;
    std::vector<unsigned char> buf(size);
    auto* list = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr, list, &size) != NO_ERROR) return out;
    for (auto* a = list; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
        for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
            uint32_t ip = ntohl(reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr)->sin_addr.s_addr);
            int prefix = u->OnLinkPrefixLength;
            if (prefix <= 0 || prefix >= 31 || (ip >> 16) == 0xA9FE) continue;  // also skips 169.254.x.x
            uint32_t mask = 0xFFFFFFFFu << (32 - prefix);
            out.push_back(htonl(ip | ~mask));
        }
    }
    return out;
}

class Discovery {
public:
    ~Discovery() { if (s_ != INVALID_SOCKET) closesocket(s_); }

    /** Sends one beacon and waits up to waitMs for a phone's answer. */
    bool Poll(int waitMs, std::string& phoneIp, std::string& phoneName) {
        if (s_ == INVALID_SOCKET) {
            s_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
            if (s_ == INVALID_SOCKET) return false;
            BOOL one = TRUE; setsockopt(s_, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&one), sizeof one);
            sockaddr_in any = {}; any.sin_family = AF_INET;
            bind(s_, reinterpret_cast<sockaddr*>(&any), sizeof any);
        }
        static const char kBeacon[] = "HXVR-PC 1";
        for (uint32_t b : BroadcastAddresses()) {
            sockaddr_in to = {}; to.sin_family = AF_INET; to.sin_port = htons(kDiscoveryPort); to.sin_addr.s_addr = b;
            sendto(s_, kBeacon, int(sizeof kBeacon - 1), 0, reinterpret_cast<sockaddr*>(&to), sizeof to);
        }
        bool found = false;
        for (;;) {
            fd_set rd; FD_ZERO(&rd); FD_SET(s_, &rd);
            timeval tv = {waitMs / 1000, (waitMs % 1000) * 1000};
            if (select(0, &rd, nullptr, nullptr, &tv) != 1) break;
            char msg[256]; sockaddr_in from = {}; int len = sizeof from;
            int n = recvfrom(s_, msg, sizeof msg - 1, 0, reinterpret_cast<sockaddr*>(&from), &len);
            if (n <= 0) break;
            msg[n] = 0;
            if (strncmp(msg, "HXVR-PHONE", 10) != 0) continue;
            char ip[64]; inet_ntop(AF_INET, &from.sin_addr, ip, sizeof ip);
            phoneIp = ip;
            phoneName = n > 11 ? std::string(msg + 11) : std::string();
            found = true;
            waitMs = 0;  // drain the other answers already queued, but do not wait for more
        }
        return found;
    }

private:
    SOCKET s_ = INVALID_SOCKET;
};

}  // namespace hxvr
