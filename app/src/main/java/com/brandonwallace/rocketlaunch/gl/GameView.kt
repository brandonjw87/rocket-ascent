package com.brandonwallace.rocketlaunch.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import com.brandonwallace.rocketlaunch.audio.SoundEngine
import com.brandonwallace.rocketlaunch.game.GameEngine
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

@SuppressLint("ViewConstructor")
class GameView(context: Context, engine: GameEngine, sound: SoundEngine) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MsaaConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(GameRenderer(engine, sound))
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Render at ~80% resolution (scaled up by the compositor) to keep the per-pixel sky shader at 60 fps.
        if (w > 0 && h > 0) post { holder.setFixedSize((w * 0.8f).toInt(), (h * 0.8f).toInt()) }
    }
}

/** Prefer 4x MSAA with ES3, fall back to a plain config. */
class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val es3 = 0x40
        val withMsaa = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE,
        )
        val plain = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE,
        )
        for (attribs in listOf(withMsaa, plain)) {
            val num = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0 && configs[0] != null) return configs[0]!!
        }
        throw IllegalStateException("No OpenGL ES 3 config available")
    }
}
