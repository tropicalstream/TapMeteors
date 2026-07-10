package com.tapmeteors.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
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
 * assets/tts/<id>.mp3 (or <id>_<n>.mp3 for a phrase with several variants);
 * no network at run time. Until they're generated, Android TTS pitched LOW
 * and slowed stands in — gloom is non-negotiable.
 *
 * A phrase id may map to several variant lines in phrases.json (a JSON
 * array instead of a single string) — one is picked at random each time so
 * frequent lines (an imperial ship showing up, say) don't repeat.
 */
class Voice(private val context: Context) {

    private val TAG = "TapMeteorsVoice"

    @Volatile var volume = 1.0f

    /** True while a line is actually sounding — lets Sfx duck around it. */
    val isSpeaking: Boolean get() = speaking

    private val phrases = HashMap<String, List<String>>()
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val queue = ArrayDeque<String>()
    @Volatile private var speaking = false
    private val rng = Random(System.nanoTime())

    fun load() {
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
                ttsReady = true
                tts?.language = Locale.UK
                tts?.setPitch(0.78f)          // fallback melancholy
                tts?.setSpeechRate(0.92f)
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { speaking = false; pump() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { speaking = false; pump() }
                })
            }
        }
    }

    /** Queue a line. urgent (death, game over) clears the queue and interrupts. */
    fun say(id: String, urgent: Boolean = false) {
        if (volume <= 0.01f) return
        if (!phrases.containsKey(id)) return
        synchronized(queue) {
            if (urgent) {
                queue.clear()
                stopCurrent()
                queue.add(id)
            } else {
                // One pending mutter at most; the sweeper doesn't backlog complaints.
                if (speaking || queue.isNotEmpty()) return
                queue.add(id)
            }
        }
        pump()
    }

    private fun pump() {
        val id: String
        synchronized(queue) {
            if (speaking) return
            id = queue.pollFirst() ?: return
            speaking = true
        }
        val variants = phrases[id]
        if (variants.isNullOrEmpty()) { speaking = false; return }
        val idx = if (variants.size > 1) rng.nextInt(variants.size) else 0
        val clipId = if (variants.size > 1) "${id}_$idx" else id
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
            mp.setOnCompletionListener { speaking = false; stopPlayer(); pump() }
            mp.setOnErrorListener { _, _, _ -> speaking = false; stopPlayer(); pump(); true }
            mp.prepare()
            mp.start()
            player = mp
        }.onFailure { speaking = false; Log.w(TAG, "clip failed", it) }
    }

    private fun speakFallback(text: String, utteranceId: String) {
        if (!ttsReady) { speaking = false; return }
        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun stopCurrent() {
        stopPlayer()
        if (ttsReady) runCatching { tts?.stop() }
        speaking = false
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    fun release() {
        stopCurrent()
        runCatching { tts?.shutdown() }
    }
}
