// YUV_420_888 -> RGBA bitmap for hand tracking, written straight into the Bitmap's pixels.
// Same integer BT.601 math as the Kotlin fallback (YuvToBitmap), with nearest-neighbour
// downscaling by [step] and optional 180 degree rotation; ~10x faster than per-byte ByteBuffer
// access from Kotlin, so the camera thread hands frames to MediaPipe sooner.
#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>

static inline uint8_t clamp8(int v) { return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v)); }

extern "C" JNIEXPORT jboolean JNICALL Java_com_hyperxr_handtracking_YuvNative_convert(
        JNIEnv* env, jclass, jobject yBuf, jobject uBuf, jobject vBuf, jint yRow, jint yPix, jint uvRow, jint uvPix,
        jint step, jboolean rotate180, jobject bitmap) {
    const uint8_t* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    const uint8_t* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    const uint8_t* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!y || !u || !v || step < 1) return JNI_FALSE;
    const jlong yCap = env->GetDirectBufferCapacity(yBuf), uCap = env->GetDirectBufferCapacity(uBuf), vCap = env->GetDirectBufferCapacity(vBuf);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    const int ow = static_cast<int>(info.width), oh = static_cast<int>(info.height);
    // every read must stay inside the planes
    const jlong lastY = static_cast<jlong>((oh - 1) * step) * yRow + static_cast<jlong>((ow - 1) * step) * yPix;
    const jlong lastUv = static_cast<jlong>(((oh - 1) * step) >> 1) * uvRow + static_cast<jlong>(((ow - 1) * step) >> 1) * uvPix;
    if (lastY >= yCap || lastUv >= uCap || lastUv >= vCap) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return JNI_FALSE;
    for (int oy = 0; oy < oh; oy++) {
        const int sy = oy * step;
        const uint8_t* yr = y + static_cast<long>(sy) * yRow;
        const long uvBase = static_cast<long>(sy >> 1) * uvRow;
        uint8_t* dst = static_cast<uint8_t*>(pixels) + static_cast<long>(rotate180 ? oh - 1 - oy : oy) * info.stride;
        for (int ox = 0; ox < ow; ox++) {
            const int sx = ox * step;
            int yy = yr[static_cast<long>(sx) * yPix] - 16;
            if (yy < 0) yy = 0;
            const long ui = uvBase + static_cast<long>(sx >> 1) * uvPix;
            const int uu = u[ui] - 128, vv = v[ui] - 128;
            const int c = 1192 * yy;
            uint8_t* p = dst + 4 * (rotate180 ? ow - 1 - ox : ox);
            p[0] = clamp8((c + 1634 * vv) >> 10);
            p[1] = clamp8((c - 833 * vv - 400 * uu) >> 10);
            p[2] = clamp8((c + 2066 * uu) >> 10);
            p[3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
