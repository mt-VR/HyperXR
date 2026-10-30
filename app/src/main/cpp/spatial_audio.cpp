// HyperMagic spatial audio: Resonance Audio (Google, Apache-2.0) renders every positioned sound
// binaurally (HRTF, third-order ambisonics), so a sound is heard in front, behind, above or below,
// not only left or right. Output through AAudio's low-latency path.
//
// Threads: Kotlin only queues commands (play, stop, listener pose); the audio callback applies them
// at the start of each block, so Resonance is only ever touched from the audio thread.
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include "api/resonance_audio_api.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "HxSpatialAudio", __VA_ARGS__)

namespace {

constexpr int kRate = 44100;      // the rate HyperMagic synthesises its sounds at
constexpr int kBlock = 256;       // Resonance renders fixed blocks of this many frames
constexpr int kMaxVoices = 32;

struct Sound { std::vector<float> pcm; };

struct Voice {
    bool active = false;
    int sound = -1;
    double pos = 0;               // in source frames (pitch moves it faster or slower)
    float pitch = 1, volume = 1;
    bool loop = false, spatial = false;
    int source = -1;              // Resonance source id
    float x = 0, y = 0, z = 0;
    int handle = 0;
};

struct Command {
    enum Kind { PLAY, STOP, MOVE } kind;
    int handle, sound;
    float volume, pitch, x, y, z;
    bool loop, spatial;
};

struct Engine {
    vraudio::ResonanceAudioApi* api = nullptr;
    AAudioStream* stream = nullptr;
    std::mutex lock;                         // guards sounds, commands and the listener
    std::vector<std::unique_ptr<Sound>> sounds;
    std::vector<Command> commands;
    float head[7] = {0, 1.6f, 0, 0, 0, 0, 1};
    Voice voices[kMaxVoices];
    std::vector<float> mono = std::vector<float>(kBlock);
    std::vector<float> stereo = std::vector<float>(kBlock * 2);
    std::vector<float> out = std::vector<float>(kBlock * 2);
    int outLeft = 0;                         // frames of [out] not yet handed to AAudio
    std::atomic<int> nextHandle{1};
    float master = 1;
};

Engine* g = nullptr;

void apply(Engine& e) {
    std::vector<Command> cmds;
    float head[7];
    {
        std::lock_guard<std::mutex> l(e.lock);
        cmds.swap(e.commands);
        std::memcpy(head, e.head, sizeof head);
    }
    e.api->SetHeadPosition(head[0], head[1], head[2]);
    e.api->SetHeadRotation(head[3], head[4], head[5], head[6]);
    for (auto& c : cmds) {
        if (c.kind == Command::PLAY) {
            Voice* v = nullptr;
            for (auto& cand : e.voices) if (!cand.active) { v = &cand; break; }
            if (!v) continue;                // all voices busy: the newest sound is dropped
            *v = Voice{};
            v->active = true; v->sound = c.sound; v->pitch = c.pitch; v->volume = c.volume;
            v->loop = c.loop; v->spatial = c.spatial; v->handle = c.handle;
            v->x = c.x; v->y = c.y; v->z = c.z;
            if (c.spatial) {
                v->source = e.api->CreateSoundObjectSource(vraudio::kBinauralHighQuality);
                e.api->SetSourceDistanceModel(v->source, vraudio::kLogarithmic, 0.25f, 30.f);
                e.api->SetSourcePosition(v->source, c.x, c.y, c.z);
            } else {
                v->source = e.api->CreateStereoSource(2);
            }
            e.api->SetSourceVolume(v->source, c.volume);
        } else {
            for (auto& v : e.voices) {
                if (!v.active || v.handle != c.handle) continue;
                if (c.kind == Command::STOP) { e.api->DestroySource(v.source); v.active = false; }
                else if (v.spatial) e.api->SetSourcePosition(v.source, c.x, c.y, c.z);
            }
        }
    }
}

/** One block of every voice into Resonance, then the binaural mix out. */
void renderBlock(Engine& e) {
    apply(e);
    std::lock_guard<std::mutex> l(e.lock);   // the sounds' PCM must not move while it is read
    for (auto& v : e.voices) {
        if (!v.active) continue;
        const auto& pcm = e.sounds[v.sound]->pcm;
        const double len = static_cast<double>(pcm.size());
        bool ended = false;
        for (int i = 0; i < kBlock; i++) {
            float s = 0;
            if (!ended) {
                if (v.pos >= len - 1) {
                    if (v.loop && len > 1) v.pos = std::fmod(v.pos, len - 1);
                    else { ended = true; }
                }
                if (!ended) {
                    const size_t k = static_cast<size_t>(v.pos);
                    const float t = static_cast<float>(v.pos - k);
                    s = pcm[k] * (1 - t) + pcm[k + 1] * t;
                    v.pos += v.pitch;
                }
            }
            if (v.spatial) e.mono[i] = s;
            else { e.stereo[i * 2] = s; e.stereo[i * 2 + 1] = s; }
        }
        if (v.spatial) e.api->SetInterleavedBuffer(v.source, e.mono.data(), 1, kBlock);
        else e.api->SetInterleavedBuffer(v.source, e.stereo.data(), 2, kBlock);
        if (ended) { e.api->DestroySource(v.source); v.active = false; }
    }
    if (!e.api->FillInterleavedOutputBuffer(2, kBlock, e.out.data())) std::fill(e.out.begin(), e.out.end(), 0.f);
    for (auto& s : e.out) s = std::fmax(-1.f, std::fmin(1.f, s * e.master));
    e.outLeft = kBlock;
}

aaudio_data_callback_result_t onAudio(AAudioStream*, void* user, void* data, int32_t frames) {
    auto& e = *static_cast<Engine*>(user);
    auto* dst = static_cast<float*>(data);
    while (frames > 0) {
        if (e.outLeft == 0) renderBlock(e);
        const int n = std::min(frames, e.outLeft);
        std::memcpy(dst, e.out.data() + (kBlock - e.outLeft) * 2, n * 2 * sizeof(float));
        dst += n * 2; frames -= n; e.outLeft -= n;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_hyperxr_audio_SpatialAudio_nativeStart(JNIEnv*, jclass) {
    if (g) return JNI_TRUE;
    auto* e = new Engine();
    e->api = vraudio::CreateResonanceAudioApi(2, kBlock, kRate);
    if (!e->api) { delete e; return JNI_FALSE; }
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) { delete e->api; delete e; return JNI_FALSE; }
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, kRate);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    if (__builtin_available(android 28, *)) AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setDataCallback(b, onAudio, e);
    const aaudio_result_t r = AAudioStreamBuilder_openStream(b, &e->stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || AAudioStream_requestStart(e->stream) != AAUDIO_OK) {
        LOG("AAudio failed: %s", AAudio_convertResultToText(r));
        if (e->stream) AAudioStream_close(e->stream);
        delete e->api; delete e; return JNI_FALSE;
    }
    LOG("Resonance Audio on AAudio, %d Hz, burst %d", AAudioStream_getSampleRate(e->stream), AAudioStream_getFramesPerBurst(e->stream));
    g = e;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_hyperxr_audio_SpatialAudio_nativeStop(JNIEnv*, jclass) {
    if (!g) return;
    AAudioStream_requestStop(g->stream);
    AAudioStream_close(g->stream);
    delete g->api;
    delete g;
    g = nullptr;
}

JNIEXPORT jint JNICALL Java_com_hyperxr_audio_SpatialAudio_nativeAddSound(JNIEnv* env, jclass, jfloatArray pcm) {
    if (!g) return -1;
    auto s = std::make_unique<Sound>();
    const jsize n = env->GetArrayLength(pcm);
    s->pcm.resize(n + 1);
    env->GetFloatArrayRegion(pcm, 0, n, s->pcm.data());
    s->pcm[n] = 0;                              // one silent frame: the interpolation never reads past the end
    std::lock_guard<std::mutex> l(g->lock);
    g->sounds.push_back(std::move(s));
    return static_cast<jint>(g->sounds.size() - 1);
}

JNIEXPORT jint JNICALL Java_com_hyperxr_audio_SpatialAudio_nativePlay(JNIEnv*, jclass, jint sound, jfloat volume, jfloat pitch,
                                                                      jboolean loop, jboolean spatial, jfloat x, jfloat y, jfloat z) {
    if (!g || sound < 0) return 0;
    const int h = g->nextHandle++;
    std::lock_guard<std::mutex> l(g->lock);
    if (sound >= static_cast<jint>(g->sounds.size())) return 0;
    g->commands.push_back({Command::PLAY, h, sound, volume, pitch, x, y, z, loop == JNI_TRUE, spatial == JNI_TRUE});
    return h;
}

JNIEXPORT void JNICALL Java_com_hyperxr_audio_SpatialAudio_nativeStopVoice(JNIEnv*, jclass, jint handle) {
    if (!g) return;
    std::lock_guard<std::mutex> l(g->lock);
    g->commands.push_back({Command::STOP, handle, 0, 0, 0, 0, 0, 0, false, false});
}

JNIEXPORT void JNICALL Java_com_hyperxr_audio_SpatialAudio_nativeMove(JNIEnv*, jclass, jint handle, jfloat x, jfloat y, jfloat z) {
    if (!g) return;
    std::lock_guard<std::mutex> l(g->lock);
    g->commands.push_back({Command::MOVE, handle, 0, 0, 0, x, y, z, false, false});
}

JNIEXPORT void JNICALL Java_com_hyperxr_audio_SpatialAudio_nativeListener(JNIEnv*, jclass, jfloat px, jfloat py, jfloat pz,
                                                                          jfloat qx, jfloat qy, jfloat qz, jfloat qw) {
    if (!g) return;
    std::lock_guard<std::mutex> l(g->lock);
    const float h[7] = {px, py, pz, qx, qy, qz, qw};
    std::memcpy(g->head, h, sizeof h);
}

JNIEXPORT void JNICALL Java_com_hyperxr_audio_SpatialAudio_nativeMaster(JNIEnv*, jclass, jfloat v) {
    if (g) g->master = v;
}

}  // extern "C"
