package com.tapmeteors

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tapmeteors.audio.Sfx
import com.tapmeteors.engine.Game
import com.tapmeteors.engine.GameHost
import com.tapmeteors.gl.GLRenderer
import kotlin.math.abs
import kotlin.math.max

/**
 * TapMeteors. TWO controls, no settings menu:
 *  - SWIPE on the temple pad = turn, relative to the ship's heading: swipe
 *    forward = turn right (clockwise), swipe back = turn left. One discrete
 *    30° step per gesture (the x3cycles-proven convention; raw dx sign is
 *    inverted vs the physical gesture on this hardware).
 *  - TAP (arrives as a KEY on the glasses) = thrust; also starts/retries.
 *    The cannon fires itself.
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    // De-dupe one physical press that may arrive as both KEY and touch.
    private var lastAction = 0L
    private var downX = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        game = Game(store, this)
        renderer = GLRenderer(game).also { it.sbs = store.sbs }

        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        game.boot()
    }

    // ------------------------------------------------------------ GameHost

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun startSaucerLoop() = sfx.startSaucerLoop()
    override fun stopSaucerLoop() = sfx.stopSaucerLoop()

    // --------------------------------------------------------------- input

    private fun act(run: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAction < 25) return // KEY+touch echo of one physical press
        lastAction = now
        glView.queueEvent(run)
    }

    private fun thrust() = act { game.tap() }
    private fun turn(left: Boolean) = act { game.turn(left) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> { thrust(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { turn(true); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { turn(false); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Ignore the left temple volume pad.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = ev.x
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                // In play the pad only ever means "turn": classify by the SIGN
                // of horizontal travel past a small dead-zone; anything shorter
                // is a tap = thrust. Sign inverted vs the physical gesture.
                val dead = max(16f, 0.02f * resources.displayMetrics.widthPixels)
                if (abs(dx) >= dead) turn(left = dx > 0) else thrust()
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
    }

    override fun onPause() {
        sfx.stopSaucerLoop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
