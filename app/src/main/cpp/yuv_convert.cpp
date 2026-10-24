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

// Affine crop + bilinear sampling + RGB float normalization in one pass, straight into the model's
// input tensor (HyperXR NPU hand tracker). m maps output pixel centres to source coordinates:
// x = m0*u + m1*v + m2, y = m3*u + m4*v + m5. Outside the frame is black, like the letterbox.
extern "C" JNIEXPORT jboolean JNICALL Java_com_hyperxr_handtracking_YuvNative_warpToTensor(
        JNIEnv* env, jclass, jobject bitmap, jfloatArray jm, jint size, jobject out) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    float* dst = static_cast<float*>(env->GetDirectBufferAddress(out));
    if (!dst || env->GetDirectBufferCapacity(out) < static_cast<jlong>(size) * size * 3 * 4) return JNI_FALSE;
    float m[6];
    env->GetFloatArrayRegion(jm, 0, 6, m);
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return JNI_FALSE;
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    const int w = static_cast<int>(info.width), h = static_cast<int>(info.height);
    const int stride = static_cast<int>(info.stride);
    const float k = 1.0f / 255.0f;
    for (int v = 0; v < size; v++) {
        // source position of this row's first pixel centre, then step per output pixel
        float x = m[0] * 0.5f + m[1] * (v + 0.5f) + m[2] - 0.5f;
        float y = m[3] * 0.5f + m[4] * (v + 0.5f) + m[5] - 0.5f;
        float* o = dst + static_cast<size_t>(v) * size * 3;
        for (int u = 0; u < size; u++, x += m[0], y += m[3], o += 3) {
            int x0 = static_cast<int>(x < 0 ? x - 1 : x), y0 = static_cast<int>(y < 0 ? y - 1 : y);
            if (x0 < 0 || y0 < 0 || x0 >= w - 1 || y0 >= h - 1) { o[0] = o[1] = o[2] = 0.f; continue; }
            float fx = x - x0, fy = y - y0;
            const uint8_t* p = src + y0 * stride + x0 * 4;
            const uint8_t* q = p + stride;
            float w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy;
            o[0] = (p[0] * w00 + p[4] * w10 + q[0] * w01 + q[4] * w11) * k;
            o[1] = (p[1] * w00 + p[5] * w10 + q[1] * w01 + q[5] * w11) * k;
            o[2] = (p[2] * w00 + p[6] * w10 + q[2] * w01 + q[6] * w11) * k;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

/*
 * Like warpToTensor, with the model's own normalisation folded in: out = (pixel - mean) * invStd
 * per channel, pixels outside the image take [pad] (raw 0..255). [planarBgr] writes three
 * planes in B, G, R order (NCHW, as OpenMMLab's detectors take it); otherwise interleaved RGB
 * (NHWC). Writes width x height samples (a letterbox may be wider than the image).
 */
extern "C" JNIEXPORT jboolean JNICALL Java_com_hyperxr_handtracking_YuvNative_warpToTensorNorm(
        JNIEnv* env, jclass, jobject bitmap, jfloatArray jm, jint outW, jint outH, jfloatArray jmean, jfloatArray jinv, jfloat pad,
        jboolean planarBgr, jobject out) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    float* dst = static_cast<float*>(env->GetDirectBufferAddress(out));
    const size_t plane = static_cast<size_t>(outW) * outH;
    if (!dst || env->GetDirectBufferCapacity(out) < static_cast<jlong>(plane * 3 * 4)) return JNI_FALSE;
    float m[6], mean[3], inv[3];
    env->GetFloatArrayRegion(jm, 0, 6, m);
    env->GetFloatArrayRegion(jmean, 0, 3, mean);
    env->GetFloatArrayRegion(jinv, 0, 3, inv);
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return JNI_FALSE;
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    const int w = static_cast<int>(info.width), h = static_cast<int>(info.height);
    const int stride = static_cast<int>(info.stride);
    const float pr = (pad - mean[0]) * inv[0], pg = (pad - mean[1]) * inv[1], pb = (pad - mean[2]) * inv[2];
    const bool planar = planarBgr == JNI_TRUE;
    for (int v = 0; v < outH; v++) {
        float x = m[0] * 0.5f + m[1] * (v + 0.5f) + m[2] - 0.5f;
        float y = m[3] * 0.5f + m[4] * (v + 0.5f) + m[5] - 0.5f;
        for (int u = 0; u < outW; u++, x += m[0], y += m[3]) {
            float r, g, b;
            int x0 = static_cast<int>(x < 0 ? x - 1 : x), y0 = static_cast<int>(y < 0 ? y - 1 : y);
            if (x0 < 0 || y0 < 0 || x0 >= w - 1 || y0 >= h - 1) { r = pr; g = pg; b = pb; }
            else {
                float fx = x - x0, fy = y - y0;
                const uint8_t* p = src + y0 * stride + x0 * 4;
                const uint8_t* q = p + stride;
                float w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy;
                r = ((p[0] * w00 + p[4] * w10 + q[0] * w01 + q[4] * w11) - mean[0]) * inv[0];
                g = ((p[1] * w00 + p[5] * w10 + q[1] * w01 + q[5] * w11) - mean[1]) * inv[1];
                b = ((p[2] * w00 + p[6] * w10 + q[2] * w01 + q[6] * w11) - mean[2]) * inv[2];
            }
            const size_t i = static_cast<size_t>(v) * outW + u;
            if (planar) { dst[i] = b; dst[plane + i] = g; dst[2 * plane + i] = r; }
            else { float* o = dst + i * 3; o[0] = r; o[1] = g; o[2] = b; }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
