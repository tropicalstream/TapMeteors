package com.tapmeteors.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlin.random.Random

/**
 * The space sweeper's voice — a chronically under-appreciated custodian of
 * the cosmos. Clips are pre-generated fish.audio S2.1-Pro MP3s (voice model
 * 1864d40339ae4dbabf832f844c8d1d6f) baked by tools/generate_tts.py into
 * assets/tts/<id>.mp3 (or <id>_<n>.mp3 for a phrase with several variants).
 * Until they exist, Android TTS pitched LOW stands in.
 *
 * EVERYTHING runs on a dedicated background thread. TextToSpeech.speak() and
 * MediaPlayer.prepare() can block for tens of milliseconds; the game calls
 * say() from the GL render thread, so doing that work inline would hitch the
 * frame (a stutter every time a line fired). say() now only posts a message.
 *
 * A phrase id may map to several variant lines (a JSON array) — one is picked
 * at random each time so frequent lines don't repeat.
 */
class Voice(private val context: Context) {

    private val TAG = "TapMeteorsVoice"

    @Volatile var volume = 1.0f

    /** True while a line is actually sounding — lets Sfx duck around it. */
    @Volatile var isSpeaking = false
        private set

    private val phrases = HashMap<String, List<String>>()
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val queue = ArrayDeque<String>()          // touched only on the voice thread
    private val rng = Random(System.nanoTime())

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun load() {
        thread = HandlerThread("tapmeteors-voice").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post { initOnThread() }
    }

    private fun initOnThread() {
        runCatching {
            val txt = context.assets.open("phrases.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            for (k in o.keys()) {
                val v = o.get(k)
                phrases[k] = if (v is JSONArray) List(v.length()) { i -> v.getString(i) } else listOf(v.toString())
            }
        }.onFailure { Log.e(TAG, "phrases.json", it) }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setPitch(0.78f)          // fallback melancholy
                tts?.setSpeechRate(0.92f)
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { isSpeaking = false; postPump() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { isSpeaking = false; postPump() }
                })
                ttsReady = true
            }
        }
    }

    /** Called from the render thread — must return instantly. Just posts. */
    fun say(id: String, urgent: Boolean = false) {
        if (volume <= 0.01f) return
        val h = handler ?: return
        h.post { sayOnThread(id, urgent) }
    }

    private fun sayOnThread(id: String, urgent: Boolean) {
        if (!phrases.containsKey(id)) return
        if (urgent) {
            queue.clear()
            stopCurrent()
            queue.add(id)
        } else {
            // One pending mutter at most; the sweeper doesn't backlog complaints.
            if (isSpeaking || queue.isNotEmpty()) return
            queue.add(id)
        }
        pumpOnThread()
    }

    private fun postPump() { handler?.post { pumpOnThread() } }

    private fun pumpOnThread() {
        if (isSpeaking) return
        val id = queue.pollFirst() ?: return
        val variants = phrases[id]
        if (variants.isNullOrEmpty()) return
        val idx = if (variants.size > 1) rng.nextInt(variants.size) else 0
        val clipId = if (variants.size > 1) "${id}_$idx" else id
        isSpeaking = true
        val clip = findClip(clipId)
        if (clip != null) playClip(clip) else speakFallback(variants[idx], clipId)
    }

    private fun findClip(clipId: String): Any? {
        val f = File(File(context.filesDir, "tts"), "$clipId.mp3")
        if (f.exists()) return f
        return runCatching { context.assets.openFd("tts/$clipId.mp3") }.getOrNull()
    }

    private fun playClip(src: Any) {
        runCatching {
            stopPlayer()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            when (src) {
                is File -> mp.setDataSource(src.absolutePath)
                is android.content.res.AssetFileDescriptor -> {
                    mp.setDataSource(src.fileDescriptor, src.startOffset, src.length)
                    src.close()
                }
            }
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener { isSpeaking = false; stopPlayer(); pumpOnThread() }
            mp.setOnErrorListener { _, _, _ -> isSpeaking = false; stopPlayer(); postPump(); true }
            mp.prepare()   // off the render thread now, so a blocking prepare is fine
            mp.start()
            player = mp
        }.onFailure { isSpeaking = false; Log.w(TAG, "clip failed", it) }
    }

    private fun speakFallback(text: String, utteranceId: String) {
        if (!ttsReady) { isSpeaking = false; return }
        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun stopCurrent() {
        stopPlayer()
        if (ttsReady) runCatching { tts?.stop() }
        isSpeaking = false
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    fun release() {
        handler?.post {
            stopCurrent()
            runCatching { tts?.shutdown() }
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
