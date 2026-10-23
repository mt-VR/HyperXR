package com.hyperxr.headtracking

/**
 * When the frame being drawn now will light up the screen, for head prediction.
 *
 * A fixed "now + two refresh periods" is right on average but lands anywhere within a period of
 * the real moment, depending on where in the refresh cycle the frame starts: the prediction
 * target then wobbles by up to a period from frame to frame, and the view shakes slightly during
 * head turns. Here the target is locked to the display's vsync: the frame is ready when the GPU
 * finishes (measured, smoothed), the compositor picks it up at the next vsync, shows it one
 * refresh later, and the middle of the scan-out is half a refresh after that.
 * Times are System.nanoTime() (Choreographer's timebase).
 */
class PhotonClock {
    @Volatile var vsyncNs = 0L; private set
    @Volatile var periodNs = 16_666_667L
    /** Pose latch to GPU done, smoothed (ns). */
    var renderNs = 6_000_000f; private set

    /** A vsync seen by the UI thread (Choreographer frame time). */
    fun onVsync(frameTimeNs: Long) { if (frameTimeNs > vsyncNs) vsyncNs = frameTimeNs }

    /** How long the last frame took from the pose latch until the GPU finished it. */
    fun onRendered(ns: Long) {
        val v = ns.toFloat().coerceIn(1_000_000f, 2f * periodNs)
        renderNs += (v - renderNs) / 8f
    }

    /** Photon time for a frame whose pose is latched at [nowNs]; [trimNs] is the user's adjustment. */
    fun photon(nowNs: Long, trimNs: Long = 0L): Long {
        val p = periodNs
        val v = vsyncNs
        if (v == 0L || nowNs - v > 10 * p) return nowNs + 2 * p + trimNs   // no vsync yet: the old rule
        val ready = nowNs + renderNs.toLong()
        val k = if (ready <= v) 0L else (ready - v + p - 1) / p
        val pickup = v + k * p
        return pickup + p + p / 2 + trimNs
    }
}
