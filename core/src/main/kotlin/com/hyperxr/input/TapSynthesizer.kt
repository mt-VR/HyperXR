package com.hyperxr.input

import kotlin.math.abs

/**
 * Turns a pinch (or controller button) held on a window into the touch stream an Android/web page
 * expects. A hand pinch is slow (detection takes a few frames) and the pointer wobbles while the
 * fingers close, so forwarding it raw produces a long press or a small drag, never a click:
 *  - released while still and before [holdMs]: a crisp tap at the point where the pinch started;
 *  - moved beyond the slop: a drag/scroll that starts at the original point;
 *  - held still for [holdMs]: a real long press (context menus, link drag).
 * Actions use the MotionEvent codes (0 down, 1 up, 2 move, 3 cancel).
 */
class TapSynthesizer(
    /** Movement tolerated before a press becomes a drag, as a fraction of the display width. */
    var slopFraction: Float = 0.018f,
    var holdMs: Long = 900L,
) {
    fun interface Sink { fun touch(action: Int, x: Float, y: Float, downTimeMs: Long, eventTimeMs: Long) }

    private var pending = false
    private var active = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var slop = 20f
    /** Called once when a press turns into a long press (still for [holdMs]). */
    var onLongHold: ((x: Float, y: Float) -> Unit)? = null

    val pressing: Boolean get() = pending || active

    fun down(x: Float, y: Float, nowMs: Long, displayWidthPx: Int) {
        pending = true; active = false
        downX = x; downY = y; downTime = nowMs
        slop = (displayWidthPx * slopFraction).coerceAtLeast(8f)
    }

    fun move(x: Float, y: Float, nowMs: Long, sink: Sink) {
        if (pending) {
            val moved = abs(x - downX) > slop || abs(y - downY) > slop
            when {
                moved -> {
                    // a drag: the touch starts where the pinch started, then follows the pointer
                    pending = false; active = true
                    sink.touch(DOWN, downX, downY, downTime, downTime)
                    sink.touch(MOVE, x, y, downTime, nowMs)
                }
                nowMs - downTime >= holdMs -> {
                    pending = false; active = true
                    sink.touch(DOWN, downX, downY, nowMs, nowMs)
                    downTime = nowMs
                    onLongHold?.invoke(downX, downY)
                }
            }
            return
        }
        if (active) sink.touch(MOVE, x, y, downTime, nowMs)
    }

    fun up(x: Float, y: Float, nowMs: Long, sink: Sink) {
        if (pending) {
            // a tap: short and exactly where the pinch began
            pending = false
            sink.touch(DOWN, downX, downY, nowMs - TAP_MS, nowMs - TAP_MS)
            sink.touch(UP, downX, downY, nowMs - TAP_MS, nowMs)
            return
        }
        if (active) { active = false; sink.touch(UP, x, y, downTime, nowMs) }
    }

    fun cancel(nowMs: Long, sink: Sink) {
        if (active) sink.touch(CANCEL, downX, downY, downTime, nowMs)
        pending = false; active = false
    }

    companion object {
        const val DOWN = 0; const val UP = 1; const val MOVE = 2; const val CANCEL = 3
        /** Duration of a synthesized tap: well under every platform's long-press timeout. */
        const val TAP_MS = 40L
    }
}
