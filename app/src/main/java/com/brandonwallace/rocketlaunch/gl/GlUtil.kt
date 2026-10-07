package com.brandonwallace.rocketlaunch.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ShaderProgram(vs: String, fs: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, v)
        GLES30.glAttachShader(id, f)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("RocketGL", "link: " + GLES30.glGetProgramInfoLog(id))
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
    }

    fun use() = GLES30.glUseProgram(id)
    fun loc(name: String): Int = locs.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("RocketGL", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
}

fun floatBuffer(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

/** Interleaved position(3) + normal(3), non-indexed triangles. */
class Mesh(data: FloatArray) {
    val count = data.size / 6
    private val vao = IntArray(1)
    private val vbo = IntArray(1)

    init {
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val fb = floatBuffer(data.size).put(data)
        fb.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count)
    }

    fun release() {
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }
}

/** Procedural geometry. */
class MeshBuilder {
    private var d = FloatArray(4096)
    private var n = 0

    private fun put(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        if (n + 6 > d.size) d = d.copyOf(d.size * 2)
        d[n++] = x; d[n++] = y; d[n++] = z; d[n++] = nx; d[n++] = ny; d[n++] = nz
    }

    fun tri(a: FloatArray, b: FloatArray, c: FloatArray, na: FloatArray, nb: FloatArray, nc: FloatArray) {
        put(a[0], a[1], a[2], na[0], na[1], na[2])
        put(b[0], b[1], b[2], nb[0], nb[1], nb[2])
        put(c[0], c[1], c[2], nc[0], nc[1], nc[2])
    }

    fun flatTri(a: FloatArray, b: FloatArray, c: FloatArray) {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        nx /= l; ny /= l; nz /= l
        val nn = floatArrayOf(nx, ny, nz)
        tri(a, b, c, nn, nn, nn)
    }

    /** Surface of revolution around Y. profile = (radius, y) from bottom to top. */
    fun lathe(profile: List<Pair<Float, Float>>, seg: Int, capBottom: Boolean = true, capTop: Boolean = true): MeshBuilder {
        for (i in 0 until profile.size - 1) {
            val (r0, y0) = profile[i]
            val (r1, y1) = profile[i + 1]
            var nr = y1 - y0; var ny = -(r1 - r0)
            val l = sqrt(nr * nr + ny * ny).coerceAtLeast(1e-6f); nr /= l; ny /= l
            for (s in 0 until seg) {
                val a0 = (2 * PI * s / seg).toFloat(); val a1 = (2 * PI * (s + 1) / seg).toFloat()
                val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                val p00 = floatArrayOf(r0 * c0, y0, r0 * s0); val p01 = floatArrayOf(r0 * c1, y0, r0 * s1)
                val p10 = floatArrayOf(r1 * c0, y1, r1 * s0); val p11 = floatArrayOf(r1 * c1, y1, r1 * s1)
                val n0 = floatArrayOf(nr * c0, ny, nr * s0); val n1 = floatArrayOf(nr * c1, ny, nr * s1)
                tri(p00, p10, p11, n0, n0, n1)
                tri(p00, p11, p01, n0, n1, n1)
            }
        }
        if (capBottom && profile.first().first > 0f) disc(profile.first().first, profile.first().second, -1f, seg)
        if (capTop && profile.last().first > 0f) disc(profile.last().first, profile.last().second, 1f, seg)
        return this
    }

    private fun disc(r: Float, y: Float, ny: Float, seg: Int) {
        val nn = floatArrayOf(0f, ny, 0f)
        val c = floatArrayOf(0f, y, 0f)
        for (s in 0 until seg) {
            val a0 = (2 * PI * s / seg).toFloat(); val a1 = (2 * PI * (s + 1) / seg).toFloat()
            tri(c, floatArrayOf(r * cos(a0), y, r * sin(a0)), floatArrayOf(r * cos(a1), y, r * sin(a1)), nn, nn, nn)
        }
    }

    fun cylinder(r: Float = 1f, h: Float = 1f, seg: Int = 24) = lathe(listOf(r to 0f, r to h), seg)

    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float): MeshBuilder {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2; val y0 = cy - sy / 2; val y1 = cy + sy / 2; val z0 = cz - sz / 2; val z1 = cz + sz / 2
        fun q(a: FloatArray, b: FloatArray, c: FloatArray, e: FloatArray, nrm: FloatArray) { tri(a, b, c, nrm, nrm, nrm); tri(a, c, e, nrm, nrm, nrm) }
        q(floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1), floatArrayOf(0f, 0f, 1f))
        q(floatArrayOf(x1, y0, z0), floatArrayOf(x0, y0, z0), floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(0f, 0f, -1f))
        q(floatArrayOf(x1, y0, z1), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(1f, 0f, 0f))
        q(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y1, z0), floatArrayOf(-1f, 0f, 0f))
        q(floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0), floatArrayOf(0f, 1f, 0f))
        q(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1), floatArrayOf(0f, -1f, 0f))
        return this
    }

    /** UV sphere (optionally displaced for rocks). */
    fun sphere(r: Float = 1f, su: Int = 20, sv: Int = 14, bumpy: Float = 0f, seed: Int = 0): MeshBuilder {
        fun rad(u: Int, v: Int): Float {
            if (bumpy == 0f) return r
            val uu = if (u == su) 0 else u
            val h = ((uu * 73856093) xor (v * 19349663) xor (seed * 83492791)) and 0xffff
            val pole = if (v == 0 || v == sv) 0f else 1f
            return r * (1f + bumpy * pole * (h / 65535f - 0.5f))
        }
        fun p(u: Int, v: Int): FloatArray {
            val th = (2 * PI * u / su).toFloat(); val ph = (PI * v / sv).toFloat()
            val rr = rad(u, v)
            return floatArrayOf(rr * sin(ph) * cos(th), rr * cos(ph), rr * sin(ph) * sin(th))
        }
        for (v in 0 until sv) for (u in 0 until su) {
            val a = p(u, v); val b = p(u + 1, v); val c = p(u + 1, v + 1); val e = p(u, v + 1)
            if (bumpy == 0f) {
                fun nrm(q: FloatArray) = floatArrayOf(q[0] / r, q[1] / r, q[2] / r)
                tri(a, c, b, nrm(a), nrm(c), nrm(b)); tri(a, e, c, nrm(a), nrm(e), nrm(c))
            } else {
                flatTri(a, c, b); flatTri(a, e, c)
            }
        }
        return this
    }

    /** Convex polygon in the XY plane extruded along Z (fins, wings). */
    fun prism(poly: List<Pair<Float, Float>>, thickness: Float): MeshBuilder {
        var area = 0f
        for (i in poly.indices) { val (x0, y0) = poly[i]; val (x1, y1) = poly[(i + 1) % poly.size]; area += x0 * y1 - x1 * y0 }
        val pts = if (area < 0) poly.reversed() else poly
        val h = thickness / 2
        val nf = floatArrayOf(0f, 0f, 1f); val nb = floatArrayOf(0f, 0f, -1f)
        for (i in 1 until pts.size - 1) {
            val a = pts[0]; val b = pts[i]; val c = pts[i + 1]
            tri(floatArrayOf(a.first, a.second, h), floatArrayOf(b.first, b.second, h), floatArrayOf(c.first, c.second, h), nf, nf, nf)
            tri(floatArrayOf(a.first, a.second, -h), floatArrayOf(c.first, c.second, -h), floatArrayOf(b.first, b.second, -h), nb, nb, nb)
        }
        for (i in pts.indices) {
            val (x0, y0) = pts[i]; val (x1, y1) = pts[(i + 1) % pts.size]
            var nx = y1 - y0; var ny = -(x1 - x0)
            val l = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-6f); nx /= l; ny /= l
            val nn = floatArrayOf(nx, ny, 0f)
            val a = floatArrayOf(x0, y0, h); val b = floatArrayOf(x1, y1, h); val c = floatArrayOf(x1, y1, -h); val e = floatArrayOf(x0, y0, -h)
            tri(a, e, c, nn, nn, nn); tri(a, c, b, nn, nn, nn)
        }
        return this
    }

    fun build(): Mesh = Mesh(d.copyOf(n))
}
