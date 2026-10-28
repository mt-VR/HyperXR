package com.hyperxr.rendering

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Small GLES 3.0 helpers. All calls must happen on the GL thread. */
object Gl {
    private const val TAG = "HyperMagic-GL"

    fun compile(vs: String, fs: String, name: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                GLES30.glDeleteShader(s)
                throw IllegalStateException("Shader $name failed: $log")
            }
            return s
        }
        val p = GLES30.glCreateProgram()
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glBindAttribLocation(p, 1, "aNormal")
        GLES30.glBindAttribLocation(p, 2, "aUv")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        if (ok[0] == 0) throw IllegalStateException("Program $name link failed: ${GLES30.glGetProgramInfoLog(p)}")
        return p
    }

    fun floatBuffer(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    fun intBuffer(n: Int): IntBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

    fun genTexture(): Int { val t = IntArray(1); GLES30.glGenTextures(1, t, 0); return t[0] }

    fun uploadBitmap(bitmap: Bitmap, existing: Int = 0, mipmap: Boolean = false): Int {
        val tex = if (existing != 0) existing else genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (mipmap) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        if (mipmap) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return tex
    }

    fun repeatTexture(tex: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
    }

    fun externalTexture(): Int {
        val t = genTexture()
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t
    }

    fun deleteTexture(t: Int) { if (t != 0) GLES30.glDeleteTextures(1, intArrayOf(t), 0) }

    fun checkError(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
    }
}

/** Colour (+ optional depth) render target. */
class RenderTarget(val width: Int, val height: Int, depth: Boolean = true, val format: Int = GLES30.GL_RGBA8, externalTexture: Int = 0) {
    val fbo: Int
    val texture: Int
    private val depthRb: Int
    private val ownsTexture = externalTexture == 0

    init {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        texture = if (externalTexture != 0) externalTexture else Gl.genTexture().also { t ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            val (fmt, type) = when (format) {
                GLES30.GL_R8 -> GLES30.GL_RED to GLES30.GL_UNSIGNED_BYTE
                else -> GLES30.GL_RGBA to GLES30.GL_UNSIGNED_BYTE
            }
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, format, width, height, 0, fmt, type, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        if (depth) {
            GLES30.glGenRenderbuffers(1, ids, 0); depthRb = ids[0]
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthRb)
        } else depthRb = 0
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete: 0x${Integer.toHexString(status)}" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (ownsTexture) Gl.deleteTexture(texture)
        if (depthRb != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
    }
}
