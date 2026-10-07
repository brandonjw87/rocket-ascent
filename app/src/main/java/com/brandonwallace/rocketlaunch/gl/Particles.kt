package com.brandonwallace.rocketlaunch.gl

import java.nio.FloatBuffer

/** Structure-of-arrays particle pool. kind: 0 smoke (alpha), 1 flame (additive), 2 cloud (alpha), 3 spark (additive). */
class Particles(val max: Int = 3600) {
    val px = FloatArray(max); val py = FloatArray(max); val pz = FloatArray(max)
    val vx = FloatArray(max); val vy = FloatArray(max); val vz = FloatArray(max)
    val life = FloatArray(max); val maxLife = FloatArray(max)
    val s0 = FloatArray(max); val s1 = FloatArray(max)
    val cr = FloatArray(max); val cg = FloatArray(max); val cb = FloatArray(max); val ca = FloatArray(max)
    val drag = FloatArray(max); val seed = FloatArray(max); val rot = FloatArray(max); val rotV = FloatArray(max)
    val grav = FloatArray(max)
    val kind = IntArray(max)
    var count = 0
    private var cursor = 0

    fun clear() { count = 0 }

    fun emit(
        x: Float, y: Float, z: Float, vx0: Float, vy0: Float, vz0: Float, lifeS: Float,
        size0: Float, size1: Float, r: Float, g: Float, b: Float, a: Float, k: Int,
        dragK: Float = 0f, gravity: Float = 0f, sd: Float = 0f, spin: Float = 0f,
    ) {
        val i: Int
        if (count < max) i = count++ else { i = cursor; cursor = (cursor + 1) % max }
        px[i] = x; py[i] = y; pz[i] = z; vx[i] = vx0; vy[i] = vy0; vz[i] = vz0
        life[i] = lifeS; maxLife[i] = lifeS; s0[i] = size0; s1[i] = size1
        cr[i] = r; cg[i] = g; cb[i] = b; ca[i] = a; kind[i] = k; drag[i] = dragK; grav[i] = gravity
        seed[i] = sd; rot[i] = sd * 6.28f; rotV[i] = spin
    }

    fun update(dt: Float, scroll: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) { count--; copy(count, i); continue }
            val k = 1f / (1f + drag[i] * dt)
            vx[i] *= k; vy[i] = (vy[i] - grav[i] * dt) * k; vz[i] *= k
            px[i] += vx[i] * dt
            py[i] += (vy[i] - scroll) * dt
            pz[i] += vz[i] * dt
            rot[i] += rotV[i] * dt
            i++
        }
    }

    private fun copy(from: Int, to: Int) {
        if (from == to) return
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from]; vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from]
        life[to] = life[from]; maxLife[to] = maxLife[from]; s0[to] = s0[from]; s1[to] = s1[from]
        cr[to] = cr[from]; cg[to] = cg[from]; cb[to] = cb[from]; ca[to] = ca[from]; kind[to] = kind[from]
        drag[to] = drag[from]; seed[to] = seed[from]; rot[to] = rot[from]; rotV[to] = rotV[from]; grav[to] = grav[from]
    }

    /** Writes instances (12 floats each) of the requested blend class; returns how many were written. */
    fun write(buf: FloatBuffer, additive: Boolean, light: Float): Int {
        var n = 0
        for (i in 0 until count) {
            val k = kind[i]
            val add = k == 1 || k == 3
            if (add != additive) continue
            val t = 1f - life[i] / maxLife[i]
            val size = s0[i] + (s1[i] - s0[i]) * t
            val a = when (k) {
                0 -> ca[i] * (t / 0.08f).coerceAtMost(1f) * (1f - t) * (1f - t)
                1 -> ca[i] * (1f - t)
                else -> ca[i] * (1f - t * t)
            }
            val lm = if (add) 1f else light
            buf.put(px[i]); buf.put(py[i]); buf.put(pz[i]); buf.put(size)
            buf.put(cr[i] * lm); buf.put(cg[i] * lm); buf.put(cb[i] * lm); buf.put(a)
            buf.put(k.toFloat()); buf.put(seed[i]); buf.put(rot[i]); buf.put(0f)
            n++
        }
        return n
    }
}
