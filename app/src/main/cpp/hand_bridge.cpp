// HyperMagic -> OpenXR games: writes the tracked hands into the shared file the HyperMagic runtime
// maps inside the game (layout and conventions in native/openxr-runtime/src/hx_hands.h).
// The sequence counter is odd while a write is in progress, so the reader never takes a torn copy.
#include <fcntl.h>
#include <jni.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "hx_hands.h"

namespace {
HxHandShared* asMap(jlong p) { return reinterpret_cast<HxHandShared*>(p); }
}

extern "C" {

JNIEXPORT jint JNICALL Java_com_hyperxr_openxr_HandBridge_nativeSize(JNIEnv*, jclass) { return sizeof(HxHandShared); }

/** Maps [path] (created with the right size and header when missing); 0 on failure. */
JNIEXPORT jlong JNICALL Java_com_hyperxr_openxr_HandBridge_nativeOpen(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    int fd = open(p, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    env->ReleaseStringUTFChars(path, p);
    if (fd < 0) return 0;
    if (ftruncate(fd, sizeof(HxHandShared)) != 0) { close(fd); return 0; }
    void* m = mmap(nullptr, sizeof(HxHandShared), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return 0;
    HxHandShared* h = static_cast<HxHandShared*>(m);
    if (h->magic != HX_HANDS_MAGIC) { memset(h, 0, sizeof(*h)); h->magic = HX_HANDS_MAGIC; }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_com_hyperxr_openxr_HandBridge_nativeClose(JNIEnv*, jclass, jlong map) {
    if (map) munmap(asMap(map), sizeof(HxHandShared));
}

/**
 * [state]: per hand (left, right) tracked, confidence, pinch; [joints]: 2 x 21 x 3 floats, camera
 * space. Both hands are for [timeNs] (CLOCK_MONOTONIC).
 */
JNIEXPORT void JNICALL Java_com_hyperxr_openxr_HandBridge_nativeWrite(JNIEnv* env, jclass, jlong map, jlong timeNs,
                                                                     jfloatArray state, jfloatArray joints) {
    HxHandShared* h = asMap(map);
    if (!h) return;
    float st[6], j[2 * HX_HANDS_LANDMARKS * 3];
    env->GetFloatArrayRegion(state, 0, 6, st);
    env->GetFloatArrayRegion(joints, 0, 2 * HX_HANDS_LANDMARKS * 3, j);
    uint32_t seq = __atomic_load_n(&h->seq, __ATOMIC_RELAXED);
    __atomic_store_n(&h->seq, seq + 1, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_RELEASE);
    for (int s = 0; s < 2; s++) {
        h->timeNs[s] = timeNs;
        h->tracked[s] = st[s * 3] > 0.5f ? 1u : 0u;
        h->confidence[s] = st[s * 3 + 1];
        h->pinch[s] = st[s * 3 + 2];
    }
    memcpy(h->joints, j, sizeof(h->joints));
    __atomic_store_n(&h->seq, seq + 2, __ATOMIC_RELEASE);
}

/** When the runtime in a game last said it was alive / wanted hands (CLOCK_MONOTONIC, 0 = never). */
JNIEXPORT jlong JNICALL Java_com_hyperxr_openxr_HandBridge_nativeReaderAlive(JNIEnv*, jclass, jlong map) {
    return map ? __atomic_load_n(&asMap(map)->readerAliveNs, __ATOMIC_RELAXED) : 0;
}
JNIEXPORT jlong JNICALL Java_com_hyperxr_openxr_HandBridge_nativeReaderWantsHands(JNIEnv*, jclass, jlong map) {
    return map ? __atomic_load_n(&asMap(map)->readerWantsHandsNs, __ATOMIC_RELAXED) : 0;
}

}  // extern "C"
