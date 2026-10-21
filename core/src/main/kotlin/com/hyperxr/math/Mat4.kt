package com.hyperxr.math

import kotlin.math.tan

/** Column-major 4x4 matrix helpers operating on FloatArray(16), compatible with GLES uniforms. */
object Mat4 {
    fun identity(m: FloatArray): FloatArray {
        m.fill(0f); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return m
    }

    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray): FloatArray {
        // out may not alias a or b
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
            }
        }
        return out
    }

    /** Builds T * R * S. */
    fun compose(p: Vec3, q: Quat, sx: Float, sy: Float, sz: Float, out: FloatArray): FloatArray {
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        out[0] = (1 - (yy + zz)) * sx; out[1] = (xy + wz) * sx; out[2] = (xz - wy) * sx; out[3] = 0f
        out[4] = (xy - wz) * sy; out[5] = (1 - (xx + zz)) * sy; out[6] = (yz + wx) * sy; out[7] = 0f
        out[8] = (xz + wy) * sz; out[9] = (yz - wx) * sz; out[10] = (1 - (xx + yy)) * sz; out[11] = 0f
        out[12] = p.x; out[13] = p.y; out[14] = p.z; out[15] = 1f
        return out
    }

    /** Inverse of a rigid transform (rotation + translation only). */
    fun invertRigid(m: FloatArray, out: FloatArray): FloatArray {
        out[0] = m[0]; out[1] = m[4]; out[2] = m[8]; out[3] = 0f
        out[4] = m[1]; out[5] = m[5]; out[6] = m[9]; out[7] = 0f
        out[8] = m[2]; out[9] = m[6]; out[10] = m[10]; out[11] = 0f
        out[12] = -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14])
        out[13] = -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14])
        out[14] = -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14])
        out[15] = 1f
        return out
    }

    /** Asymmetric frustum from tangent angles, as used by OpenXR XrFovf and lens-based stereo. */
    fun frustumTan(tanLeft: Float, tanRight: Float, tanDown: Float, tanUp: Float, near: Float, far: Float, out: FloatArray): FloatArray {
        val w = tanRight - tanLeft
        val h = tanUp - tanDown
        out.fill(0f)
        out[0] = 2f / w
        out[5] = 2f / h
        out[8] = (tanRight + tanLeft) / w
        out[9] = (tanUp + tanDown) / h
        out[10] = -(far + near) / (far - near)
        out[11] = -1f
        out[14] = -(2f * far * near) / (far - near)
        return out
    }

    fun perspective(fovYRadians: Float, aspect: Float, near: Float, far: Float, out: FloatArray): FloatArray {
        val t = tan(fovYRadians / 2f)
        return frustumTan(-t * aspect, t * aspect, -t, t, near, far, out)
    }

    fun transformPoint(m: FloatArray, v: Vec3, out: Vec3 = Vec3()): Vec3 = out.set(
        m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12],
        m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13],
        m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14],
    )
}
