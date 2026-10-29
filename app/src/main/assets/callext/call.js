// HyperMagic calls: Firefox's WebRTC inside a hidden GeckoView page. The app (Kotlin) does the
// signalling over Supabase and tells this page what to do through native messaging ("hxcall").
// Audio goes peer to peer; the shared screen goes as JPEG frames on a data channel.
const port = browser.runtime.connectNative("hxcall");
let pc = null, mic = null, dc = null, pendingIce = [], remoteSet = false;
const remote = document.getElementById("remote");
const CHUNK = 15000;
let incoming = [];

function send(o) { port.postMessage(o); }
function log(t) { send({ type: "log", text: String(t) }); }

async function newPeer() {
  pc = new RTCPeerConnection({ iceServers: [{ urls: "stun:stun.l.google.com:19302" }, { urls: "stun:stun.cloudflare.com:3478" }] });
  pc.onicecandidate = e => { if (e.candidate) send({ type: "ice", candidate: e.candidate.candidate, mid: e.candidate.sdpMid, index: e.candidate.sdpMLineIndex }); };
  pc.onconnectionstatechange = () => send({ type: "state", state: pc.connectionState });
  pc.ontrack = e => { remote.srcObject = e.streams[0] || new MediaStream([e.track]); remote.play().catch(log); };
  pc.ondatachannel = e => setupChannel(e.channel);
  mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
  mic.getTracks().forEach(t => pc.addTrack(t, mic));
}

function setupChannel(ch) {
  dc = ch;
  dc.binaryType = "arraybuffer";
  dc.onmessage = e => {
    // frames: "F<n>" header then n chunks of base64 JPEG
    const m = e.data;
    if (typeof m !== "string") return;
    if (m.startsWith("F")) { incoming = []; incoming.expected = parseInt(m.slice(1)); return; }
    if (m === "S0") { send({ type: "screenOff" }); return; }
    incoming.push(m);
    if (incoming.length === incoming.expected) { send({ type: "frame", jpeg: incoming.join("") }); incoming = []; }
  };
}

async function flushIce() { remoteSet = true; for (const c of pendingIce) { try { await pc.addIceCandidate(c); } catch (e) { log(e); } } pendingIce = []; }

port.onMessage.addListener(async m => {
  try {
    if (m.type === "call") {
      await newPeer();
      setupChannel(pc.createDataChannel("screen", { ordered: true }));
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      send({ type: "offer", sdp: offer.sdp });
    } else if (m.type === "offer") {
      await newPeer();
      await pc.setRemoteDescription({ type: "offer", sdp: m.sdp });
      await flushIce();
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      send({ type: "answer", sdp: answer.sdp });
    } else if (m.type === "answer") {
      await pc.setRemoteDescription({ type: "answer", sdp: m.sdp });
      await flushIce();
    } else if (m.type === "ice") {
      const c = { candidate: m.candidate, sdpMid: m.mid, sdpMLineIndex: m.index };
      if (remoteSet && pc) await pc.addIceCandidate(c); else pendingIce.push(c);
    } else if (m.type === "mute") {
      if (mic) mic.getAudioTracks().forEach(t => t.enabled = !m.on);
    } else if (m.type === "frame") {
      // our shared screen: a base64 JPEG cut into chunks the channel accepts
      if (!dc || dc.readyState !== "open" || dc.bufferedAmount > 400000) return;
      const s = m.jpeg, n = Math.ceil(s.length / CHUNK);
      dc.send("F" + n);
      for (let i = 0; i < n; i++) dc.send(s.slice(i * CHUNK, (i + 1) * CHUNK));
    } else if (m.type === "screenOff") {
      if (dc && dc.readyState === "open") dc.send("S0");
    } else if (m.type === "hangup") {
      if (pc) pc.close();
      if (mic) mic.getTracks().forEach(t => t.stop());
      pc = null; mic = null; dc = null; pendingIce = []; remoteSet = false; remote.srcObject = null;
    }
  } catch (e) { send({ type: "error", text: String(e) }); }
});
send({ type: "ready" });
