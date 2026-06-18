package com.example.phantomtrackerxr_final

import androidx.xr.runtime.math.Pose

data class Matrix4x4(
    var m00: Float = 1f, var m01: Float = 0f, var m02: Float = 0f, var m03: Float = 0f,
    var m10: Float = 0f, var m11: Float = 1f, var m12: Float = 0f, var m13: Float = 0f,
    var m20: Float = 0f, var m21: Float = 0f, var m22: Float = 1f, var m23: Float = 0f,
    var m30: Float = 0f, var m31: Float = 0f, var m32: Float = 0f, var m33: Float = 1f
)

fun Pose.toMatrix4x4(outMatrix: Matrix4x4? = null): Matrix4x4 {
    val m = outMatrix ?: Matrix4x4()

    val r = rotation
    val t = translation

    val x = r.x
    val y = r.y
    val z = r.z
    val w = r.w

    val xx = x * x; val xy = x * y; val xz = x * z; val xw = x * w
    val yy = y * y; val yz = y * z; val yw = y * w
    val zz = z * z; val zw = z * w

    m.m00 = 1 - 2 * (yy + zz)
    m.m10 = 2 * (xy + zw)
    m.m20 = 2 * (xz - yw)
    m.m30 = 0f

    m.m01 = 2 * (xy - zw)
    m.m11 = 1 - 2 * (xx + zz)
    m.m21 = 2 * (yz + xw)
    m.m31 = 0f

    m.m02 = 2 * (xz + yw)
    m.m12 = 2 * (yz - xw)
    m.m22 = 1 - 2 * (xx + yy)
    m.m32 = 0f

    m.m03 = t.x
    m.m13 = t.y
    m.m23 = t.z
    m.m33 = 1f

    return m
}
