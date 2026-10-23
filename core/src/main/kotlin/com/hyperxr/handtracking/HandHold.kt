package com.hyperxr.handtracking

/**
 * How long a hand the detector stopped seeing stays on screen (coasting, still usable). A hand
 * that vanishes in the middle of the camera view has not gone anywhere: it is behind motion blur,
 * the other hand or a bad frame, and is kept for a full second so it hardly ever disappears. One
 * that was last seen at the edge of the view has left it and goes after a short moment.
 */
object HandHold {
    const val INSIDE_NS = 1_000_000_000L
    const val EDGE_NS = 400_000_000L
    const val EDGE = 0.06f

    /** [image] holds normalised x, y, z per joint; true when any joint is near the image border. */
    fun nearEdge(image: FloatArray): Boolean {
        for (j in 0 until HandJoint.COUNT) {
            val x = image[j * 3]; val y = image[j * 3 + 1]
            if (x < EDGE || x > 1 - EDGE || y < EDGE || y > 1 - EDGE) return true
        }
        return false
    }

    fun timeoutNs(nearEdge: Boolean) = if (nearEdge) EDGE_NS else INSIDE_NS
}
