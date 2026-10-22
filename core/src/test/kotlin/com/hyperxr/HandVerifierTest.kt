package com.hyperxr

import com.hyperxr.handtracking.HandVerifier
import com.hyperxr.handtracking.npu.HandRoi
import com.hyperxr.handtracking.npu.PalmDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandVerifierTest {
    // Metric landmarks the landmark model produced on real photos (scratch tests, docs/HAND_TRACKING.md):
    // an open left hand (presence 0.995) and the sole of a foot the model scored 0.68 as a "hand".
    private val hand = floatArrayOf(0.0217f,0.0515f,0.0741f,0.0100f,0.0171f,0.0637f,-0.0026f,-0.0040f,0.0531f,-0.0178f,-0.0076f,0.0270f,-0.0339f,-0.0097f,-0.0026f,0.0035f,-0.0122f,0.0106f,-0.0202f,-0.0139f,0.0098f,-0.0393f,-0.0152f,0.0264f,-0.0519f,-0.0064f,0.0490f,0.0006f,-0.0025f,-0.0002f,-0.0237f,-0.0077f,-0.0010f,-0.0432f,0.0041f,0.0180f,-0.0467f,0.0070f,0.0454f,-0.0024f,0.0079f,-0.0077f,-0.0256f,0.0117f,-0.0040f,-0.0296f,0.0174f,0.0205f,-0.0227f,0.0182f,0.0445f,-0.0084f,0.0264f,-0.0033f,-0.0261f,0.0256f,-0.0009f,-0.0333f,0.0278f,0.0201f,-0.0270f,0.0272f,0.0338f)
    private val foot = floatArrayOf(0.0158f,0.0155f,-0.1742f,-0.0244f,-0.0045f,-0.1325f,-0.0284f,-0.0142f,-0.0711f,-0.0284f,-0.0315f,-0.0155f,-0.0222f,-0.0557f,0.0090f,-0.0057f,-0.0320f,-0.0011f,0.0050f,-0.0315f,0.0350f,0.0000f,-0.0107f,0.0511f,0.0159f,-0.0282f,0.0781f,-0.0086f,-0.0036f,0.0130f,0.0298f,-0.0052f,0.0339f,0.0043f,-0.0135f,0.0091f,-0.0004f,-0.0152f,0.0250f,0.0045f,0.0136f,0.0053f,0.0212f,0.0110f,0.0435f,0.0121f,0.0126f,0.0109f,-0.0120f,0.0027f,-0.0108f,-0.0025f,0.0207f,-0.0057f,0.0021f,0.0378f,0.0106f,0.0037f,0.0401f,0.0307f,-0.0099f,0.0204f,0.0307f)

    @Test fun chiralityTellsRightFromLeft() {
        val r = com.hyperxr.handtracking.HandednessResolver()
        // the fixture is a left hand (the landmark model agrees: 0.01 for "right"); its mirror image is a right hand
        assertTrue(r.chirality(hand) < -0.01f)
        val mirrored = hand.copyOf().also { m -> for (j in 0 until 21) m[j * 3] = -m[j * 3] }
        assertTrue(r.chirality(mirrored) > 0.01f)
        val res = r.resolve(listOf(com.hyperxr.handtracking.HandednessResolver.Input(hand, 0.7f, 0.5f, Float.NaN)))
        assertEquals(listOf(com.hyperxr.handtracking.Handedness.LEFT), res)
    }

    @Test fun twoHandsGetBothSidesAndKeepThem() {
        val r = com.hyperxr.handtracking.HandednessResolver()
        val mirrored = hand.copyOf().also { m -> for (j in 0 until 21) m[j * 3] = -m[j * 3] }
        val R = com.hyperxr.handtracking.Handedness.RIGHT; val L = com.hyperxr.handtracking.Handedness.LEFT
        val two = listOf(com.hyperxr.handtracking.HandednessResolver.Input(hand, 0.3f, 0.5f, Float.NaN),
            com.hyperxr.handtracking.HandednessResolver.Input(mirrored, 0.7f, 0.5f, Float.NaN))
        repeat(5) { assertEquals(listOf(L, R), r.resolve(two)) }
        // a doubtful frame (no 3D, model unsure) does not swap a hand that has been seen
        val doubtful = listOf(com.hyperxr.handtracking.HandednessResolver.Input(null, 0.31f, 0.5f, 0.6f),
            com.hyperxr.handtracking.HandednessResolver.Input(null, 0.69f, 0.5f, 0.4f))
        assertEquals(listOf(L, R), r.resolve(doubtful))
        // both judged right in one frame: they still get different sides
        val same = listOf(com.hyperxr.handtracking.HandednessResolver.Input(hand, 0.2f, 0.2f, Float.NaN),
            com.hyperxr.handtracking.HandednessResolver.Input(hand, 0.8f, 0.8f, Float.NaN))
        assertEquals(setOf(L, R), com.hyperxr.handtracking.HandednessResolver().resolve(same).toSet())
    }

    @Test fun realHandHasHandShape() { assertNull(HandVerifier().shape(hand)) }

    @Test fun footIsTooBigForAHand() {
        val v = HandVerifier()
        val r = v.shape(foot)
        assertNotNull(r)
        assertTrue(r!!, r.contains("pé"))
    }

    @Test fun footScoreDoesNotPassAcquisition() {
        val v = HandVerifier()
        repeat(5) { assertFalse(v.gate(0, 0.68f)) }
        assertTrue(v.lastRejection.isNotEmpty())
    }

    @Test fun handNeedsTwoConfidentFramesThenStays() {
        val v = HandVerifier()
        assertFalse(v.gate(1, 0.85f))  // good but not certain: wait one more frame
        assertTrue(v.gate(1, 0.85f))
        assertTrue(v.gate(1, 0.6f))   // blur: kept once confirmed
        assertFalse(v.gate(1, 0.3f))  // lost
        assertFalse(v.gate(1, 0.6f))  // must be re-acquired with a confident score
    }

    /** The way the service calls it each frame: a new hand must get confirmed (regression). */
    @Test fun newHandIsConfirmedAcrossFrames() {
        val v = HandVerifier()
        var used = false
        repeat(3) {
            v.beginFrame()
            used = v.gate(1, 0.85f)
            v.endFrame()
        }
        assertTrue(used)
        // a frame without the hand drops it; it then needs confirming again
        v.beginFrame(); v.endFrame()
        v.beginFrame(); assertFalse(v.gate(1, 0.85f)); v.endFrame()
        v.beginFrame(); assertTrue(v.gate(1, 0.85f)); v.endFrame()
        // a certain hand shows up on its very first frame
        v.beginFrame(); v.endFrame()
        v.beginFrame(); assertTrue(v.gate(1, 0.97f)); v.endFrame()
    }

    @Test fun reachRejectsFeetAndOtherPeople() {
        val v = HandVerifier()
        assertNull(v.reach(0.1f, -0.2f, -0.45f))
        assertNotNull(v.reach(0f, -1.3f, -0.6f))  // looking down at a foot from standing height
    }

    @Test fun anchorsAndDecode() {
        assertEquals(PalmDecoder.ANCHORS * 2, PalmDecoder.anchors.size)
        val reg = FloatArray(PalmDecoder.ANCHORS * 18)
        val logits = FloatArray(PalmDecoder.ANCHORS) { -10f }
        // one confident palm on anchor 100, 40x40 px in the 192 input, centred on the anchor
        logits[100] = 5f
        reg[100 * 18 + 2] = 40f; reg[100 * 18 + 3] = 40f
        reg[100 * 18 + 5] = 10f   // wrist keypoint below the centre
        reg[100 * 18 + 9] = -10f  // middle knuckle above it
        val d = PalmDecoder.decode(reg, logits, 640, 480)
        assertEquals(1, d.size)
        val p = d[0]
        assertEquals(PalmDecoder.anchors[200] * 640, p.cx, 1e-3f)
        assertEquals(PalmDecoder.anchors[201] * 640 - 80f, p.cy, 1e-3f)  // letterbox removed
        assertEquals(40f / 192 * 640, p.w, 1e-3f)
        // wrist below, knuckle above: the hand points up, no rotation needed
        val roi = HandRoi.fromPalm(p)
        assertEquals(0f, roi.rotation, 1e-4f)
        assertTrue(roi.cy < p.cy)  // shifted toward the fingers
    }

    @Test fun roiMapsTheCentreOfTheInputToItsCentre() {
        val r = HandRoi(300f, 200f, 224f, 0.7f)
        val o = FloatArray(2)
        r.toImage(112f, 112f, o)
        assertEquals(300f, o[0], 1e-3f); assertEquals(200f, o[1], 1e-3f)
    }
}
