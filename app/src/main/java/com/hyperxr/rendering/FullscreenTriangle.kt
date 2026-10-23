package com.hyperxr.rendering

import android.opengl.GLES30

/** Shared fullscreen triangle vertex data (attribute 0). */
object FullscreenTriangle {
    private var vbo = 0
    fun bind() {
        if (vbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
            val v = Gl.floatBuffer(9).put(floatArrayOf(-1f, -1f, 0f, 3f, -1f, 0f, -1f, 3f, 0f)).also { it.position(0) }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 36, v, GLES30.GL_STATIC_DRAW)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
    }

    fun reset() { vbo = 0 }
}
