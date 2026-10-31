// Camera frames from the HyperMagic app to its OpenXR runtime, for passthrough (the ALPHA_BLEND
// environment blend mode).
//
// HyperMagic's hand bridge service owns the camera while a game runs (the game has no camera
// permission). When the runtime asks for passthrough, the service writes small RGBA frames into
// this shared file, mapped by the runtime through content://com.hypermagic.runtime/camera.
// Two buffers: the app fills the one not published, then publishes it by bumping [seq]; a reader
// that sees [seq] move by more than one while it read has a frame that may have been overwritten.
#pragma once

#include <stdint.h>

#define HX_CAMERA_MAGIC 0x31434858u  // "XHC1"
#define HX_CAMERA_MAX_W 640
#define HX_CAMERA_MAX_H 480

struct HxCameraShared {
    uint32_t magic;
    uint32_t seq;              // frames published so far
    uint32_t width, height;    // of the latest frame, rows top to bottom, RGBA8
    uint32_t latest;           // buffer holding the latest frame (0 or 1)
    uint32_t pad;
    float fx, fy, cx, cy;      // pinhole intrinsics of the frame, pixels (image y down)
    int64_t timeNs;            // CLOCK_MONOTONIC capture time of the latest frame
    int64_t readerWantsNs;     // the runtime shows passthrough (the app keeps the camera on)
    uint8_t pixels[2][HX_CAMERA_MAX_W * HX_CAMERA_MAX_H * 4];
};
