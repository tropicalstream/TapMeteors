package com.tapmeteors.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized SFX bank for TapMeteors (no audio binaries ship). The stars:
 * the two low THUMPs — the tension heartbeat that quickens as rocks thin
 * out — and the saucer's warbling siren, remixed as a portamento saw sweep
 * with vibrato that loops while the visitor prowls.
 */
class Sfx(private val context: Context) {

    companion object {
        const val TURN = 0
        const val THRUST = 1
        const val FIRE = 2
        const val EXPL_L = 3
        const val EXPL_M = 4
        const val EXPL_S = 5
        const val SHIP_DIE = 6
        const val SAUCER_FIRE = 7
        const val SAUCER_DIE = 8
        const val SAUCER_LOOP = 9   // looping warble while a saucer is on-field
        const val WARP = 10
        const val DROP = 11         // the big saucer's radial "beat drop"
        const val WAVE = 12
        const val CLEAR = 13
        const val LIFE = 14
        const val SPAWN = 15
        const val START = 16
        const val GAMEOVER = 17
        const val HISCORE = 18
        const val THUMP_LO = 19     // the heartbeat, low note
        const val THUMP_HI = 20     // the heartbeat, high note
        private const val COUNT = 21
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.9f
    private var saucerStream = 0
    private val rng = Random(11)

    fun loadAsync() {
        Thread {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                ids[TURN] = load(dir, "turn", buf(60) { t -> sq(760f + 500f * t, t) * exp(-t * 26f) * 0.35f })
                ids[THRUST] = load(dir, "thrust", synthThrust())
                ids[FIRE] = load(dir, "fire", buf(90) { t -> (saw(1300f - 700f * t, t) + 0.2f * noise()) * exp(-t * 26f) * 0.4f })
                ids[EXPL_L] = load(dir, "explL", synthExplosion(650, 140f, 3.4f))
                ids[EXPL_M] = load(dir, "explM", synthExplosion(430, 220f, 5f))
                ids[EXPL_S] = load(dir, "explS", synthExplosion(280, 330f, 7f))
                ids[SHIP_DIE] = load(dir, "die", synthShipDie())
                ids[SAUCER_FIRE] = load(dir, "sfire", buf(240) { t -> (saw(480f - 260f * t, t) * 0.5f + 0.3f * noise() * exp(-t * 25f)) * exp(-t * 7f) })
                ids[SAUCER_DIE] = load(dir, "sdie", synthSaucerDie())
                ids[SAUCER_LOOP] = load(dir, "sloop", synthSaucerLoop())
                ids[WARP] = load(dir, "warp", synthWarp())
                ids[DROP] = load(dir, "drop", synthDrop())
                ids[WAVE] = load(dir, "wave", arpeggio(intArrayOf(392, 523, 659, 784), 85, 0.7f))
                ids[CLEAR] = load(dir, "clear", arpeggio(intArrayOf(523, 659, 784, 1046, 1318), 80, 0.7f))
                ids[LIFE] = load(dir, "life", arpeggio(intArrayOf(784, 1046, 1318, 1568, 2093), 65, 0.7f))
                ids[SPAWN] = load(dir, "spawn", buf(320) { t -> sine(280f + 900f * t, t) * exp(-t * 6f) * 0.5f })
                ids[START] = load(dir, "start", arpeggio(intArrayOf(330, 440, 554, 659, 880), 75, 0.7f))
                ids[GAMEOVER] = load(dir, "over", synthGameover())
                ids[HISCORE] = load(dir, "hi", arpeggio(intArrayOf(523, 659, 784, 1046, 1318, 1568, 2093), 80, 0.7f))
                ids[THUMP_LO] = load(dir, "thlo", synthThump(55f))
                ids[THUMP_HI] = load(dir, "thhi", synthThump(65f))
                loaded = true
            }
        }.start()
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        val s = ids[id]; if (s == 0) return
        val v = (volume * vol).coerceIn(0f, 1f); if (v <= 0f) return
        pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun startSaucerLoop() {
        if (!loaded || saucerStream != 0) return
        val v = (volume * 0.4f).coerceIn(0f, 1f)
        saucerStream = pool.play(ids[SAUCER_LOOP], v, v, 0, -1, 1f)
    }

    fun stopSaucerLoop() { if (saucerStream != 0) { pool.stop(saucerStream); saucerStream = 0 } }
    fun release() { runCatching { pool.release() } }

    // ------------------------------------------------------------ synthesis

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f
    private fun noise() = rng.nextFloat() * 2f - 1f

    /** The heartbeat: a soft low sine knock with a felt-mallet attack. */
    private fun synthThump(f: Float) = buf(180) { t ->
        val body = sine(f, t) * exp(-t * 14f)
        val knock = noise() * exp(-t * 220f) * 0.25f
        (body + knock) * 0.9f
    }

    private fun synthThrust() = buf(240) { t ->
        (noise() * 0.5f + saw(90f + 60f * t, t) * 0.4f) * exp(-t * 9f)
    }

    private fun synthExplosion(ms: Int, f0: Float, decay: Float) = buf(ms) { t ->
        val crush = if ((t * 34f).toInt() % 2 == 0) 1f else 0.55f
        ((noise() * 0.65f + sq(f0 * (1f - t * 0.6f), t) * 0.35f) * crush) * exp(-t * decay)
    }

    private fun synthShipDie() = buf(900) { t ->
        val f = 240f - t * 160f
        val crush = if ((t * 26f).toInt() % 2 == 0) 1f else 0.4f
        ((noise() * 0.55f + saw(f, t) * 0.45f) * crush) * exp(-t * 3.2f)
    }

    private fun synthSaucerDie() = buf(700) { t ->
        val f = 900f - t * 750f
        (saw(f, t) * 0.5f + sine(f * 0.5f, t) * 0.3f + noise() * 0.25f * exp(-t * 8f)) * exp(-t * 4f)
    }

    /** The remixed warble: portamento saw sweep with vibrato, loopable. */
    private fun synthSaucerLoop(): ShortArray = buf(1000) { t ->
        val sweep = 380f + 260f * sin(2f * PI.toFloat() * 1f * t)   // 1 Hz up-down glide
        val vib = 1f + 0.04f * sin(2f * PI.toFloat() * 12f * t)     // fast shimmer
        (saw(sweep * vib, t) * 0.45f + sine(sweep * 0.5f, t) * 0.25f) * 0.8f
    }

    private fun synthWarp() = buf(500) { t ->
        sine(1600f - 1200f * t, t) * exp(-t * 4f) * 0.4f +
            sine(200f + 800f * t, t) * exp(-t * 5f) * 0.3f
    }

    /** The beat drop: sub boom + rising zap ring. */
    private fun synthDrop() = buf(450) { t ->
        val boom = sine(48f, t) * exp(-t * 6f) * 0.8f
        val ring = saw(300f + 900f * t, t) * exp(-t * 7f) * 0.3f
        boom + ring
    }

    private fun synthGameover() = buf(1000) { t ->
        val f = if (t < 0.4f) 330f - t * 180f else 260f - (t - 0.4f) * 140f
        (saw(f, t) * 0.4f + sine(f * 0.5f, t) * 0.4f) * exp(-t * 2f)
    }

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
