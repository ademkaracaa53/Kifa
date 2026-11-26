package com.kifa.transkript

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "Transkript"
private const val SR = 16000

class Settings(val model: String, val lang: String, val diar: Boolean)

/**
 * Kayıt → konuşma algılama (Silero VAD) → Whisper (sherpa-onnx) → konuşmacı ses izi.
 * İki iş parçacığı: biri mikrofonu okuyup cümleleri ayırır, diğeri modelleri çalıştırır.
 * Sonuçlar ana iş parçacığına [Listener] ile bildirilir.
 */
object Engine {
    interface Listener {
        fun onText(text: String, emb: FloatArray?, dur: Float, session: Int)
        fun onInterim(text: String?)
        fun onLevel(level: Float)
        fun onStatus(text: String, error: Boolean = false)
        fun onStateChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    var listener: Listener? = null

    @Volatile var recording = false; private set
    @Volatile var working = false; private set      // model iş parçacığı hâlâ çalışıyor (kalan cümleler)
    @Volatile var modelReady = false; private set
    var startedAt = 0L; private set
    var session = 0; private set

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var loadedKey = ""

    private class Job(val samples: FloatArray, val session: Int)
    private val finals = LinkedBlockingQueue<Job>()
    private val curLock = Any()
    private val cur = ArrayList<FloatArray>()   // süren konuşmanın sesi (ara metin için)
    private var curLen = 0

    private fun post(f: () -> Unit) = main.post(f)
    private fun status(s: String, err: Boolean = false) = post { listener?.onStatus(s, err) }

    val pending get() = finals.size

    private var lastSettings: Settings? = null
    private val life = Any()

    /** false dönerse başlatılamadı (önceki kaydın kalan cümleleri farklı ayarla işleniyor). */
    fun start(ctx: Context, s: Settings): Boolean {
        if (recording) return true
        val app = ctx.applicationContext
        var reuse = false
        synchronized(life) {
            val prev = lastSettings
            reuse = working   // önceki kaydın model iş parçacığı hâlâ çalışıyorsa onu kullanmaya devam et
            if (reuse && (prev == null || prev.model != s.model || prev.lang != s.lang || prev.diar != s.diar)) {
                status("Önceki kaydın kalan cümleleri çevriliyor; ayar değişikliği için biraz bekleyin.", true)
                return false
            }
            lastSettings = s
            session++
            recording = true; working = true
        }
        startedAt = SystemClock.elapsedRealtime()
        synchronized(curLock) { cur.clear(); curLen = 0 }
        post { listener?.onStateChanged() }
        val sess = session
        if (!reuse) Thread({ workerLoop(app, s) }, "transkript-model").start()
        Thread({ recordLoop(app, sess) }, "transkript-mic").start()
        return true
    }

    fun stop() {
        if (!recording) return
        recording = false
        post { listener?.onStateChanged() }
    }

    // ---------- modeller ----------
    @Synchronized
    private fun ensureModels(ctx: Context, s: Settings) {
        val key = "${s.model}|${s.lang}"
        if (recognizer == null || loadedKey != key) {
            recognizer?.release(); recognizer = null; modelReady = false
            status("Model yükleniyor…")
            val threads = min(4, max(1, Runtime.getRuntime().availableProcessors() / 2))
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = Models.path(ctx, s.model, 0),
                        decoder = Models.path(ctx, s.model, 1),
                        language = if (s.lang == "auto") "" else s.lang,
                        task = "transcribe",
                    ),
                    tokens = Models.path(ctx, s.model, 2),
                    modelType = "whisper",
                    numThreads = threads,
                ),
            )
            recognizer = OfflineRecognizer(null, cfg)
            loadedKey = key
        }
        if (vad == null) {
            vad = Vad(
                ctx.assets,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = Models.VAD_ASSET, threshold = 0.5f,
                        minSilenceDuration = 0.5f, minSpeechDuration = 0.25f,
                        windowSize = 512, maxSpeechDuration = 14f,
                    ),
                    sampleRate = SR, numThreads = 1,
                ),
            )
        }
        if (s.diar && extractor == null) {
            extractor = SpeakerEmbeddingExtractor(ctx.assets, SpeakerEmbeddingExtractorConfig(model = Models.SPK_ASSET, numThreads = 2))
        }
        modelReady = true
    }

    private fun transcribe(samples: FloatArray): String {
        val r = recognizer ?: return ""
        val st = r.createStream()
        st.acceptWaveform(samples, SR)
        r.decode(st)
        val text = r.getResult(st).text
        st.release()
        return text
    }

    private fun embed(samples: FloatArray): FloatArray? {
        val ex = extractor ?: return null
        // ses izi için 6 sn yeterli; uzun bölümde ortadaki kısmı al
        val maxN = SR * 6
        val a = if (samples.size > maxN) { val s0 = (samples.size - maxN) / 2; samples.copyOfRange(s0, s0 + maxN) } else samples
        val st = ex.createStream()
        st.acceptWaveform(a, SR)
        st.inputFinished()
        val out = if (ex.isReady(st)) ex.compute(st) else null
        st.release()
        return out
    }

    // ---------- model iş parçacığı ----------
    private fun workerLoop(ctx: Context, s: Settings) {
        try {
            ensureModels(ctx, s)
            status("Dinleniyor…")
            post { listener?.onStateChanged() }
        } catch (e: Throwable) {
            Log.e(TAG, "model load", e)
            synchronized(life) { recording = false; working = false; finals.clear() }
            status("Model yüklenemedi: ${e.message}", true)
            post { listener?.onStateChanged() }
            return
        }
        var lastInterim = 0L
        while (true) {
            synchronized(life) { if (!recording && finals.isEmpty()) { working = false } }
            if (!working) break
            val job = finals.poll(150, TimeUnit.MILLISECONDS)
            if (job != null) {
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    val text = transcribe(job.samples)
                    val dur = job.samples.size / SR.toFloat()
                    val emb = if (s.diar && dur >= 0.8f) embed(job.samples) else null
                    val took = (SystemClock.elapsedRealtime() - t0) / 1000f
                    post {
                        listener?.onInterim(null)
                        listener?.onText(text, emb, dur, job.session)
                    }
                    val left = finals.size
                    status(
                        (if (recording) "Dinleniyor" else "Kalan konuşmalar çevriliyor") +
                            " · son parça: %.1f sn ses → %.1f sn işlem".format(dur, took) +
                            (if (left > 1) " · $left parça sırada" else "")
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "decode", e)
                }
                continue
            }
            // boştayken: süren konuşmanın o ana kadarki kısmını çevir (ara metin)
            val now = SystemClock.elapsedRealtime()
            if (recording && now - lastInterim > 700) {
                val snap = synchronized(curLock) {
                    if (curLen < SR) null else {
                        val out = FloatArray(curLen); var o = 0
                        for (c in cur) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
                        out
                    }
                }
                if (snap != null) {
                    lastInterim = now
                    try {
                        val t = cleanText(transcribe(snap))
                        if (t.isNotEmpty() && finals.isEmpty()) post { listener?.onInterim(t) }
                    } catch (e: Throwable) { Log.e(TAG, "interim", e) }
                }
            }
        }
        status("")
        post { listener?.onInterim(null); listener?.onStateChanged() }
    }

    // ---------- mikrofon iş parçacığı ----------
    @SuppressLint("MissingPermission")
    private fun recordLoop(ctx: Context, sess: Int) {
        // VOICE_RECOGNITION: görüşme modu işlemesi (yankı engelleme vb.) olmadan ham ses
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, SR))
        } catch (e: Throwable) { null }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            recording = false
            status("Mikrofon açılamadı. Mikrofon iznini kontrol edin.", true)
            post { listener?.onStateChanged() }
            return
        }
        // modeller yüklenene kadar konuşmalar bekletilir (kaybolmaz)
        val early = ArrayList<FloatArray>()
        ar.startRecording()
        val buf = ShortArray(SR / 10)   // 100 ms
        val pre = ArrayDeque<FloatArray>()   // konuşma başlangıcından önceki ~0,4 sn
        var preLen = 0
        var pending = FloatArray(0)
        var inSpeech = false
        var lastLevel = 0L
        try {
            while (recording) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                val x = FloatArray(n) { buf[it] / 32768f }
                var e = 0.0; for (v in x) e += v * v
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevel > 80) { lastLevel = now; val lv = sqrt(e / n).toFloat(); post { listener?.onLevel(lv) } }

                val v = vad
                if (v == null || !modelReady) { early.add(x); continue }
                if (early.isNotEmpty()) { // bekletilen sesi önce işle
                    val all = early.flatMap { it.asIterable() }.toFloatArray(); early.clear()
                    pending = all
                }
                pending = if (pending.isEmpty()) x else pending + x
                var off = 0
                while (pending.size - off >= 512) {
                    val w = pending.copyOfRange(off, off + 512); off += 512
                    v.acceptWaveform(w)
                    if (v.isSpeechDetected()) {
                        synchronized(curLock) {
                            if (!inSpeech) { inSpeech = true; cur.clear(); curLen = 0; for (p in pre) { cur.add(p); curLen += p.size } }
                            cur.add(w); curLen += w.size
                        }
                    } else {
                        pre.addLast(w); preLen += w.size
                        while (preLen > SR * 2 / 5) { preLen -= pre.removeFirst().size }
                    }
                    while (!v.empty()) {
                        val seg = v.front(); v.pop()
                        finals.offer(Job(seg.samples, sess))
                        synchronized(curLock) { inSpeech = false; cur.clear(); curLen = 0 }
                    }
                }
                pending = pending.copyOfRange(off, pending.size)
            }
        } finally {
            try { ar.stop() } catch (_: Throwable) {}
            ar.release()
            vad?.let { v ->
                if (pending.isNotEmpty()) v.acceptWaveform(pending)
                v.flush()
                while (!v.empty()) { finals.offer(Job(v.front().samples, sess)); v.pop() }
                v.reset()
            }
            synchronized(curLock) { cur.clear(); curLen = 0 }
            post { listener?.onLevel(0f) }
        }
    }
}
