"""HybridMagic wire format (the phone side is app/.../hybrid/HybridLink.kt; keep them in step).

Finding each other, without typing an IP and without a firewall rule on the PC:
  PC  -> UDP broadcast to port 9955: b"HYBRIDMAGIC-PC 1"
  phone -> unicast reply to the sender: b"HYBRIDMAGIC-PHONE 1 <name>"
  PC  -> TCP connection to the phone, port 9956 (outgoing, which Windows allows)

Messages on the TCP connection: u32 length (of what follows), u8 type, payload. Little-endian.
  HELLO (phone -> PC): u16 version, utf-8 phone name
  INFO  (PC -> phone): u16 version, utf-8 description of the PC's engine ("GPU: NVIDIA ...")
  FRAME (phone -> PC): u32 seq, u16 width, u16 height, JPEG bytes
  HANDS (PC -> phone): u32 seq, f32 milliseconds on the PC, u8 count,
                       per hand: f32 presence, 21 x (f32 x, f32 y, f32 confidence) in frame pixels
"""
import struct

VERSION = 1
DISCOVERY_PORT = 9955
TCP_PORT = 9956
BEACON = b"HYBRIDMAGIC-PC 1"
REPLY = b"HYBRIDMAGIC-PHONE"

HELLO, INFO, FRAME, HANDS = 1, 2, 3, 4
JOINTS = 21


def message(kind: int, payload: bytes) -> bytes:
    return struct.pack("<IB", len(payload) + 1, kind) + payload


def info(text: str) -> bytes:
    return message(INFO, struct.pack("<H", VERSION) + text.encode("utf-8"))


def hands(seq: int, ms: float, found) -> bytes:
    out = [struct.pack("<IfB", seq, ms, len(found))]
    for h in found:
        out.append(struct.pack("<f", h.presence))
        for j in range(JOINTS):
            out.append(struct.pack("<fff", float(h.joints[j][0]), float(h.joints[j][1]), float(h.conf[j])))
    return message(HANDS, b"".join(out))


def parse_frame(payload: bytes):
    seq, w, h = struct.unpack_from("<IHH", payload, 0)
    return seq, w, h, payload[8:]


def parse_hello(payload: bytes):
    (version,) = struct.unpack_from("<H", payload, 0)
    return version, payload[2:].decode("utf-8", "replace")
