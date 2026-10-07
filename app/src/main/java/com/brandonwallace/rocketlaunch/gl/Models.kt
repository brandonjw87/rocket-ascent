package com.brandonwallace.rocketlaunch.gl

import android.opengl.Matrix
import com.brandonwallace.rocketlaunch.game.RocketSpec
import com.brandonwallace.rocketlaunch.game.UpgradeType
import kotlin.math.PI
import kotlin.math.pow

/** Shared unit meshes (one set per GL context). */
class UnitMeshes {
    val cylinder = MeshBuilder().cylinder(1f, 1f, 28).build()
    val cylinderLow = MeshBuilder().cylinder(1f, 1f, 12).build()
    val cone = MeshBuilder().lathe(listOf(1f to 0f, 0f to 1f), 24).build()
    val frustum = MeshBuilder().lathe(listOf(1f to 0f, 0.72f to 1f), 28).build()
    val bell = MeshBuilder().lathe(listOf(1f to 0f, 0.82f to 0.25f, 0.55f to 0.6f, 0.42f to 0.85f, 0.45f to 1f), 24, capBottom = false).build()
    val sphere = MeshBuilder().sphere(1f, 20, 14).build()
    val box = MeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f).build()
    val coin = MeshBuilder().lathe(listOf(1f to -0.11f, 1f to 0.11f), 24).lathe(listOf(0.78f to 0.11f, 0.78f to 0.13f), 24, capBottom = false).build()
    val rock = MeshBuilder().sphere(1f, 10, 7, bumpy = 0.45f, seed = 3).build()
    val rock2 = MeshBuilder().sphere(1f, 8, 6, bumpy = 0.6f, seed = 11).build()
    val fin = MeshBuilder().prism(listOf(0f to 0f, 1.25f to -0.55f, 1.3f to 0.15f, 0f to 1.9f), 0.12f).build()
    val tailFin = MeshBuilder().prism(listOf(0f to 0f, 1.6f to 0f, 1.9f to 1.6f, 1.1f to 1.6f), 0.15f).build()
    val wing = MeshBuilder().prism(listOf(-0.9f to 0f, 0.9f to 0f, 0.4f to 5f, -0.2f to 5f), 0.18f).build()

    fun release() {
        listOf(cylinder, cylinderLow, cone, frustum, bell, sphere, box, coin, rock, rock2, fin, tailFin, wing).forEach { it.release() }
    }
}

class Part(
    val mesh: Mesh, val group: Int, val local: FloatArray,
    val r: Float, val g: Float, val b: Float, val spec: Float, val glowBell: Boolean = false,
)

fun mat(
    tx: Float, ty: Float, tz: Float, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f,
    ry: Float = 0f, rz: Float = 0f, rx: Float = 0f, pre: FloatArray? = null,
): FloatArray {
    val m = FloatArray(16)
    Matrix.setIdentityM(m, 0)
    Matrix.translateM(m, 0, tx, ty, tz)
    if (ry != 0f) Matrix.rotateM(m, 0, ry, 0f, 1f, 0f)
    if (rz != 0f) Matrix.rotateM(m, 0, rz, 0f, 0f, 1f)
    if (rx != 0f) Matrix.rotateM(m, 0, rx, 1f, 0f, 0f)
    Matrix.scaleM(m, 0, sx, sy, sz)
    if (pre == null) return m
    val out = FloatArray(16)
    Matrix.multiplyMM(out, 0, pre, 0, m, 0)
    return out
}

/** Rocket geometry generated from the current upgrade levels. Group = stage index, -(b+1) = booster b, 100 = payload. */
class RocketModel(spec: RocketSpec, u: UnitMeshes) {
    companion object { const val PAYLOAD = 100 }

    val parts = ArrayList<Part>()
    val stageBase = FloatArray(spec.stages.size + 1)
    val boosterOffsets = ArrayList<FloatArray>()
    var totalLength = 0f
    private val owned = ArrayList<Mesh>()

    init {
        val eng = spec.level(UpgradeType.ENGINE)
        val aero = spec.level(UpgradeType.AERO)
        val hull = spec.level(UpgradeType.HULL)
        val heat = spec.level(UpgradeType.HEAT)
        val fins = spec.level(UpgradeType.FINS)
        val body = arrayOf(
            floatArrayOf(0.93f, 0.93f, 0.95f), floatArrayOf(0.86f, 0.88f, 0.92f), floatArrayOf(0.74f, 0.78f, 0.86f),
            floatArrayOf(0.56f, 0.61f, 0.71f), floatArrayOf(0.36f, 0.39f, 0.47f), floatArrayOf(0.20f, 0.21f, 0.26f),
        )[hull]
        val stripes = arrayOf(floatArrayOf(1f, 0.45f, 0.08f), floatArrayOf(0.15f, 0.45f, 1f), floatArrayOf(0.2f, 0.85f, 0.4f), floatArrayOf(0.7f, 0.3f, 0.95f))
        val finC = arrayOf(floatArrayOf(0.5f, 0.5f, 0.52f), floatArrayOf(0.85f, 0.18f, 0.14f), floatArrayOf(1f, 0.5f, 0.1f), floatArrayOf(0.1f, 0.75f, 0.9f), floatArrayOf(0.98f, 0.76f, 0.2f))[fins]
        val noseC = arrayOf(floatArrayOf(0.55f, 0.55f, 0.58f), floatArrayOf(0.85f, 0.15f, 0.12f), floatArrayOf(0.95f, 0.95f, 0.97f), floatArrayOf(0.15f, 0.35f, 0.9f), floatArrayOf(1f, 0.78f, 0.25f), floatArrayOf(0.92f, 0.94f, 0.98f))[aero]
        val dark = floatArrayOf(0.12f, 0.12f, 0.14f)
        val metal = floatArrayOf(0.32f, 0.29f, 0.27f)

        var y = 0f
        val n = spec.stages.size
        for (i in 0 until n) {
            val s = spec.stages[i]
            val r = s.radius
            val L = s.length
            stageBase[i] = y
            val bellH = 0.75f + 0.04f * eng
            val bellR = r * (0.5f + 0.05f * eng)
            parts += Part(u.bell, i, mat(0f, y, 0f, bellR, bellH, bellR), metal[0], metal[1], metal[2], 0.9f, glowBell = true)
            val tankY = y + bellH * 0.8f
            val tankH = L - bellH * 0.8f
            parts += Part(u.cylinder, i, mat(0f, tankY, 0f, r, tankH, r), body[0], body[1], body[2], 0.45f)
            parts += Part(u.cylinder, i, mat(0f, tankY, 0f, r * 1.012f, 0.18f, r * 1.012f), dark[0], dark[1], dark[2], 0.2f)
            val st = stripes[i % stripes.size]
            parts += Part(u.cylinder, i, mat(0f, tankY + tankH * 0.62f, 0f, r * 1.014f, 0.4f, r * 1.014f), st[0], st[1], st[2], 0.5f)
            parts += Part(u.cylinder, i, mat(0f, tankY + tankH * 0.62f - 0.6f, 0f, r * 1.014f, 0.12f, r * 1.014f), st[0], st[1], st[2], 0.5f)
            if (i < n - 1) parts += Part(u.cylinder, i, mat(0f, y + L - 0.32f, 0f, r * 1.008f, 0.32f, r * 1.008f), dark[0], dark[1], dark[2], 0.3f)
            if (i == 0) {
                val count = if (fins >= 2) 4 else 3
                val sc = 0.85f + 0.2f * fins
                for (k in 0 until count) {
                    val a = 360f * k / count + (if (count == 4) 45f else 90f)
                    parts += Part(u.fin, 0, mat(0f, tankY + 0.1f, 0f, ry = a, pre = null).let { base ->
                        val m2 = mat(r * 0.96f, 0f, 0f, sc, sc, 1f); val out = FloatArray(16); Matrix.multiplyMM(out, 0, base, 0, m2, 0); out
                    }, finC[0], finC[1], finC[2], 0.6f)
                }
            }
            y += L
        }
        stageBase[n] = y

        // payload: capsule + window + nose cone
        val rl = spec.stages.last().radius
        parts += Part(u.frustum, PAYLOAD, mat(0f, y, 0f, rl, 1.3f, rl), body[0], body[1], body[2], 0.5f)
        parts += Part(u.sphere, PAYLOAD, mat(0f, y + 0.72f, rl * 0.86f, 0.24f, 0.24f, 0.08f), 0.1f, 0.25f, 0.55f, 1.2f)
        y += 1.3f
        val noseH = 1.3f + 0.35f * aero
        val sharp = 1.2f + 0.5f * aero
        val prof = (0..10).map { k -> val t = k / 10f; (rl * 0.72f * (1f - t.pow(sharp)).coerceAtLeast(0f).pow(0.55f)) to (t * noseH) }
        val noseMesh = MeshBuilder().lathe(prof, 24).build().also { owned += it }
        val nc = if (heat >= 1) floatArrayOf(noseC[0] * 0.75f, noseC[1] * 0.75f, noseC[2] * 0.75f) else noseC
        parts += Part(noseMesh, PAYLOAD, mat(0f, y, 0f), nc[0], nc[1], nc[2], if (aero == 5) 1.4f else 0.6f)
        if (heat >= 1) parts += Part(u.sphere, PAYLOAD, mat(0f, y + noseH * 0.93f, 0f, 0.12f + 0.03f * heat, 0.16f, 0.12f + 0.03f * heat), 0.08f, 0.08f, 0.09f, 0.3f)
        if (heat >= 2) parts += Part(u.cylinder, 0, mat(0f, 0.55f, 0f, spec.stages[0].radius * 1.03f, 0.25f, spec.stages[0].radius * 1.03f), 0.1f, 0.1f, 0.1f, 0.2f)
        totalLength = y + noseH

        // boosters
        val b = spec.booster
        if (b != null) {
            val r0 = spec.stages[0].radius
            val d = r0 + b.radius + 0.06f
            for (k in 0 until spec.boosterCount) {
                val ang = when (k) { 0 -> 180f; 1 -> 0f; 2 -> 90f; else -> 270f }
                val base = mat(0f, 0f, 0f, ry = ang)
                val off = mat(d, 0f, 0f, pre = base)
                boosterOffsets += off
                val g = -(k + 1)
                fun add(mesh: Mesh, m: FloatArray, c: FloatArray, sp: Float, glow: Boolean = false) {
                    val out = FloatArray(16); Matrix.multiplyMM(out, 0, off, 0, m, 0); parts += Part(mesh, g, out, c[0], c[1], c[2], sp, glow)
                }
                val br = b.radius; val bl = b.length
                add(u.bell, mat(0f, 0f, 0f, br * 0.75f, 0.55f, br * 0.75f), metal, 0.9f, glow = true)
                add(u.cylinder, mat(0f, 0.45f, 0f, br, bl - 1.4f, br), floatArrayOf(0.95f, 0.95f, 0.96f), 0.4f)
                add(u.cylinder, mat(0f, 0.45f + (bl - 1.4f) * 0.7f, 0f, br * 1.02f, 0.35f, br * 1.02f), floatArrayOf(1f, 0.45f, 0.08f), 0.5f)
                add(u.cone, mat(0f, bl - 0.95f, 0f, br, 1.2f, br), floatArrayOf(0.95f, 0.95f, 0.96f), 0.4f)
                add(u.box, mat(-(b.radius + 0.03f), 1.4f, 0f, 0.12f, 0.15f, 0.12f), dark, 0.2f)
                add(u.box, mat(-(b.radius + 0.03f), bl - 2f, 0f, 0.12f, 0.15f, 0.12f), dark, 0.2f)
            }
        }
    }

    fun release() { owned.forEach { it.release() }; owned.clear() }
}
