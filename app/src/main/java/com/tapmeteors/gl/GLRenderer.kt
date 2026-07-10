package com.tapmeteors.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.tapmeteors.engine.Game
import com.tapmeteors.engine.GameState
import com.tapmeteors.engine.Meteor
import com.tapmeteors.engine.Saucer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * OpenGL ES 3.0 renderer for TapMeteors — an ISOMETRIC neon view of the whole
 * rock field: additive blend, hue-cycling wireframes, everything aglow on
 * black (transparent on the waveguide). On the X3 the frame renders once per
 * eye into side-by-side viewports.
 */
class GLRenderer(private val game: Game) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)
    private val rgb = FloatArray(3)

    private val lines = Batch(24000)
    private val fx = Batch(6000)
    private val hud = Batch(4000)

    private val FW = Game.FIELD_W
    private val FH = Game.FIELD_H

    // Fixed starfield below the play plane (hue drifts slowly).
    private val stars: FloatArray = Random(3).let { r ->
        FloatArray(90 * 3) { i ->
            when (i % 3) {
                0 -> r.nextFloat() * (FW + 24f) - 12f
                1 -> -2.5f - r.nextFloat() * 5f
                else -> r.nextFloat() * (FH + 24f) - 12f
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        game.update(dt)

        buildScene(); buildHud()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Fixed isometric camera over the field centre.
        val cx = FW / 2f; val cz = FH / 2f
        Matrix.setLookAtM(view, 0, cx + 26f, 34f, cz + 26f, cx, 0f, cz, 0f, 1f, 0f)

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()
        val v = 19f
        Matrix.orthoM(proj, 0, -v * aspect, v * aspect, -v, v, 1f, 300f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            lines.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 11f); fx.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    // ------------------------------------------------------- scene build

    private fun buildScene() {
        lines.reset(); fx.reset()
        buildStars()
        buildFloor()
        game.powerUp?.let { buildPowerUp(it) }
        for (m in game.meteors) buildMeteorGhosted(m)
        game.saucer?.let { buildSaucer(it) }
        if (game.shipAlive) buildShip()
        for (b in game.bullets) {
            if (b.hostile) {
                val hue = (game.time * 0.8f) % 1f
                hsv(hue, 0.85f, 1f)
                fx.v(b.x, 0.5f, b.z, rgb[0], rgb[1], rgb[2], 1f)
                lines.line(b.x, 0.5f, b.z, b.x - b.vx * 0.04f, 0.5f, b.z - b.vz * 0.04f, rgb[0], rgb[1], rgb[2], 0.7f)
            } else {
                fx.v(b.x, 0.5f, b.z, 0.5f, 1f, 1f, 1f)
                lines.line(b.x, 0.5f, b.z, b.x - b.vx * 0.03f, 0.5f, b.z - b.vz * 0.03f, 0.4f, 1f, 1f, 0.75f)
            }
        }
        for (pt in game.particles) {
            val k = (pt.life / pt.maxLife).coerceIn(0f, 1f)
            hsv(pt.hue, 1f, 1f)
            fx.v(pt.x, pt.y, pt.z, rgb[0], rgb[1], rgb[2], k)
        }
    }

    private fun buildStars() {
        val h = game.time * 0.03f
        for (i in 0 until stars.size / 3) {
            hsv((h + i * 0.013f) % 1f, 0.5f, 0.8f)
            val tw = 0.35f + 0.3f * sin(game.time * 1.7f + i)
            fx.v(stars[i * 3], stars[i * 3 + 1], stars[i * 3 + 2], rgb[0], rgb[1], rgb[2], tw)
        }
    }

    /**
     * No walls, no rectangle: space just continues (ships wrap at the screen
     * edge, arcade-style). A faint synthwave floor grid runs past the visible
     * field in every direction and throbs with the heartbeat.
     */
    private fun buildFloor() {
        hsv((game.time * 0.05f + 0.55f) % 1f, 0.7f, 0.35f)
        val a = 0.09f + 0.14f * game.beatPulse
        var gx = -16f
        while (gx <= FW + 16f) {
            lines.line(gx, 0f, -16f, gx, 0f, FH + 16f, rgb[0], rgb[1], rgb[2], a)
            gx += 8f
        }
        var gz = -16.5f
        while (gz <= FH + 16f) {
            lines.line(-16f, 0f, gz, FW + 16f, 0f, gz, rgb[0], rgb[1], rgb[2], a)
            gz += 7.5f
        }
    }

    /** The wave's collectible: a spinning diamond in the power's signature hue. */
    private fun buildPowerUp(p: com.tapmeteors.engine.PowerUp) {
        val hue = powerHue(p.type)
        val pulse = 0.6f + 0.4f * sin(game.time * 6f)
        val y = 0.6f + 0.15f * sin(game.time * 3f)
        val s = 0.75f
        hsv(hue, 0.85f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        // spinning diamond (two crossed squares)
        for (half in 0 until 2) {
            val rot = p.spin + half * 0.7854f
            var px = p.x + cos(rot) * s; var pz = p.z + sin(rot) * s
            for (i in 1..4) {
                val a2 = rot + i * 1.5708f
                val vx = p.x + cos(a2) * s; val vz = p.z + sin(a2) * s
                lines.line(px, y, pz, vx, y, vz, r, g, b, pulse)
                px = vx; pz = vz
            }
        }
        // vertical beacon + halo so it reads across the field
        lines.line(p.x, 0f, p.z, p.x, y + 1.1f, p.z, r, g, b, 0.3f * pulse)
        ring(p.x, 0.05f, p.z, 1.3f, 12, r, g, b, 0.35f * pulse)
        fx.v(p.x, y, p.z, 1f, 1f, 1f, pulse)
        // fading urgency blink in the last three seconds
        if (p.life < 3f && (p.life * 5f).toInt() % 2 == 0) {
            ring(p.x, y, p.z, s * 1.6f, 8, r, g, b, 0.5f)
        }
    }

    private fun powerHue(type: Int): Float = when (type) {
        Game.PWR_RAPID -> 0.08f    // ember orange
        Game.PWR_TRIPLE -> 0.5f    // cyan
        Game.PWR_SHIELD -> 0.33f   // green
        Game.PWR_PIERCE -> 0.85f   // magenta
        else -> 0.72f              // violet time warp
    }

    /** Wrap-aware: draw ghost copies when a rock straddles an edge. */
    private fun buildMeteorGhosted(m: Meteor) {
        val oxs = mutableListOf(0f)
        val ozs = mutableListOf(0f)
        if (m.x < m.radius) oxs.add(FW) else if (m.x > FW - m.radius) oxs.add(-FW)
        if (m.z < m.radius) ozs.add(FH) else if (m.z > FH - m.radius) ozs.add(-FH)
        for (ox in oxs) for (oz in ozs) buildMeteor(m, m.x + ox, m.z + oz)
    }

    private fun buildMeteor(m: Meteor, x: Float, z: Float) {
        // Hue cycles forever; brighter core for small (fast, dangerous) rocks.
        hsv((m.baseHue + game.time * 0.12f) % 1f, 0.85f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val n = m.shape.size
        val y = 0.5f
        val apex = 0.6f + m.radius * 0.45f
        var px = 0f; var pz = 0f; var fx0 = 0f; var fz0 = 0f
        for (i in 0..n) {
            val k = i % n
            val a = m.angle + k * (6.2832f / n)
            val rad = m.radius * m.shape[k]
            val vx = x + cos(a) * rad
            val vz = z + sin(a) * rad
            if (i == 0) { fx0 = vx; fz0 = vz } else {
                lines.line(px, y, pz, vx, y, vz, r, g, b, 0.95f)
                // crystal ribs up to a glowing apex — every third vertex
                if (k % 3 == 0) lines.line(vx, y, vz, x, y + apex, z, r, g, b, 0.45f)
            }
            px = vx; pz = vz
        }
        lines.line(px, y, pz, fx0, y, fz0, r, g, b, 0.95f)
        fx.v(x, y + apex, z, 1f, 1f, 1f, 0.8f)
    }

    private fun buildShip() {
        val x = game.shipX; val z = game.shipZ
        val hAng = game.heading
        // Invulnerability shimmer: blink + halo.
        val blink = if (game.invuln > 0f) (0.45f + 0.55f * sin(game.time * 20f)) else 1f
        val y = 0.55f

        fun pt(d: Float, off: Float, rad: Float): Pair<Float, Float> =
            Pair(x + cos(hAng + off) * rad * d, z + sin(hAng + off) * rad * d)

        val nose = pt(1f, 0f, 1.5f)
        val lw = pt(1f, 2.55f, 1.15f)
        val rw = pt(1f, -2.55f, 1.15f)
        val tail = pt(1f, 3.1416f, 0.55f)

        val cyan = floatArrayOf(0.35f, 0.95f, 1f)
        lines.line(nose.first, y, nose.second, lw.first, y, lw.second, cyan[0], cyan[1], cyan[2], blink)
        lines.line(nose.first, y, nose.second, rw.first, y, rw.second, cyan[0], cyan[1], cyan[2], blink)
        lines.line(lw.first, y, lw.second, tail.first, y, tail.second, cyan[0], cyan[1], cyan[2], blink * 0.85f)
        lines.line(rw.first, y, rw.second, tail.first, y, tail.second, cyan[0], cyan[1], cyan[2], blink * 0.85f)
        // canopy mast for 3D pop
        lines.line(x, y, z, x, y + 0.9f, z, cyan[0], cyan[1], cyan[2], blink * 0.7f)
        fx.v(x, y + 0.95f, z, 1f, 1f, 1f, blink)

        if (game.thrustFlash > 0f) {
            val k = game.thrustFlash / 0.22f
            val flame = pt(1f, 3.1416f, 1.6f + k)
            hsv(0.09f, 1f, 1f)
            lines.line(tail.first, y, tail.second, flame.first, y, flame.second, rgb[0], rgb[1], rgb[2], k)
        }
        if (game.invuln > 0f) {
            hsv((game.time * 0.5f) % 1f, 0.6f, 1f)
            ring(x, y, z, 1.9f, 12, rgb[0], rgb[1], rgb[2], 0.4f * blink)
        }
    }

    /** The visitor: stacked spinning disco rings, hue-strobing spokes, dome spark. */
    private fun buildSaucer(s: Saucer) {
        val x = s.x; val z = s.z
        val bob = 0.15f * sin(s.t * 4f)
        val y0 = 0.45f + bob
        val y1 = 1.05f + bob
        val rr = s.radius

        val hue = (game.time * (if (s.small) 0.9f else 0.45f)) % 1f
        hsv(hue, 0.9f, 1f)
        ring(x, y0, z, rr, 14, rgb[0], rgb[1], rgb[2], 0.95f)
        hsv((hue + 0.33f) % 1f, 0.9f, 1f)
        ring(x, y1, z, rr * 0.55f, 10, rgb[0], rgb[1], rgb[2], 0.95f)

        // spinning spokes between rings — the disco cage
        for (k in 0 until 6) {
            val a = s.t * (if (s.small) 5f else 2.6f) + k * 1.0472f
            hsv((hue + k * 0.16f) % 1f, 1f, 1f)
            lines.line(
                x + cos(a) * rr, y0, z + sin(a) * rr,
                x + cos(a) * rr * 0.55f, y1, z + sin(a) * rr * 0.55f,
                rgb[0], rgb[1], rgb[2], 0.8f
            )
        }
        // dome light
        fx.v(x, y1 + 0.35f, z, 1f, 1f, 1f, 0.8f + 0.2f * sin(s.t * 9f))
        // under-glow sparkle trail
        hsv((hue + 0.5f) % 1f, 0.8f, 1f)
        fx.v(x - s.dir * 0.8f, y0 - 0.25f, z, rgb[0], rgb[1], rgb[2], 0.5f)
    }

    private fun ring(x: Float, y: Float, z: Float, rad: Float, seg: Int, r: Float, g: Float, b: Float, a: Float) {
        var px = x + rad; var pz = z
        for (i in 1..seg) {
            val ang = i * (6.2832f / seg)
            val vx = x + cos(ang) * rad
            val vz = z + sin(ang) * rad
            lines.line(px, y, pz, vx, y, vz, r, g, b, a)
            px = vx; pz = vz
        }
    }

    // -------------------------------------------------------------- hud

    private val sink = object : StrokeFont.LineSink {
        var cr = 1f; var cg = 1f; var cb = 1f; var ca = 1f
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) { hud.line(x0, y0, 0f, x1, y1, 0f, cr, cg, cb, ca) }
    }

    private fun text(s: String, cx: Float, y: Float, scale: Float, r: Float, g: Float, b: Float, a: Float = 1f, center: Boolean = true) {
        val x = if (center) cx - StrokeFont.width(s, scale) / 2f else cx
        sink.cr = r; sink.cg = g; sink.cb = b; sink.ca = a
        StrokeFont.draw(s, x, y, scale, sink)
    }

    private fun buildHud() {
        hud.reset()
        val pulse = 0.55f + 0.45f * sin(game.time * 4f)
        hsv(game.time * 0.05f, 0.8f, 1f)
        val hr = rgb[0]; val hg = rgb[1]; val hb = rgb[2]

        when (game.state) {
            GameState.TITLE -> {
                text("TAPMETEORS", 320f, 140f, 4.4f, hr, hg, hb)
                text("THE COSMOS WON'T SWEEP ITSELF", 320f, 196f, 1.5f, 0.7f, 0.9f, 1f)
                text("SWIPE TO TURN - TAP TO THRUST", 320f, 250f, 1.7f, 1f, 1f, 1f, pulse)
                text("CANNONS FIRE THEMSELVES", 320f, 284f, 1.5f, 0.7f, 0.9f, 1f)
                if (game.highScore > 0) text("HIGH ${game.highScore}", 320f, 330f, 1.8f, 0.6f, 1f, 0.7f)
                text("TAP TO CLOCK IN", 320f, 386f, 2f, 0.5f, 1f, 0.6f, pulse)
            }
            GameState.GAME_OVER -> {
                bar()
                text("PLANET DOOMED", 320f, 200f, 3.6f, 1f, 0.4f, 0.35f)
                text("SCORE ${game.score}", 320f, 258f, 2.2f, 1f, 1f, 1f)
                text("WAVE ${game.wave} - BEST ${game.bestWave}", 320f, 300f, 1.6f, 0.7f, 0.9f, 1f)
                text("TAP TO CLOCK BACK IN", 320f, 366f, 1.9f, 0.5f, 1f, 0.6f, pulse)
            }
            else -> bar()
        }

        game.message?.let {
            hsv((game.messageHue + game.time * 0.4f) % 1f, 0.85f, 1f)
            text(it, 320f, 246f, 2.6f, rgb[0], rgb[1], rgb[2], 0.6f + 0.4f * pulse)
        }
    }

    private fun bar() {
        text("${game.score}", 16f, 40f, 2.2f, 1f, 1f, 1f, 1f, center = false)
        val wv = "WAVE ${game.wave}"
        text(wv, 320f - StrokeFont.width(wv, 1.6f) / 2f, 40f, 1.6f, 0.7f, 0.85f, 1f, 1f, center = false)
        // lives as chevrons, top right
        for (i in 0 until game.lives.coerceAtMost(6)) {
            val cx = 624f - i * 26f
            hud.line(cx, 20f, 0f, cx - 8f, 38f, 0f, 0.35f, 0.95f, 1f, 1f)
            hud.line(cx, 20f, 0f, cx + 8f, 38f, 0f, 0.35f, 0.95f, 1f, 1f)
        }
        // active power: name + draining time bar, small, under the wave label
        if (game.activePower >= 0) {
            hsv(powerHue(game.activePower), 0.85f, 1f)
            val name = Game.POWER_NAMES[game.activePower].trimEnd('!')
            text(name, 320f - StrokeFont.width(name, 1.2f) / 2f, 62f, 1.2f, rgb[0], rgb[1], rgb[2], 0.9f, center = false)
            val frac = (game.powerT / Game.POWER_DURATION).coerceIn(0f, 1f)
            hud.line(320f - 60f, 70f, 0f, 320f - 60f + 120f * frac, 70f, 0f, rgb[0], rgb[1], rgb[2], 0.9f)
        }
    }

    // ------------------------------------------------------- gl helpers

    private fun hsv(hh: Float, s: Float, v: Float) {
        val h6 = ((hh % 1f + 1f) % 1f) * 6f
        val i = h6.toInt(); val f = h6 - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        when (i % 6) {
            0 -> { rgb[0] = v; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = v; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = v; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = v }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = v }
            else -> { rgb[0] = v; rgb[1] = p; rgb[2] = q }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapMeteors", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapMeteors", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer = ByteBuffer.allocateDirect(maxVerts * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set
        fun reset() { fb.position(0); count = 0 }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }
        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }
        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0); GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3); GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        private const val VERT = """#version 300 es
        in vec3 aPos; in vec4 aColor; uniform mat4 uMVP; uniform float uPointSize; out vec4 vColor;
        void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPointSize; vColor = aColor; }"""
        private const val FRAG = """#version 300 es
        precision mediump float; in vec4 vColor; uniform float uPoint; out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r2 = dot(d, d); if (r2 > 0.25) discard; fragColor = vec4(vColor.rgb, vColor.a * (1.0 - r2 * 4.0)); }
            else { fragColor = vColor; }
        }"""
    }
}
