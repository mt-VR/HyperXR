"""HybridMagic PC: the PC's GPU tracks the hands for HyperMagic on the phone.

Open it, keep it open, and start the VR mode on the phone on the same Wi-Fi: the two find each
other by themselves. The phone sends its camera images, this program finds the hands with the
heavier pipeline in engine.py and sends the 21 joints of each hand back. If the PC goes away, the
phone goes back to its own tracker without stopping.
"""
from __future__ import annotations

import os
import socket
import struct
import sys
import threading
import time
import urllib.request
import zipfile

import cv2
import numpy as np

import protocol as P
from engine import HandEngine

MODELS = {
    # OpenMMLab RTMPose ONNX SDK exports (Apache-2.0)
    "hand_detector.onnx": "https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/rtmdet_nano_8xb32-300e_hand-267f9c8f.zip",
    "hand_pose.onnx": "https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/rtmpose-m_simcc-hand5_pt-aic-coco_210e-256x256-74fb594_20230320.zip",
}


def base_dir() -> str:
    # next to the .exe when frozen by PyInstaller, next to this file otherwise
    return os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))


def models_dir() -> str:
    for d in (os.path.join(getattr(sys, "_MEIPASS", ""), "models"), os.path.join(base_dir(), "models")):
        if all(os.path.isfile(os.path.join(d, n)) for n in MODELS):
            return d
    return os.path.join(base_dir(), "models")


def fetch_models(log=print) -> str:
    """The two models, downloaded once (the release .exe already carries them)."""
    d = models_dir()
    os.makedirs(d, exist_ok=True)
    for name, url in MODELS.items():
        dst = os.path.join(d, name)
        if os.path.isfile(dst):
            continue
        log(f"Baixando {name}...")
        tmp = dst + ".zip"
        urllib.request.urlretrieve(url, tmp)
        with zipfile.ZipFile(tmp) as z:
            member = next(m for m in z.namelist() if m.endswith("end2end.onnx"))
            with z.open(member) as src, open(dst, "wb") as out:
                out.write(src.read())
        os.remove(tmp)
    return d


def broadcast_addresses() -> list[str]:
    """255.255.255.255 and the x.y.z.255 of each of this PC's IPv4 addresses (Windows sends the
    first only on one adapter)."""
    out = {"255.255.255.255"}
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                out.add(ip.rsplit(".", 1)[0] + ".255")
    except OSError:
        pass
    return sorted(out)


def local_ips() -> list[str]:
    try:
        return sorted({i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET) if not i[4][0].startswith("127.")})
    except OSError:
        return []


class Server:
    def __init__(self, engine: HandEngine, log=print):
        self.engine = engine
        self.log = log
        self.phone = ""          # name and address of the connected phone
        self.fps = 0.0
        self.ms = 0.0
        self.hands = 0
        self.running = True
        self._conn: socket.socket | None = None

    # ------------------------------------------------------------------ discovery
    def discovery_loop(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.bind(("", 0))
        s.settimeout(0.3)
        while self.running:
            if self._conn is None:
                for b in broadcast_addresses():
                    try:
                        s.sendto(P.BEACON, (b, P.DISCOVERY_PORT))
                    except OSError:
                        pass
                end = time.time() + 1.0
                while time.time() < end and self._conn is None:
                    try:
                        data, addr = s.recvfrom(256)
                    except (socket.timeout, OSError):
                        continue
                    if data.startswith(P.REPLY):
                        name = data.decode("utf-8", "replace").split(" ", 2)[2] if data.count(b" ") >= 2 else "celular"
                        self.connect(addr[0], name)
            else:
                time.sleep(0.5)

    # ------------------------------------------------------------------ connection
    def connect(self, ip: str, name: str):
        try:
            c = socket.create_connection((ip, P.TCP_PORT), timeout=2)
        except OSError as e:
            self.log(f"Achei {name} ({ip}), mas não conectou: {e}")
            return
        c.settimeout(None)
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._conn = c
        self.phone = f"{name} ({ip})"
        self.log(f"Conectado: {self.phone}")
        threading.Thread(target=self.serve, args=(c,), daemon=True).start()

    @staticmethod
    def _read(c: socket.socket, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = c.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("fechada")
            buf += chunk
        return bytes(buf)

    def serve(self, c: socket.socket):
        self.engine.reset()
        frames = 0
        t_stat = time.time()
        try:
            c.sendall(P.info(f"{self.engine.device} no PC"))
            while self.running:
                (length,) = struct.unpack("<I", self._read(c, 4))
                if length == 0 or length > (8 << 20):
                    raise ConnectionError(f"tamanho inválido {length}")
                body = self._read(c, length)
                kind, payload = body[0], body[1:]
                if kind == P.HELLO:
                    version, name = P.parse_hello(payload)
                    self.phone = f"{name} ({c.getpeername()[0]})"
                elif kind == P.FRAME:
                    seq, w, h, jpeg = P.parse_frame(payload)
                    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
                    found = self.engine.process(img) if img is not None else []
                    c.sendall(P.hands(seq, self.engine.last_ms, found))
                    frames += 1
                    self.ms = self.ms * 0.9 + self.engine.last_ms * 0.1
                    self.hands = len(found)
                    now = time.time()
                    if now - t_stat >= 1.0:
                        self.fps = frames / (now - t_stat)
                        frames = 0
                        t_stat = now
        except (OSError, ConnectionError, struct.error) as e:
            self.log(f"Celular desconectado ({e})")
        finally:
            try:
                c.close()
            except OSError:
                pass
            self._conn = None
            self.phone = ""
            self.fps = 0.0

    def start(self):
        threading.Thread(target=self.discovery_loop, daemon=True).start()


def run_window(server: Server, lines: list[str]):
    import tkinter as tk

    root = tk.Tk()
    root.title("HybridMagic PC")
    root.geometry("560x330")
    root.configure(bg="#0f1420")
    tk.Label(root, text="HybridMagic", font=("Segoe UI", 20, "bold"), fg="#5af0ff", bg="#0f1420").pack(anchor="w", padx=20, pady=(16, 0))
    tk.Label(root, text="A placa de vídeo deste PC rastreia as mãos para o HyperMagic no celular.\n"
             "Deixe aberto e entre no modo VR no celular, no mesmo Wi-Fi.",
             font=("Segoe UI", 10), fg="#c8d2e0", bg="#0f1420", justify="left").pack(anchor="w", padx=20)
    status = tk.Label(root, text="", font=("Segoe UI", 12), fg="white", bg="#0f1420", justify="left")
    status.pack(anchor="w", padx=20, pady=12)
    log = tk.Label(root, text="", font=("Consolas", 9), fg="#8a96aa", bg="#0f1420", justify="left")
    log.pack(anchor="w", padx=20)

    def refresh():
        if server.phone:
            s = f"Celular: {server.phone}\nMãos: {server.hands}   {server.fps:.0f} quadros/s   {server.ms:.1f} ms por quadro"
        else:
            s = "Procurando o celular na rede...\nNo celular: abra o HyperMagic e entre no modo VR (HybridMagic ligado nos Ajustes)."
        status.config(text=s + f"\n\nMotor: {server.engine.device}    IP deste PC: {', '.join(local_ips()) or '?'}")
        log.config(text="\n".join(lines[-5:]))
        root.after(500, refresh)

    refresh()
    root.protocol("WM_DELETE_WINDOW", lambda: (setattr(server, "running", False), root.destroy()))
    root.mainloop()


def main():
    lines: list[str] = []

    def log(s: str):
        lines.append(time.strftime("%H:%M:%S ") + s)
        print(lines[-1], flush=True)

    d = fetch_models(log)
    engine = HandEngine(os.path.join(d, "hand_detector.onnx"), os.path.join(d, "hand_pose.onnx"))
    log(f"Motor de mãos pronto: {engine.device}")
    server = Server(engine, log)
    server.start()
    if "--console" in sys.argv:
        while True:
            time.sleep(5)
            log(f"celular: {server.phone or '-'}  {server.fps:.0f} q/s  {server.ms:.1f} ms")
    try:
        run_window(server, lines)
    except Exception as e:  # no display (tkinter missing): keep serving from the console
        log(f"Sem janela ({e}); rodando no console.")
        while server.running:
            time.sleep(5)


if __name__ == "__main__":
    main()
