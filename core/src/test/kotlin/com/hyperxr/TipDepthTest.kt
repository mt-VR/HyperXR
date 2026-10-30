package com.hyperxr

import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.handtracking.DepthFusion
import com.hyperxr.handtracking.DepthSource
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.LandmarkObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TipDepthTest {
    private val intr = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)

    /** A flat hand 50 cm away, centred, palm about 9 cm: 90 px at fx 500. */
    private fun hand(): LandmarkObservation {
        val o = LandmarkObservation(Handedness.RIGHT)
        o.score = 1f
        for (j in 0 until HandJoint.COUNT) {
            o.image[j * 3] = (320f + (j % 5 - 2) * 18f) / 640f
            o.image[j * 3 + 1] = (300f - (j / 4) * 22f) / 480f
            o.image[j * 3 + 2] = 0f
        }
        // the knuckles a palm apart (wrist to middle knuckle 90 px)
        o.image[HandJoint.WRIST * 3 + 1] = 330f / 480f; o.image[HandJoint.WRIST * 3] = 320f / 640f
        o.image[HandJoint.MIDDLE_MCP * 3 + 1] = 240f / 480f; o.image[HandJoint.MIDDLE_MCP * 3] = 320f / 640f
        return o
    }

    private fun px(o: LandmarkObservation, j: Int) = o.image[j * 3] * 640f to o.image[j * 3 + 1] * 480f

    @Test
    fun indexTipFollowsItsOwnDepth() {
        val o = hand()
        val (tx, ty) = px(o, HandJoint.INDEX_TIP)
        // the palm at 0.50 m, the index tip pushed forward to 0.46 m, the wall at 1 m
        val depth = { x: Float, y: Float ->
            when {
                kotlin.math.abs(x - tx) < 4f && kotlin.math.abs(y - ty) < 4f -> 0.46f
                kotlin.math.abs(x - 320f) < 60f && y in 200f..340f -> 0.50f
                else -> 1.0f
            }
        }
        val out = DepthFusion.Result()
        assertTrue(DepthFusion().fuse(o, intr, depth, null, out))
        assertEquals(DepthSource.DEPTH_MAP, out.depthSource)
        val tipZ = -out.cameraSpace[HandJoint.INDEX_TIP].z
        val palmZ = -out.cameraSpace[HandJoint.MIDDLE_MCP].z
        assertTrue("tip nearer than the palm: $tipZ vs $palmZ", tipZ < palmZ - 0.015f)
    }

    @Test
    fun wallBehindTheTipIsIgnored() {
        val o = hand()
        val (tx, ty) = px(o, HandJoint.THUMB_TIP)
        val depth = { x: Float, y: Float ->
            when {
                kotlin.math.abs(x - tx) < 6f && kotlin.math.abs(y - ty) < 6f -> 1.0f   // missed the thin tip
                kotlin.math.abs(x - 320f) < 60f && y in 200f..340f -> 0.50f
                else -> 1.0f
            }
        }
        val out = DepthFusion.Result()
        assertTrue(DepthFusion().fuse(o, intr, depth, null, out))
        val tipZ = -out.cameraSpace[HandJoint.THUMB_TIP].z
        assertTrue("thumb stays with the hand: $tipZ", tipZ < 0.56f)
    }
}
