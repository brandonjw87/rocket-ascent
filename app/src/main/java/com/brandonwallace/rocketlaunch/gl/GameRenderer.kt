package com.brandonwallace.rocketlaunch.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.brandonwallace.rocketlaunch.audio.SoundEngine
import com.brandonwallace.rocketlaunch.game.EntityKind
import com.brandonwallace.rocketlaunch.game.Fx
import com.brandonwallace.rocketlaunch.game.GameEngine
import com.brandonwallace.rocketlaunch.game.Phase
import com.brandonwallace.rocketlaunch.game.Physics
import com.brandonwallace.rocketlaunch.game.World
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameRenderer(private val engine: GameEngine, private val sound: SoundEngine) : GLSurfaceView.Renderer {
    private lateinit var lit: ShaderProgram
    private lateinit var sky: ShaderProgram
    private lateinit var bb: ShaderProgram
    private lateinit var u: UnitMeshes
    private var rocket: RocketModel? = null
    private var builtVersion = -1
    private val particles = Particles()
    private val rnd = Random(42)

    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private val mvp = FloatArray(16); private val tmp = FloatArray(16); private val tmp2 = FloatArray(16)
    private val rocketBase = FloatArray(16); private val vec = FloatArray(4); private val outV = FloatArray(4)
    private var width = 1; private var height = 1
    private var lastNanos = 0L

    // GL objects
    private val skyVao = IntArray(1); private val skyVbo = IntArray(1)
    private val bbVao = IntArray(1); private val bbCorner = IntArray(1); private val bbInst = IntArray(1)
    private val instBuf = floatBuffer(4800 * 12)

    // uniform locations
    private var uMVP = 0; private var uModel = 0; private var uColor = 0; private var uEm = 0; private var uSpec = 0
    private var uLight = 0; private var uSkyAmb = 0; private var uGroundAmb = 0; private var uCam = 0; private var uFogC = 0; private var uFogD = 0

    // smoothed camera
    private var camDist = 20f; private var camPitch = 0.15f; private var camYaw = 0.3f; private var camTX = 0f; private var camTY = 5f
    private val eye = FloatArray(3)
    private val sun = floatArrayOf(-0.45f, 0.55f, 0.70f).also { val l = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]); for (i in 0..2) it[i] /= l }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        lit = ShaderProgram(Shaders.LIT_VS, Shaders.LIT_FS)
        sky = ShaderProgram(Shaders.SKY_VS, Shaders.SKY_FS)
        bb = ShaderProgram(Shaders.BB_VS, Shaders.BB_FS)
        uMVP = lit.loc("uMVP"); uModel = lit.loc("uModel"); uColor = lit.loc("uColor"); uEm = lit.loc("uEmissive"); uSpec = lit.loc("uSpec")
        uLight = lit.loc("uLightDir"); uSkyAmb = lit.loc("uSkyAmb"); uGroundAmb = lit.loc("uGroundAmb"); uCam = lit.loc("uCamPos")
        uFogC = lit.loc("uFogColor"); uFogD = lit.loc("uFogDensity")
        u = UnitMeshes()
        rocket = null; builtVersion = -1

        GLES30.glGenVertexArrays(1, skyVao, 0); GLES30.glGenBuffers(1, skyVbo, 0)
        GLES30.glBindVertexArray(skyVao[0]); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, skyVbo[0])
        val tri = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.size * 4, floatBuffer(tri.size).put(tri).position(0), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)

        GLES30.glGenVertexArrays(1, bbVao, 0); GLES30.glGenBuffers(1, bbCorner, 0); GLES30.glGenBuffers(1, bbInst, 0)
        GLES30.glBindVertexArray(bbVao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bbCorner[0])
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, floatBuffer(quad.size).put(quad).position(0), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bbInst[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, instBuf.capacity() * 4, null, GLES30.GL_STREAM_DRAW)
        for (a in 1..3) { GLES30.glEnableVertexAttribArray(a); GLES30.glVertexAttribDivisor(a, 1) }
        setInstancePointers(0)
        GLES30.glBindVertexArray(0)

        GLES30.glClearColor(0.02f, 0.03f, 0.08f, 1f)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        lastNanos = 0L
    }

    private fun setInstancePointers(firstInstance: Int) {
        val base = firstInstance * 48
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 48, base)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, 48, base + 16)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 48, base + 32)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        synchronized(engine.lock) {
            engine.update(dt)
            if (engine.specVersion != builtVersion) {
                rocket?.release()
                rocket = RocketModel(engine.spec, u)
                builtVersion = engine.specVersion
            }
            val alt = engine.flight?.alt ?: 0.0
            val atm = (Physics.density(alt) / 1.225).toFloat()
            handleFx(atm)
            emitContinuous(dt, alt, atm)
            particles.update(dt, engine.scroll)
            setupCamera(dt, alt)
            sound.enabled = engine.save.value.soundOn
            sound.engineLevel = engine.engineGlow
            sound.atmosphere = atm

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            drawSky(alt)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(true)
            drawScene(alt, atm)
            drawBillboards(atm)
        }
    }

    // ---------------------------------------------------------------- camera

    private fun remainingLength(): Float {
        val r = rocket ?: return 10f
        val first = engine.flight?.stage ?: 0
        return r.totalLength - r.stageBase[first]
    }

    private fun setupCamera(dt: Float, alt: Double) {
        val L = remainingLength()
        val t = engine.time
        var tDist: Float; var tPitch: Float; var tYaw: Float; var tX: Float; var tY: Float
        when (engine.phase) {
            Phase.MENU -> { tDist = 12f + L * 1.0f; tPitch = 0.10f; tYaw = t * 0.18f; tX = 0f; tY = L * 0.42f }
            Phase.HANGAR -> { tDist = 11f + L * 1.05f; tPitch = 0.05f; tYaw = 0.5f + t * 0.22f; tX = 0f; tY = L * 0.08f }
            Phase.COUNTDOWN -> { tDist = 11f + L * 0.85f; tPitch = 0.20f; tYaw = 0.45f + 0.03f * sin(t); tX = 0f; tY = L * 0.5f }
            else -> {
                val km = alt / 1000.0
                val down = smooth(ln(max(km, 1.0)) .toFloat(), ln(20f), ln(400f))
                val up = smooth(km.toFloat(), 30_000f, 150_000f)
                tPitch = 0.18f - 0.62f * down + 0.72f * up
                tDist = 16f + L * 0.9f + min(abs(engine.scroll) * 0.25f, 60f)
                tYaw = 0.3f + 0.05f * sin(t * 0.2f)
                tX = engine.laneX * 0.75f; tY = L * 0.55f
            }
        }
        val k = min(1f, dt * (if (engine.phase == Phase.FLIGHT) 2.2f else 3f))
        camDist += (tDist - camDist) * k
        camPitch += (tPitch - camPitch) * k
        var dy = tYaw - camYaw
        if (engine.phase == Phase.MENU || engine.phase == Phase.HANGAR) dy = tYaw - camYaw // continuous orbit
        camYaw += dy * k
        camTX += (tX - camTX) * min(1f, dt * 6f)
        camTY += (tY - camTY) * k

        val fx = sin(camYaw) * cos(camPitch); val fy = sin(camPitch); val fz = -cos(camYaw) * cos(camPitch)
        val sh = engine.shake * 0.22f
        eye[0] = camTX - fx * camDist + sin(t * 37f) * sh
        eye[1] = camTY - fy * camDist + sin(t * 41f + 1f) * sh
        eye[2] = 0f - fz * camDist + sin(t * 29f + 2f) * sh * 0.5f
        // never dip below the ground
        val groundLocal = (-alt).toFloat() + 1.5f
        if (eye[1] < groundLocal) eye[1] = groundLocal
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], camTX + sin(t * 23f) * sh * 0.4f, camTY, 0f, 0f, 1f, 0f)
        val aspect = width.toFloat() / height
        val fov = if (engine.phase == Phase.FLIGHT) 68f else 60f
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.4f, 4000f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
    }

    private fun smooth(x: Float, a: Float, b: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    // ---------------------------------------------------------------- sky

    private fun drawSky(alt: Double) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)
        sky.use()
        val rotView = view.copyOf(); rotView[12] = 0f; rotView[13] = 0f; rotView[14] = 0f
        Matrix.multiplyMM(tmp, 0, proj, 0, rotView, 0)
        Matrix.invertM(tmp2, 0, tmp, 0)
        GLES30.glUniformMatrix4fv(sky.loc("uInvVP"), 1, false, tmp2, 0)
        val camAltM = max(2.0, alt + eye[1])
        val hkm = camAltM / 1000.0
        val rkm = Physics.R_EARTH / 1000.0
        GLES30.glUniform1f(sky.loc("uAlt"), hkm.toFloat())
        GLES30.glUniform1f(sky.loc("uAtm"), (Physics.density(camAltM) / 1.225).toFloat())
        GLES30.glUniform3f(sky.loc("uSun"), sun[0], sun[1], sun[2])
        GLES30.glUniform1f(sky.loc("uR"), rkm.toFloat())
        GLES30.glUniform1f(sky.loc("uC0"), (hkm * (2 * rkm + hkm)).toFloat())
        val moonR = (Physics.MOON_RADIUS / 1000.0)
        val dist = max((Physics.MOON_CENTER_DIST - Physics.R_EARTH - alt) / 1000.0, moonR * 1.12)
        val el = 0.50; val az = 0.3
        GLES30.glUniform3f(sky.loc("uMoonPos"), (sin(az) * cos(el) * dist).toFloat(), (sin(el) * dist).toFloat(), (-cos(az) * cos(el) * dist).toFloat())
        GLES30.glUniform1f(sky.loc("uMoonR"), moonR.toFloat())
        GLES30.glUniform1f(sky.loc("uTime"), engine.time)
        GLES30.glBindVertexArray(skyVao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    // ---------------------------------------------------------------- lit scene

    private fun draw(mesh: Mesh, m: FloatArray, r: Float, g: Float, b: Float, spec: Float = 0.3f, er: Float = 0f, eg: Float = 0f, eb: Float = 0f) {
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES30.glUniform3f(uColor, r, g, b)
        GLES30.glUniform3f(uEm, er, eg, eb)
        GLES30.glUniform1f(uSpec, spec)
        mesh.draw()
    }

    private fun drawScene(alt: Double, atm: Float) {
        lit.use()
        val sa = sqrt(atm)
        GLES30.glUniform3f(uLight, sun[0], sun[1], sun[2])
        GLES30.glUniform3f(uSkyAmb, 0.07f + 0.30f * sa, 0.08f + 0.38f * sa, 0.12f + 0.50f * sa)
        GLES30.glUniform3f(uGroundAmb, 0.06f + 0.20f * sa, 0.08f + 0.16f * sa, 0.13f + 0.07f * sa)
        GLES30.glUniform3f(uCam, eye[0], eye[1], eye[2])
        GLES30.glUniform3f(uFogC, 0.72f * atm, 0.84f * atm, 1.0f * atm)
        GLES30.glUniform1f(uFogD, 0.0009f * atm)

        if (alt < 3000) drawPad((-alt).toFloat())
        drawRocket()
        drawSpent()
        drawEntities()
    }

    private fun drawPad(y0: Float) {
        val L = rocket?.totalLength ?: 10f
        val concrete = 0.52f
        draw(u.box, mat(0f, y0 - 0.15f, 0f, 70f, 0.3f, 70f), concrete * 0.8f, concrete * 0.8f, concrete * 0.78f, 0.05f)
        draw(u.box, mat(0f, y0 - 0.7f, 0f, 14f, 1.4f, 14f), concrete, concrete, concrete * 0.97f, 0.1f)
        draw(u.box, mat(0f, y0 + 0.01f, 0f, 3.2f, 0.06f, 14.2f), 0.12f, 0.12f, 0.13f, 0.05f)
        // stripes
        for (s in -1..1 step 2) draw(u.box, mat(s * 6.5f, y0 + 0.02f, 0f, 0.4f, 0.05f, 14f), 0.95f, 0.75f, 0.1f, 0.1f)
        // launch tower
        val tx = -5.2f; val h = L + 5f
        val red = floatArrayOf(0.62f, 0.16f, 0.1f)
        for (cx in listOf(-1.1f, 1.1f)) for (cz in listOf(-1.1f, 1.1f)) draw(u.box, mat(tx + cx, y0 + h / 2, cz, 0.3f, h, 0.3f), red[0], red[1], red[2], 0.2f)
        var yy = 2.5f
        while (yy < h) {
            draw(u.box, mat(tx, y0 + yy, -1.1f, 2.4f, 0.2f, 0.2f), red[0], red[1], red[2], 0.2f)
            draw(u.box, mat(tx, y0 + yy, 1.1f, 2.4f, 0.2f, 0.2f), red[0], red[1], red[2], 0.2f)
            draw(u.box, mat(tx - 1.1f, y0 + yy, 0f, 0.2f, 0.2f, 2.4f), red[0], red[1], red[2], 0.2f)
            draw(u.box, mat(tx - 1.1f, y0 + yy - 1.2f, 0f, 0.12f, 2.6f, 0.12f, rx = 45f), red[0], red[1], red[2], 0.2f)
            yy += 2.5f
        }
        draw(u.box, mat(tx, y0 + h + 0.8f, 0f, 0.15f, 1.6f, 0.15f), 0.8f, 0.8f, 0.8f, 0.4f)
        // service arms swing away at ignition
        val swing = when (engine.phase) {
            Phase.COUNTDOWN -> smooth(1.6f - engine.countdown, 0f, 1.2f) * 70f
            Phase.FLIGHT, Phase.RESULTS -> 70f
            else -> 0f
        }
        for (frac in listOf(0.55f, 0.86f)) {
            val armLen = abs(tx) - 1.2f
            val m = mat(tx + 1.2f, y0 + L * frac, 0f, ry = swing)
            draw(u.box, mat(armLen / 2, 0f, 0f, armLen, 0.35f, 0.9f, pre = m), 0.75f, 0.75f, 0.78f, 0.3f)
        }
        // floodlights + propellant tanks
        for ((lx, lz) in listOf(-12f to -12f, 12f to -12f, -12f to 12f, 12f to 12f)) {
            draw(u.box, mat(lx, y0 + 4f, lz, 0.25f, 8f, 0.25f), 0.4f, 0.4f, 0.42f, 0.2f)
            draw(u.box, mat(lx, y0 + 8.2f, lz, 1.2f, 0.5f, 0.5f), 0.3f, 0.3f, 0.3f, 0.3f, 1.2f, 1.1f, 0.85f)
        }
        draw(u.sphere, mat(20f, y0 + 4f, -14f, 4f, 4f, 4f), 0.92f, 0.92f, 0.94f, 0.6f)
        draw(u.sphere, mat(29f, y0 + 3.2f, -10f, 3.2f, 3.2f, 3.2f), 0.92f, 0.92f, 0.94f, 0.6f)
        draw(u.box, mat(-22f, y0 + 3f, -18f, 10f, 6f, 6f), 0.7f, 0.72f, 0.75f, 0.2f)
    }

    private fun computeRocketBase() {
        val r = rocket ?: return
        val first = engine.flight?.stage ?: 0
        val L = r.totalLength - r.stageBase[first]
        val pivot = L * 0.4f
        Matrix.setIdentityM(rocketBase, 0)
        Matrix.translateM(rocketBase, 0, engine.laneX, 0f, 0f)
        Matrix.translateM(rocketBase, 0, 0f, pivot, 0f)
        Matrix.rotateM(rocketBase, 0, -Math.toDegrees(engine.tilt.toDouble()).toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rocketBase, 0, 0f, -pivot - r.stageBase[first], 0f)
    }

    private fun drawRocket() {
        val r = rocket ?: return
        if (engine.exploded) return
        computeRocketBase()
        val f = engine.flight
        val first = f?.stage ?: 0
        val boosters = f?.boostersAttached ?: (engine.spec.boosterCount > 0)
        val glow = engine.engineGlow
        for (p in r.parts) {
            val show = when {
                p.group == RocketModel.PAYLOAD -> true
                p.group >= 0 -> p.group >= first
                else -> boosters
            }
            if (!show) continue
            Matrix.multiplyMM(tmp, 0, rocketBase, 0, p.local, 0)
            val g = if (p.glowBell && (p.group == first || p.group < 0)) glow else 0f
            draw(p.mesh, tmp, p.r, p.g, p.b, p.spec, g * 1.4f, g * 0.6f, g * 0.15f)
        }
    }

    private fun drawSpent() {
        val r = rocket ?: return
        for (s in engine.spent) {
            val base = if (s.group >= 0) r.stageBase[s.group] else 0f
            Matrix.setIdentityM(tmp2, 0)
            Matrix.translateM(tmp2, 0, s.x, s.y, 0f)
            Matrix.rotateM(tmp2, 0, -Math.toDegrees(s.rot.toDouble()).toFloat(), 0f, 0f, 1f)
            Matrix.translateM(tmp2, 0, 0f, -base, 0f)
            for (p in r.parts) {
                if (p.group != s.group) continue
                Matrix.multiplyMM(tmp, 0, tmp2, 0, p.local, 0)
                draw(p.mesh, tmp, p.r, p.g, p.b, p.spec)
            }
        }
    }

    private fun drawEntities() {
        val t = engine.time
        for (e in engine.world.entities) {
            val x = e.x; val y = e.y; val z = e.z
            when (e.kind) {
                EntityKind.COIN -> {
                    val m = mat(x, y, z, 1.1f, 1.1f, 1.1f, ry = e.age * 200f + e.seed * 90f, rx = 90f)
                    val pulse = 0.25f + 0.15f * sin(t * 6f + e.seed * 10f)
                    val big = e.value >= 10
                    if (big) draw(u.coin, m, 0.75f, 0.85f, 1f, 1.3f, pulse * 0.6f, pulse * 0.8f, pulse)
                    else draw(u.coin, m, 1f, 0.78f, 0.2f, 1.3f, pulse, pulse * 0.7f, 0.05f)
                }
                EntityKind.FUEL -> {
                    val ry = e.age * 90f
                    draw(u.cylinder, mat(x, y - 0.8f, z, 0.65f, 1.6f, 0.65f, ry = ry), 0.2f, 0.85f, 0.35f, 0.6f, 0.05f, 0.25f, 0.08f)
                    draw(u.cylinder, mat(x, y + 0.8f, z, 0.3f, 0.35f, 0.3f, ry = ry), 0.9f, 0.9f, 0.9f, 0.6f)
                    draw(u.box, mat(x, y, z + 0.62f, 0.7f, 0.25f, 0.1f, ry = 0f), 1f, 1f, 1f, 0.2f, 0.4f, 0.4f, 0.4f)
                }
                EntityKind.REPAIR -> {
                    val ry = e.age * 90f
                    draw(u.box, mat(x, y, z, 1.5f, 1.5f, 1.5f, ry = ry), 0.95f, 0.95f, 0.95f, 0.4f, 0.15f, 0.15f, 0.15f)
                    val m = mat(x, y, z, ry = ry)
                    draw(u.box, mat(0f, 0f, 0f, 1.56f, 0.4f, 1.0f, pre = m), 0.9f, 0.1f, 0.1f, 0.3f, 0.3f, 0f, 0f)
                    draw(u.box, mat(0f, 0f, 0f, 1.0f, 0.4f, 1.56f, pre = m), 0.9f, 0.1f, 0.1f, 0.3f, 0.3f, 0f, 0f)
                    draw(u.box, mat(0f, 0f, 0f, 0.4f, 1.56f, 1.0f, pre = m), 0.9f, 0.1f, 0.1f, 0.3f, 0.3f, 0f, 0f)
                }
                EntityKind.BIRD -> {
                    val dir = if (e.vx >= 0) 0f else 180f
                    val m = mat(x, y, z, ry = dir)
                    draw(u.sphere, mat(0f, 0f, 0f, 0.55f, 0.28f, 0.28f, pre = m), 0.18f, 0.16f, 0.15f, 0.2f)
                    draw(u.sphere, mat(0.5f, 0.1f, 0f, 0.2f, 0.18f, 0.18f, pre = m), 0.2f, 0.18f, 0.16f, 0.2f)
                    val flap = sin(e.age * 14f + e.seed * 6f) * 40f
                    for (s in listOf(-1f, 1f)) {
                        val wm = mat(0f, 0.05f, s * 0.15f, rx = s * flap, pre = m)
                        draw(u.box, mat(0f, 0f, s * 0.55f, 0.35f, 0.04f, 1.1f, pre = wm), 0.22f, 0.2f, 0.18f, 0.1f)
                    }
                }
                EntityKind.PLANE -> {
                    val dir = if (e.vx >= 0) 0f else 180f
                    val m = mat(x, y, z, ry = dir)
                    draw(u.cylinder, mat(-4.5f, 0f, 0f, 0.75f, 9f, 0.75f, rz = -90f, pre = m), 0.95f, 0.95f, 0.97f, 0.7f)
                    draw(u.sphere, mat(4.5f, 0f, 0f, 1.3f, 0.75f, 0.75f, pre = m), 0.95f, 0.95f, 0.97f, 0.7f)
                    draw(u.cylinder, mat(-4.5f, -0.2f, 0f, 0.77f, 9f, 0.77f, rz = -90f, pre = mat(0f, 0f, 0f, 1f, 0.35f, 1f, pre = m)), 0.15f, 0.3f, 0.75f, 0.5f)
                    for (s in listOf(-1f, 1f)) draw(u.wing, mat(0.3f, -0.2f, 0f, 1f, 1f, 1f, rx = s * 90f, pre = m), 0.85f, 0.86f, 0.9f, 0.6f)
                    draw(u.tailFin, mat(-4.6f, 0.4f, 0f, pre = m), 0.15f, 0.3f, 0.75f, 0.5f)
                    draw(u.sphere, mat(-0.5f, -0.9f, 1.3f, 0.6f, 0.4f, 0.4f, pre = m), 0.6f, 0.6f, 0.65f, 0.6f)
                    draw(u.sphere, mat(-0.5f, -0.9f, -1.3f, 0.6f, 0.4f, 0.4f, pre = m), 0.6f, 0.6f, 0.65f, 0.6f)
                    val blink = if (sin(t * 8f) > 0.6f) 1f else 0f
                    draw(u.sphere, mat(0f, 0f, 0f, 0.12f, 0.12f, 0.12f, pre = mat(0.6f, -0.2f, 5f, pre = m)), 0.2f, 0f, 0f, 0f, blink * 2f, 0f, 0f)
                }
                EntityKind.BALLOON -> {
                    draw(u.sphere, mat(x, y + 1f, z, 2f, 2.4f, 2f), 0.95f, 0.95f, 0.92f, 0.8f)
                    draw(u.cylinder, mat(x, y - 2.6f, z, 0.03f, 2.3f, 0.03f), 0.7f, 0.7f, 0.7f, 0f)
                    draw(u.box, mat(x, y - 2.9f, z, 0.6f, 0.5f, 0.6f), 0.95f, 0.5f, 0.1f, 0.3f)
                }
                EntityKind.SATELLITE -> {
                    val m = mat(x, y, z, ry = e.rot * 57f, rz = e.rot * 20f)
                    draw(u.box, mat(0f, 0f, 0f, 1.5f, 1.5f, 1.9f, pre = m), 0.9f, 0.7f, 0.25f, 1.2f)
                    for (s in listOf(-1f, 1f)) {
                        draw(u.box, mat(s * 2.6f, 0f, 0f, 3.2f, 0.06f, 1.4f, pre = m), 0.1f, 0.18f, 0.5f, 1.5f, 0.02f, 0.04f, 0.1f)
                        draw(u.box, mat(s * 0.9f, 0f, 0f, 0.5f, 0.1f, 0.1f, pre = m), 0.7f, 0.7f, 0.7f, 0.5f)
                    }
                    draw(u.cone, mat(0f, 0.75f, 0f, 0.5f, 0.6f, 0.5f, pre = m), 0.85f, 0.85f, 0.85f, 0.8f)
                }
                EntityKind.DEBRIS -> {
                    val m = mat(x, y, z, ry = e.rot * 40f + e.seed * 300f, rz = e.rot * 57f, rx = e.seed * 90f)
                    draw(u.box, mat(0f, 0f, 0f, 2.2f, 0.5f, 1.1f, pre = m), 0.55f, 0.56f, 0.6f, 1f)
                    draw(u.box, mat(0.7f, 0.4f, 0f, 0.6f, 0.9f, 0.3f, rz = 30f, pre = m), 0.4f, 0.4f, 0.45f, 0.8f)
                    draw(u.cylinder, mat(-0.6f, -0.1f, 0f, 0.3f, 0.9f, 0.3f, rz = 70f, pre = m), 0.75f, 0.6f, 0.3f, 1f)
                }
                EntityKind.METEOR -> {
                    val m = mat(x, y, z, 2.2f, 1.9f, 2.0f, ry = e.rot * 57f, rx = e.rot * 30f)
                    draw(if (e.seed > 0.5f) u.rock else u.rock2, m, 0.36f, 0.3f, 0.26f, 0.15f, 0.25f, 0.08f, 0.02f)
                }
            }
        }
    }

    // ---------------------------------------------------------------- particles

    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, w: Float = 1f): FloatArray {
        vec[0] = x; vec[1] = y; vec[2] = z; vec[3] = w
        Matrix.multiplyMV(outV, 0, m, 0, vec, 0)
        return outV
    }

    private fun emitContinuous(dt: Float, alt: Double, atm: Float) {
        val rk = rocket ?: return
        if (engine.exploded) return
        computeRocketBase()
        val glow = engine.engineGlow
        val f = engine.flight
        val first = f?.stage ?: 0
        val padY = (-alt).toFloat()
        val frames = (dt * 60f).coerceIn(0.5f, 3f)
        if (glow > 0.02f) {
            val d = transformPoint(rocketBase, 0f, -1f, 0f, 0f); val dx = d[0]; val dy = d[1]
            val nozzles = ArrayList<FloatArray>()
            val p = transformPoint(rocketBase, 0f, rk.stageBase[first] + 0.05f, 0f); nozzles += floatArrayOf(p[0], p[1], p[2], 1f)
            if (f?.boostersAttached ?: (engine.spec.boosterCount > 0)) for (off in rk.boosterOffsets) {
                Matrix.multiplyMM(tmp, 0, rocketBase, 0, off, 0)
                val q = transformPoint(tmp, 0f, 0.05f, 0f); nozzles += floatArrayOf(q[0], q[1], q[2], 0.7f)
            }
            val vac = 1f - atm.coerceIn(0f, 1f)
            val spread = 3f + 14f * vac
            for (nz in nozzles) {
                val sz = nz[3] * (1.0f + 0.25f * engine.spec.level(com.brandonwallace.rocketlaunch.game.UpgradeType.ENGINE) / 6f)
                val n = (7 * glow * frames).toInt() + 1
                for (i in 0 until n) {
                    val sp = r(26f, 42f)
                    particles.emit(nz[0] + r(-0.15f, 0.15f), nz[1], nz[2] + r(-0.15f, 0.15f),
                        dx * sp + r(-spread, spread) * 0.4f, dy * sp, r(-spread, spread) * 0.4f,
                        r(0.16f, 0.3f) * (1f + vac), sz * r(0.9f, 1.3f), sz * (0.25f + 1.6f * vac),
                        1f, r(0.55f, 0.8f), r(0.15f, 0.3f) + 0.4f * vac, 0.85f * glow, 1, 2f, 0f, rnd.nextFloat())
                }
                particles.emit(nz[0], nz[1] - 0.3f, nz[2], dx * 4f, dy * 4f, 0f, 0.04f, sz * 2.2f, sz * 2.0f, 1f, 0.9f, 0.6f, 0.9f * glow, 1)
                particles.emit(nz[0], nz[1] - 0.6f, nz[2], dx * 30f, dy * 30f, 0f, 0.07f, sz * 0.8f, sz * 0.6f, 1f, 1f, 0.95f, glow, 3, 0f, 0f, rnd.nextFloat())
                if (atm > 0.02f) {
                    val sn = (2.5f * glow * frames * nz[3]).toInt() + (if (rnd.nextFloat() < 0.5f) 1 else 0)
                    for (i in 0 until sn) {
                        val sp = r(10f, 18f)
                        val gl = 0.85f
                        particles.emit(nz[0], nz[1] - 0.8f, nz[2], dx * sp + r(-2f, 2f), dy * sp, r(-2f, 2f),
                            r(2.5f, 4.5f), r(1.2f, 1.8f) * nz[3], r(5f, 9f) * nz[3], gl, gl, gl * 1.02f, 0.5f * sqrt(atm), 0, 1.2f, -1.5f, rnd.nextFloat(), r(-0.3f, 0.3f))
                    }
                }
            }
            // ground blast: smoke billows sideways across the pad
            val bottom = nozzles[0][1]
            if (bottom - padY < 45f) {
                val k = (1f - (bottom - padY) / 45f) * glow
                val n = (5 * k * frames).toInt() + (if (rnd.nextFloat() < k) 1 else 0)
                for (i in 0 until n) {
                    val a = r(0f, 6.283f); val sp = r(10f, 28f)
                    particles.emit(r(-1f, 1f), padY + 0.8f, r(-1f, 1f), cos(a) * sp, r(1f, 5f), sin(a) * sp * 0.6f,
                        r(3.5f, 6.5f), r(2f, 3f), r(9f, 16f), 0.9f, 0.9f, 0.88f, 0.6f, 0, 0.8f, -0.6f, rnd.nextFloat(), r(-0.2f, 0.2f))
                }
            }
        }
        // reentry-style plasma when the hull is cooking
        val heat = (f?.heat ?: 0.0).toFloat()
        if (heat > 0.45f) {
            val tip = transformPoint(rocketBase, 0f, rk.totalLength - 0.3f, 0f)
            val n = ((heat - 0.45f) * 10 * frames).toInt() + 1
            for (i in 0 until n) particles.emit(tip[0] + r(-0.8f, 0.8f), tip[1] - r(0f, 2f), tip[2] + r(-0.8f, 0.8f), r(-3f, 3f), r(-30f, -15f), 0f,
                0.25f, r(0.8f, 1.6f), 0.3f, 1f, r(0.35f, 0.6f), r(0.3f, 0.6f), min(1f, heat - 0.3f), 1)
        }
        // meteor trails
        for (e in engine.world.entities) if (e.kind == EntityKind.METEOR) {
            particles.emit(e.x + r(-0.6f, 0.6f), e.y + 1.5f, e.z, r(-1f, 1f), r(4f, 8f), 0f, 0.6f, r(1.2f, 2f), 0.3f, 1f, r(0.4f, 0.6f), 0.15f, 0.8f, 1)
        }
    }

    private fun burst(x: Float, y: Float, z: Float, n: Int, speed: Float, life: Float, size: Float, rr: Float, gg: Float, bbv: Float, a: Float, kind: Int, dragK: Float = 1.5f, grow: Float = 1f) {
        for (i in 0 until n) {
            val th = r(0f, 6.283f); val ph = r(-1f, 1f); val s = r(0.3f, 1f) * speed
            val c = sqrt(1 - ph * ph)
            particles.emit(x, y, z, cos(th) * c * s, ph * s, sin(th) * c * s, life * r(0.6f, 1.2f), size, size * grow, rr, gg, bbv, a, kind, dragK, 0f, rnd.nextFloat(), r(-1f, 1f))
        }
    }

    private fun handleFx(atm: Float) {
        val rk = rocket
        for (fx in engine.fx) when (fx) {
            is Fx.StageSep -> {
                val y = 0f
                burst(engine.laneX, y, 0f, 40, 12f, 1.8f, 1.5f, 0.95f, 0.95f, 0.97f, 0.7f, 0, 2f, 3.5f)
                burst(engine.laneX, y, 0f, 30, 25f, 0.5f, 0.35f, 1f, 0.8f, 0.4f, 1f, 3, 1f)
            }
            Fx.BoosterSep -> for (s in listOf(-1f, 1f)) {
                burst(engine.laneX + s * 1.6f, 2f, 0f, 25, 8f, 1.5f, 1.2f, 0.95f, 0.95f, 0.97f, 0.6f, 0, 2f, 3f)
            }
            is Fx.Hit -> {
                burst(fx.x, fx.y, 0f, 40, 30f, 0.6f, 0.35f, 1f, 0.7f, 0.3f, 1f, 3, 1.2f)
                burst(fx.x, fx.y, 0f, 14, 8f, 1.4f, 1.4f, 0.25f, 0.24f, 0.23f, 0.8f, 0, 2f, 3f)
            }
            is Fx.Collect -> {
                val c = when (fx.kind) { EntityKind.FUEL -> floatArrayOf(0.3f, 1f, 0.4f); EntityKind.REPAIR -> floatArrayOf(1f, 0.4f, 0.4f); else -> floatArrayOf(1f, 0.85f, 0.3f) }
                burst(fx.x, fx.y, 0f, 18, 14f, 0.5f, 0.35f, c[0], c[1], c[2], 1f, 3, 2f)
            }
            is Fx.Explode -> {
                burst(fx.x, fx.y, 0f, 160, 26f, 1.1f, 2.4f, 1f, 0.6f, 0.2f, 1f, 1, 2.5f, 2.5f)
                burst(fx.x, fx.y, 0f, 70, 40f, 1.2f, 0.4f, 1f, 0.85f, 0.5f, 1f, 3, 1f)
                burst(fx.x, fx.y, 0f, 60, 12f, 4f, 3f, 0.2f, 0.19f, 0.18f, 0.85f, 0, 1.2f, 4f)
            }
            Fx.Liftoff -> for (i in 0 until 60) {
                val a = r(0f, 6.283f); val sp = r(14f, 34f)
                particles.emit(r(-1f, 1f), 0.8f, r(-1f, 1f), cos(a) * sp, r(1f, 6f), sin(a) * sp * 0.6f, r(4f, 7f), r(2.5f, 4f), r(12f, 18f), 0.9f, 0.9f, 0.88f, 0.65f, 0, 0.7f, -0.5f, rnd.nextFloat())
            }
        }
        if (rk == null) return
    }

    private val cloudOrder = ArrayList<Int>()

    private fun drawBillboards(atm: Float) {
        instBuf.clear()
        // clouds first, far to near, as clusters of noisy puffs
        val clouds = engine.world.clouds
        cloudOrder.clear(); for (i in clouds.indices) cloudOrder.add(i)
        cloudOrder.sortBy { i -> val c = clouds[i]; -((c.x - eye[0]) * (c.x - eye[0]) + (c.y - eye[1]) * (c.y - eye[1]) + (c.z - eye[2]) * (c.z - eye[2])) }
        val cl = 0.82f + 0.18f * atm
        var nAlpha = 0
        for (i in cloudOrder) {
            if (nAlpha > 1150) break // 4800-instance buffer: <=1200 cloud puffs + <=3600 particles
            val c = clouds[i]
            for (k in 0 until 5) {
                val s = c.seed * 10f + k * 1.7f
                val ox = sin(s * 3.1f) * c.size * 0.55f; val oy = cos(s * 2.3f) * c.size * 0.18f; val oz = sin(s * 1.3f) * c.size * 0.3f
                instBuf.put(c.x + ox); instBuf.put(c.y + oy); instBuf.put(c.z + oz); instBuf.put(c.size * (0.55f + 0.2f * sin(s)))
                instBuf.put(cl); instBuf.put(cl); instBuf.put(cl * 1.02f); instBuf.put(0.82f)
                instBuf.put(2f); instBuf.put((s * 0.1f) % 1f); instBuf.put(0f); instBuf.put(0f)
                nAlpha++
            }
        }
        val light = 0.55f + 0.45f * sqrt(atm)
        nAlpha += particles.write(instBuf, false, light)
        val nAdd = particles.write(instBuf, true, 1f)
        val total = nAlpha + nAdd
        if (total == 0) return
        instBuf.position(0)
        bb.use()
        GLES30.glUniformMatrix4fv(bb.loc("uVP"), 1, false, vp, 0)
        GLES30.glUniform3f(bb.loc("uRight"), view[0], view[4], view[8])
        GLES30.glUniform3f(bb.loc("uUp"), view[1], view[5], view[9])
        GLES30.glBindVertexArray(bbVao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bbInst[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, instBuf.capacity() * 4, null, GLES30.GL_STREAM_DRAW)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, total * 48, instBuf)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        if (nAlpha > 0) {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            setInstancePointers(0)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, nAlpha)
        }
        if (nAdd > 0) {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            setInstancePointers(nAlpha)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, nAdd)
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
    }

    @Suppress("unused")
    private val lane = World.LANE
}
