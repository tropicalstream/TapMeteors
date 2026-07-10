package com.tapmeteors.engine

import com.tapmeteors.SettingsStore
import com.tapmeteors.audio.Sfx
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

enum class GameState { TITLE, PLAYING, SHIP_DOWN, WAVE_CLEAR, GAME_OVER }

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startSaucerLoop()
    fun stopSaucerLoop()
}

/** A drifting neon rock. size: 2 large, 1 medium, 0 small. */
class Meteor(var x: Float, var z: Float, var vx: Float, var vz: Float, val size: Int, rng: Random) {
    val radius = when (size) { 2 -> 3.0f; 1 -> 1.75f; else -> 0.95f }
    val baseHue = rng.nextFloat()
    var spin = (rng.nextFloat() * 2f - 1f) * 1.4f
    var angle = rng.nextFloat() * 6.2832f
    // Wireframe silhouette: per-vertex radius jitter, fixed for the rock's life.
    val shape = FloatArray(10) { 0.65f + rng.nextFloat() * 0.5f }
    var alive = true
}

class Bullet(var x: Float, var z: Float, var vx: Float, var vz: Float, val hostile: Boolean) {
    var life = if (hostile) 2.6f else 1.35f
}

/**
 * The visitor. Two flavours: the big "disco" saucer (spiral bursts, slow
 * wander, periodically drops a radial ring on the beat) and the small hunter
 * (aimed shots, fast wobble) from wave 4 on.
 */
class Saucer(var x: Float, var z: Float, val small: Boolean, val dir: Float) {
    var t = 0f
    var fireT = 1.4f
    var beatT = if (small) Float.MAX_VALUE else 5f
    var alive = true
    val radius = if (small) 1.0f else 1.6f
}

class Particle {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var life = 0f; var maxLife = 1f; var hue = 0f
}

/**
 * TapMeteors — a synthwave isometric remix of the 1979 rock-blaster. Swipe
 * turns the ship, tap thrusts, the cannon fires itself. Rocks split twice,
 * a psychedelic saucer drops by, and a two-tone heartbeat tightens as the
 * field empties.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val FIELD_W = 40f
        const val FIELD_H = 30f
        const val TURN_STEP = 0.5236f       // 30° per swipe
        const val THRUST = 5.2f             // impulse per tap
        const val MAX_SPEED = 13f
        const val DRAG = 0.55f              // exponential velocity decay /s
        const val FIRE_EVERY = 0.38f
        const val MAX_SHOTS = 5
        const val BULLET_SPEED = 21f
        const val START_LIVES = 3
        const val EXTRA_LIFE_EVERY = 10000
        const val RESPAWN_INVULN = 2.6f
    }

    var state = GameState.TITLE; private set
    var time = 0f; private set

    // --- ship ---
    var shipX = FIELD_W / 2f; private set
    var shipZ = FIELD_H / 2f; private set
    var shipVx = 0f; private set
    var shipVz = 0f; private set
    var heading = -1.5708f; private set        // rendered heading (eases)
    var targetHeading = -1.5708f; private set
    var invuln = 0f; private set
    var thrustFlash = 0f; private set
    var shipAlive = false; private set

    val meteors = ArrayList<Meteor>()
    val bullets = ArrayList<Bullet>()
    var saucer: Saucer? = null; private set
    val particles = ArrayList<Particle>()
    private val pool = ArrayDeque<Particle>()

    var wave = 1; private set
    var score = 0; private set
    var lives = START_LIVES; private set
    var highScore = 0; private set
    var bestWave = 1; private set
    private var nextLifeAt = EXTRA_LIFE_EVERY

    // --- pacing ---
    private var fireT = 0f
    private var stateT = 0f
    private var saucerT = 0f
    private var initialRocks = 1f

    // --- heartbeat ---
    private var beatT = 0f
    private var beatHi = false
    var beatPulse = 0f; private set            // renderer border throb

    // --- flourish ---
    var message: String? = null; private set
    var messageHue = 0f; private set
    private var messageUntil = 0f
    private val clearCries = arrayOf(
        "GRID CLEAR!", "MOO-VELOUS!", "UNGULATE APPROVED", "YAK ATTACK AVERTED",
        "SHEEP MAY SAFELY GRAZE", "THE LLAMA SALUTES YOU", "BOVINE AND BEYOND",
    )

    private val rng = Random(System.nanoTime())

    fun boot() {
        highScore = store.highScore
        bestWave = store.bestWave
        state = GameState.TITLE
    }

    // ---------------------------------------------------------------- input

    /** One discrete swipe = one 30° turn. left=false turns clockwise. */
    fun turn(left: Boolean) {
        if (state != GameState.PLAYING && state != GameState.WAVE_CLEAR) return
        if (!shipAlive) return
        targetHeading += if (left) -TURN_STEP else TURN_STEP
        host.sfx(Sfx.TURN, if (left) 1.12f else 0.95f, 0.35f)
    }

    /** Tap: thrust — or start from the title / game-over screens. */
    fun tap() {
        when (state) {
            GameState.TITLE, GameState.GAME_OVER -> startGame()
            GameState.PLAYING, GameState.WAVE_CLEAR -> {
                if (!shipAlive) return
                shipVx += cos(heading) * THRUST
                shipVz += sin(heading) * THRUST
                val sp = hypot(shipVx, shipVz)
                if (sp > MAX_SPEED) { shipVx *= MAX_SPEED / sp; shipVz *= MAX_SPEED / sp }
                thrustFlash = 0.22f
                host.sfx(Sfx.THRUST, 0.9f + rng.nextFloat() * 0.2f, 0.8f)
                exhaust()
            }
            else -> {}
        }
    }

    // ----------------------------------------------------------------- flow

    private fun startGame() {
        score = 0
        lives = START_LIVES
        wave = 1
        nextLifeAt = EXTRA_LIFE_EVERY
        store.games++
        host.sfx(Sfx.START)
        beginWave()
        spawnShip()
    }

    private fun beginWave() {
        meteors.clear()
        bullets.removeAll { it.hostile }
        killSaucer(silent = true)
        val n = (2 + wave).coerceAtMost(8)
        repeat(n) { spawnMeteor() }
        initialRocks = rockMass().coerceAtLeast(1f)
        saucerT = 9f + rng.nextFloat() * 8f
        beatT = 0.4f
        state = GameState.PLAYING
        flash("WAVE $wave", 2.2f)
        host.sfx(Sfx.WAVE)
        if (wave > bestWave) { bestWave = wave; store.bestWave = wave }
    }

    /** New large rocks enter from the border, never on top of the ship. */
    private fun spawnMeteor() {
        var x: Float; var z: Float
        do {
            if (rng.nextBoolean()) { x = if (rng.nextBoolean()) 1f else FIELD_W - 1f; z = rng.nextFloat() * FIELD_H }
            else { x = rng.nextFloat() * FIELD_W; z = if (rng.nextBoolean()) 1f else FIELD_H - 1f }
        } while (hypot(x - shipX, z - shipZ) < 9f)
        val a = rng.nextFloat() * 6.2832f
        val sp = (0.9f + rng.nextFloat() * 1.1f) * (1f + wave * 0.06f)
        meteors.add(Meteor(x, z, cos(a) * sp, sin(a) * sp, 2, rng))
    }

    private fun spawnShip() {
        shipX = FIELD_W / 2f; shipZ = FIELD_H / 2f
        shipVx = 0f; shipVz = 0f
        heading = -1.5708f; targetHeading = heading
        invuln = RESPAWN_INVULN
        shipAlive = true
        fireT = 0.2f
        host.sfx(Sfx.SPAWN)
    }

    private fun flash(text: String, secs: Float) {
        message = text
        messageHue = rng.nextFloat()
        messageUntil = time + secs
    }

    // --------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        beatPulse = maxOf(0f, beatPulse - dt * 4f)
        thrustFlash = maxOf(0f, thrustFlash - dt)
        if (message != null && time > messageUntil) message = null
        updateParticles(dt)

        when (state) {
            GameState.TITLE, GameState.GAME_OVER -> {}
            GameState.PLAYING -> {
                stepWorld(dt)
                heartbeat(dt)
                if (state == GameState.PLAYING && meteors.isEmpty() && saucer == null) {
                    state = GameState.WAVE_CLEAR
                    stateT = 2.4f
                    flash(clearCries[rng.nextInt(clearCries.size)], 2.2f)
                    host.sfx(Sfx.CLEAR)
                }
            }
            GameState.SHIP_DOWN -> {
                stepWorld(dt, shipFrozen = true)
                stateT -= dt
                if (stateT <= 0f && (centerSafe() || stateT < -4f)) {
                    spawnShip()
                    state = GameState.PLAYING
                }
            }
            GameState.WAVE_CLEAR -> {
                stepWorld(dt)
                stateT -= dt
                if (stateT <= 0f) { wave++; beginWave() }
            }
        }
    }

    private fun stepWorld(dt: Float, shipFrozen: Boolean = false) {
        // --- ship ---
        if (shipAlive && !shipFrozen) {
            val ease = 1f - exp(-10f * dt)
            heading += shortestArc(targetHeading - heading) * ease
            val drag = exp(-DRAG * dt)
            shipVx *= drag; shipVz *= drag
            shipX = wrapX(shipX + shipVx * dt)
            shipZ = wrapZ(shipZ + shipVz * dt)
            invuln = maxOf(0f, invuln - dt)

            // Auto-fire.
            fireT -= dt
            if (fireT <= 0f && state == GameState.PLAYING && bullets.count { !it.hostile } < MAX_SHOTS) {
                fireT = FIRE_EVERY
                bullets.add(
                    Bullet(
                        wrapX(shipX + cos(heading) * 1.1f), wrapZ(shipZ + sin(heading) * 1.1f),
                        cos(heading) * BULLET_SPEED + shipVx * 0.35f,
                        sin(heading) * BULLET_SPEED + shipVz * 0.35f, hostile = false
                    )
                )
                host.sfx(Sfx.FIRE, 0.95f + rng.nextFloat() * 0.12f, 0.4f)
            }
        }

        // --- meteors drift ---
        for (m in meteors) {
            m.x = wrapX(m.x + m.vx * dt); m.z = wrapZ(m.z + m.vz * dt)
            m.angle += m.spin * dt
        }

        updateSaucer(dt)

        // --- bullets ---
        var i = bullets.size - 1
        while (i >= 0) {
            val b = bullets[i]
            b.x = wrapX(b.x + b.vx * dt); b.z = wrapZ(b.z + b.vz * dt)
            b.life -= dt
            if (b.life <= 0f) { bullets.removeAt(i); i--; continue }
            var consumed = false

            // vs meteors (hostile bolts crack rocks too — no score for those)
            for (m in meteors) {
                if (!m.alive) continue
                if (hypot(b.x - m.x, b.z - m.z) < m.radius) {
                    breakMeteor(m, scored = !b.hostile)
                    consumed = true; break
                }
            }
            if (!consumed && !b.hostile) saucer?.let { s ->
                if (s.alive && hypot(b.x - s.x, b.z - s.z) < s.radius + 0.3f) {
                    addScore(if (s.small) 1000 else 200)
                    explode(s.x, 0.8f, s.z, rng.nextFloat(), 70, 7f)
                    flash(if (s.small) "HUNTER DOWN +1000" else "SAUCER DOWN +200", 1.6f)
                    killSaucer(silent = false)
                    consumed = true
                }
            }
            if (!consumed && b.hostile && shipAlive && !shipFrozen && invuln <= 0f &&
                hypot(b.x - shipX, b.z - shipZ) < 1.0f
            ) { shipDown(); consumed = true }

            if (consumed) bullets.removeAt(i)
            i--
        }
        meteors.removeAll { !it.alive }

        // --- ship body collisions ---
        if (shipAlive && invuln <= 0f && !shipFrozen) {
            for (m in meteors) if (hypot(m.x - shipX, m.z - shipZ) < m.radius + 0.75f) { shipDown(); break }
            saucer?.let { s ->
                if (shipAlive && s.alive && hypot(s.x - shipX, s.z - shipZ) < s.radius + 0.8f) shipDown()
            }
        }
    }

    private fun updateSaucer(dt: Float) {
        val s = saucer
        if (s == null) {
            if (state == GameState.PLAYING && meteors.isNotEmpty()) {
                saucerT -= dt
                if (saucerT <= 0f) {
                    val small = wave >= 4 && rng.nextFloat() < 0.45f
                    val fromLeft = rng.nextBoolean()
                    saucer = Saucer(
                        if (fromLeft) 0f else FIELD_W,
                        FIELD_H * 0.15f + rng.nextFloat() * FIELD_H * 0.7f,
                        small, if (fromLeft) 1f else -1f
                    )
                    host.sfx(Sfx.WARP)
                    host.startSaucerLoop()
                    flash(if (small) "HUNTER INBOUND" else "VISITOR INBOUND", 1.5f)
                }
            }
            return
        }
        s.t += dt
        val speed = if (s.small) 6.5f else 4.2f
        s.x += s.dir * speed * dt
        s.z += sin(s.t * (if (s.small) 3.1f else 1.7f)) * (if (s.small) 5f else 3f) * dt
        s.z = s.z.coerceIn(1.5f, FIELD_H - 1.5f)

        // Weapons: the hunter aims; the big one throws rotating spiral bursts.
        s.fireT -= dt
        if (s.fireT <= 0f) {
            if (s.small) {
                s.fireT = 1.5f
                if (shipAlive) {
                    val a = atan2(shipZ - s.z, shipX - s.x) + (rng.nextFloat() - 0.5f) * 0.18f
                    bullets.add(Bullet(s.x, s.z, cos(a) * 13f, sin(a) * 13f, hostile = true))
                    host.sfx(Sfx.SAUCER_FIRE, 1.25f)
                }
            } else {
                s.fireT = 2.1f
                val base = s.t * 0.9f
                for (k in 0 until 6) {
                    val a = base + k * 1.0472f
                    bullets.add(Bullet(s.x, s.z, cos(a) * 9f, sin(a) * 9f, hostile = true))
                }
                host.sfx(Sfx.SAUCER_FIRE, 0.85f)
            }
        }
        // The disco drop: a radial ring of bolts + screen throb, on the beat.
        s.beatT -= dt
        if (s.beatT <= 0f) {
            s.beatT = 6f
            beatPulse = 1f
            for (k in 0 until 12) {
                val a = k * 0.5236f
                bullets.add(Bullet(s.x, s.z, cos(a) * 6.5f, sin(a) * 6.5f, hostile = true))
            }
            host.sfx(Sfx.DROP)
        }

        if ((s.dir > 0f && s.x > FIELD_W + 1f) || (s.dir < 0f && s.x < -1f)) killSaucer(silent = true)
    }

    private fun killSaucer(silent: Boolean) {
        if (saucer != null) {
            if (!silent) host.sfx(Sfx.SAUCER_DIE)
            host.stopSaucerLoop()
            saucerT = 11f + rng.nextFloat() * 9f
        }
        saucer = null
    }

    private fun breakMeteor(m: Meteor, scored: Boolean) {
        m.alive = false
        if (scored) addScore(when (m.size) { 2 -> 20; 1 -> 50; else -> 100 })
        explode(m.x, 0.5f, m.z, m.baseHue, 20 + m.size * 16, 3f + m.size * 2f)
        host.sfx(when (m.size) { 2 -> Sfx.EXPL_L; 1 -> Sfx.EXPL_M; else -> Sfx.EXPL_S })
        if (m.size > 0) {
            repeat(2) {
                val a = rng.nextFloat() * 6.2832f
                val sp = (1.4f + rng.nextFloat() * 1.2f) * (1f + wave * 0.05f)
                val child = Meteor(
                    m.x, m.z,
                    cos(a) * sp + m.vx * 0.4f, sin(a) * sp + m.vz * 0.4f,
                    m.size - 1, rng
                )
                meteors.add(child)
            }
        }
    }

    private fun shipDown() {
        shipAlive = false
        lives--
        explode(shipX, 0.6f, shipZ, 0.52f, 90, 8f)
        host.sfx(Sfx.SHIP_DIE)
        if (lives <= 0) {
            state = GameState.GAME_OVER
            killSaucer(silent = true)
            host.sfx(Sfx.GAMEOVER)
            if (score >= highScore && score > 0) { host.sfx(Sfx.HISCORE); flash("NEW HIGH SCORE!", 3.5f) }
        } else {
            state = GameState.SHIP_DOWN
            stateT = 2.0f
        }
    }

    private fun addScore(n: Int) {
        score += n
        if (score >= nextLifeAt) {
            nextLifeAt += EXTRA_LIFE_EVERY
            lives++
            flash("1UP!", 2f)
            host.sfx(Sfx.LIFE)
        }
        if (score > highScore) { highScore = score; store.highScore = score }
    }

    /** The two-tone heartbeat, tightening as the field empties (or a saucer prowls). */
    private fun heartbeat(dt: Float) {
        beatT -= dt
        if (beatT > 0f) return
        val frac = (rockMass() / initialRocks).coerceIn(0f, 1f)
        var interval = 0.26f + 0.95f * frac
        if (saucer != null) interval *= 0.72f
        beatT = interval
        beatHi = !beatHi
        beatPulse = maxOf(beatPulse, 0.55f)
        host.sfx(if (beatHi) Sfx.THUMP_HI else Sfx.THUMP_LO, 1f, 0.85f)
    }

    private fun rockMass(): Float {
        var m = 0f
        for (r in meteors) m += when (r.size) { 2 -> 4f; 1 -> 2f; else -> 1f }
        return m
    }

    private fun centerSafe(): Boolean {
        for (m in meteors) if (hypot(m.x - FIELD_W / 2f, m.z - FIELD_H / 2f) < m.radius + 5f) return false
        return true
    }

    // ------------------------------------------------------------- helpers

    private fun wrapX(v: Float) = ((v % FIELD_W) + FIELD_W) % FIELD_W
    private fun wrapZ(v: Float) = ((v % FIELD_H) + FIELD_H) % FIELD_H

    private fun shortestArc(d: Float): Float {
        var a = d
        while (a > 3.1416f) a -= 6.2832f
        while (a < -3.1416f) a += 6.2832f
        return a
    }

    private fun exhaust() {
        repeat(10) {
            val p = pool.removeFirstOrNull() ?: Particle()
            val back = heading + 3.1416f + (rng.nextFloat() - 0.5f) * 0.7f
            p.x = shipX + cos(back) * 1.0f; p.y = 0.45f; p.z = shipZ + sin(back) * 1.0f
            val sp = 3f + rng.nextFloat() * 4f
            p.vx = cos(back) * sp + shipVx * 0.5f; p.vz = sin(back) * sp + shipVz * 0.5f
            p.vy = 0.5f + rng.nextFloat()
            p.life = 0.3f + rng.nextFloat() * 0.25f; p.maxLife = p.life
            p.hue = 0.08f + rng.nextFloat() * 0.09f
            particles.add(p)
        }
    }

    private fun explode(x: Float, y: Float, z: Float, hue: Float, count: Int, power: Float) {
        repeat(count) {
            val p = pool.removeFirstOrNull() ?: Particle()
            p.x = x; p.y = y; p.z = z
            val a = rng.nextFloat() * 6.2832f
            val sp = rng.nextFloat() * power
            p.vx = cos(a) * sp; p.vz = sin(a) * sp; p.vy = 1f + rng.nextFloat() * 4f
            p.life = 0.7f + rng.nextFloat() * 0.7f; p.maxLife = p.life
            p.hue = (hue + rng.nextFloat() * 0.25f) % 1f
            particles.add(p)
        }
    }

    private fun updateParticles(dt: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) { particles.removeAt(i); pool.addLast(p) }
            else {
                p.vy -= 7f * dt
                p.x += p.vx * dt
                p.z += p.vz * dt
                p.y = maxOf(0f, p.y + p.vy * dt)
                p.vx *= 0.97f; p.vz *= 0.97f
            }
            i--
        }
    }
}
