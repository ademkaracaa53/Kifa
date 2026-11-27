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
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
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

class Settings(
    val model: String, val lang: String, val diar: Boolean,
    val cloud: Boolean = false, val url: String = "", val code: String = "",
    val nemo: Boolean = false,
) {
    fun same(o: Settings?) = o != null && o.model == model && o.lang == lang && o.diar == diar && o.cloud == cloud &&
        o.url == url && o.code == code && o.nemo == nemo
}

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
    private var online: OnlineRecognizer? = null   // NVIDIA Nemotron (akışlı)
    private var vad: Vad? = null
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var loadedKey = ""

    /** end = true: konuşma bitti (Nemotron'da cümleyi kapat), samples boş. */
    private class Job(val samples: FloatArray, val session: Int, val end: Boolean = false)
    private val finals = LinkedBlockingQueue<Job>()
    private val curLock = Any()
    private val cur = ArrayList<FloatArray>()   // süren konuşmanın sesi (ara metin için)
    private var curLen = 0

    private fun post(f: () -> Unit) { main.post { f() } }
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
            if (reuse && !s.same(prev)) {
                status("Önceki kaydın kalan cümleleri çevriliyor; ayar değişikliği için biraz bekleyin.", true)
                return false
            }
            lastSettings = s
            active = s
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
        active = s
        val key = "${s.model}|${s.lang}"
        val threads = min(4, max(1, Runtime.getRuntime().availableProcessors() / 2))
        // kullanılmayan büyük modeli bellekten bırak (ikisi birden telefonu zorlar)
        if (!s.nemo && online != null) { online?.release(); online = null }
        if ((s.nemo || s.cloud) && recognizer != null) { recognizer?.release(); recognizer = null; loadedKey = "" }
        if (s.nemo && online == null) {
            status("NVIDIA Nemotron modeli yükleniyor…")
            online = OnlineRecognizer(
                null,
                OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SR, featureDim = 80), // model kendi boyutunu kullanır
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = Models.path(ctx, Models.NEMO, 0),
                            decoder = Models.path(ctx, Models.NEMO, 1),
                            joiner = Models.path(ctx, Models.NEMO, 2),
                        ),
                        tokens = Models.path(ctx, Models.NEMO, 3),
                        // akışlı model sürekli çalışır: az çekirdek = az ısınma (güçlü telefonda 3)
                        numThreads = if (Runtime.getRuntime().availableProcessors() >= 8) 3 else 2,
                    ),
                    // cümle sonu: konuşmadan sonra 0,8 sn sessizlik ya da 15 sn'lik cümle
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.4f, 0.0f),
                        rule2 = EndpointRule(true, 0.8f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 15.0f),
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                ),
            )
        }
        if (!s.cloud && !s.nemo && (recognizer == null || loadedKey != key)) {
            recognizer?.release(); recognizer = null; modelReady = false
            status("Model yükleniyor…")
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

    @Volatile private var active: Settings? = null

    private fun transcribe(samples: FloatArray): String {
        val s = active
        if (s != null && s.cloud) return Cloud.transcribe(s, samples)
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
        if (s.nemo) { nemoLoop(s); return }
        var lastInterim = 0L
        var errMsg: String? = null
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
                    errMsg = null
                    val left = finals.size
                    status(
                        (if (recording) "Dinleniyor" else "Kalan konuşmalar çevriliyor") +
                            " · son parça: %.1f sn ses → %.1f sn işlem".format(dur, took) +
                            (if (left > 1) " · $left parça sırada" else "")
                    )
                } catch (e: Cloud.CloudError) {
                    Log.e(TAG, "cloud", e)
                    errMsg = "Bulut: ${e.message}"; status(errMsg!!, true)
                    // kod yanlış / kota doldu: kaydı sürdürmek anlamsız, durdur ki konuşmalar boşa gitmesin
                    if (e.fatal) { stop(); finals.clear() }
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "cloud io", e)
                    errMsg = "Bulut sunucusuna ulaşılamadı (internet?): ${e.message}"; status(errMsg!!, true)
                } catch (e: Throwable) {
                    Log.e(TAG, "decode", e)
                }
                continue
            }
            // boştayken: süren konuşmanın o ana kadarki kısmını çevir (ara metin)
            val now = SystemClock.elapsedRealtime()
            if (recording && !s.cloud && now - lastInterim > 700) {
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
        status(errMsg ?: "", errMsg != null)
        post { listener?.onInterim(null); listener?.onStateChanged() }
    }

    // ---------- NVIDIA Nemotron: akışlı tanıma ----------
    // Ses kesintisiz modele verilir; metin konuşurken büyür (ara metin), cümle sonunda (endpoint) kesinleşir.
    private val TR = java.util.Locale("tr", "TR")

    /** Modelin çıktısını cümleye çevir: baş harf büyük, sonda noktalama yoksa nokta. */
    private fun sentence(t: String): String {
        var x = t.replace(Regex("\\s+"), " ").trim()
        if (x.isEmpty()) return x
        x = x.substring(0, 1).uppercase(TR) + x.substring(1)
        if (x.last() !in ".?!…") x += "."
        return x
    }

    private fun nemoLoop(s: Settings) {
        val rec = online ?: return
        val st = rec.createStream()
        st.setOption("language", if (s.lang == "auto") "auto" else s.lang)
        status("Dinleniyor · anlık (NVIDIA Nemotron)")
        post { listener?.onStateChanged() }
        val seg = ArrayList<FloatArray>()
        var segLen = 0
        var sess = session
        var last = ""

        fun emit(raw: String) {
            val text = sentence(cleanText(raw))
            val audio = FloatArray(segLen)
            var o = 0
            for (c in seg) { System.arraycopy(c, 0, audio, o, c.size); o += c.size }
            seg.clear(); segLen = 0; last = ""
            val dur = audio.size / SR.toFloat()
            val emb = if (text.isNotEmpty() && s.diar && dur >= 0.8f) {
                try { embed(audio) } catch (e: Throwable) { null }
            } else null
            val ss = sess
            post {
                listener?.onInterim(null)
                if (text.isNotEmpty()) listener?.onText(text, emb, dur, ss)
            }
        }

        // cümle sonunda modelin elindeki son parça da çözülsün diye eklenen kısa sessizlik
        val tail = FloatArray(SR * 6 / 10)
        fun close() {
            if (segLen == 0) return
            st.acceptWaveform(tail, SR)
            while (rec.isReady(st)) rec.decode(st)
            emit(rec.getResult(st).text)
            rec.reset(st)
        }
        val batch = ArrayList<Job>()
        var warned = false
        try {
            while (true) {
                synchronized(life) { if (!recording && finals.isEmpty()) working = false }
                if (!working) break
                val first = finals.poll(150, TimeUnit.MILLISECONDS) ?: continue
                batch.clear(); batch.add(first); finals.drainTo(batch)
                // telefon yetişiyor mu? (sırada bekleyen ses)
                val backlog = finals.sumOf { it.samples.size } + batch.sumOf { it.samples.size }
                if (backlog > SR * 3 && !warned) { warned = true; status("Telefon yetişemiyor; metin gecikebilir. Whisper ya da Bulut motorunu deneyin.", true) }
                else if (backlog < SR && warned) { warned = false; status("Dinleniyor · anlık (NVIDIA Nemotron)") }
                var fed = false
                for (job in batch) {
                    if (job.session != sess) { close(); sess = job.session }
                    if (job.end) {
                        if (fed) { while (rec.isReady(st)) rec.decode(st); fed = false }
                        close()
                        continue
                    }
                    st.acceptWaveform(job.samples, SR)
                    seg.add(job.samples); segLen += job.samples.size
                    fed = true
                }
                if (!fed) continue
                while (rec.isReady(st)) rec.decode(st)
                val text = rec.getResult(st).text.trim()
                if (rec.isEndpoint(st)) {
                    emit(text)   // modelin kendi cümle sonu (yedek; çok uzun konuşmada)
                    rec.reset(st)
                } else if (text != last) {
                    last = text
                    val shown = sentence(text).trimEnd('.')
                    if (shown.isNotEmpty()) post { listener?.onInterim(shown) }
                }
            }
            // kayıt bitti: son cümleyi tamamla
            close()
        } catch (e: Throwable) {
            Log.e(TAG, "nemotron", e)
            status("Nemotron hatası: ${e.message}", true)
            synchronized(life) { recording = false; working = false; finals.clear() }
        } finally {
            st.release()
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

                val nemo = active?.nemo == true
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
                        if (nemo) {
                            // Nemotron: konuşma başlayınca önceki ~0,4 sn'yi ve sonra her parçayı hemen modele ver
                            if (!inSpeech) { inSpeech = true; for (p in pre) finals.offer(Job(p, sess)); pre.clear(); preLen = 0 }
                            finals.offer(Job(w, sess))
                        } else synchronized(curLock) {
                            if (!inSpeech) { inSpeech = true; cur.clear(); curLen = 0; for (p in pre) { cur.add(p); curLen += p.size } }
                            cur.add(w); curLen += w.size
                        }
                    } else {
                        pre.addLast(w); preLen += w.size
                        while (preLen > SR * 2 / 5) { preLen -= pre.removeFirst().size }
                    }
                    while (!v.empty()) {
                        val seg = v.front(); v.pop()
                        // Nemotron sesi zaten aldı: yalnızca "cümle bitti" işareti gönder
                        finals.offer(if (nemo) Job(FloatArray(0), sess, end = true) else Job(seg.samples, sess))
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
                val nemoEnd = active?.nemo == true
                while (!v.empty()) { finals.offer(if (nemoEnd) Job(FloatArray(0), sess, end = true) else Job(v.front().samples, sess)); v.pop() }
                if (nemoEnd) finals.offer(Job(FloatArray(0), sess, end = true))
                v.reset()
            }
            synchronized(curLock) { cur.clear(); curLen = 0 }
            post { listener?.onLevel(0f) }
        }
    }
}
