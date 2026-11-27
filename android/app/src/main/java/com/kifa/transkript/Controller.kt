package com.kifa.transkript

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/**
 * Ekrandan bağımsız oturum mantığı: Engine sonuçlarını alır, konuşmacı atar, metne ekler, kaydeder.
 * Ekran kapalıyken de çalışır; açık bir ekran varsa [ui] üzerinden haber verir.
 */
object Controller : Engine.Listener {
    interface Ui {
        fun appended(p: Para, newPara: Boolean)
        fun rerender()
        fun interim(text: String?)
        fun level(level: Float)
        fun status(text: String, error: Boolean)
        fun stateChanged()
        fun message(text: String)
    }

    var ui: Ui? = null
    private lateinit var app: Context
    lateinit var prefs: SharedPreferences; private set
    private val main = Handler(Looper.getMainLooper())

    val speakers = Speakers()
    private var sinceRegroup = 0
    private var needRegroup = false
    private var lastSession = -1
    var lastStatus = ""; private set
    var lastStatusError = false; private set

    val lang get() = prefs.getString("lang", "tr")!!
    val model get() = prefs.getString("model", Models.DEFAULT)!!
    val maxSpk get() = prefs.getInt("maxSpk", 0)
    val cloud get() = prefs.getString("engine", "local") == "cloud"
    val nemo get() = prefs.getString("engine", "local") == "nemo"
    /** İndirilmesi gereken cihaz modeli: Nemotron ya da seçili Whisper */
    val modelKey get() = if (nemo) Models.NEMO else model
    val cloudUrl get() = prefs.getString("cloudUrl", "")!!
    val cloudCode get() = prefs.getString("cloudCode", "")!!
    val sens get() = prefs.getString("sens", "norm")!!
    val diarOn get() = maxSpk != 1
    fun spkThr() = when (sens) { "tol" -> 0.6f; "sens" -> 0.76f; else -> 0.68f }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("set", Context.MODE_PRIVATE)
        Transcript.load(app)
        applySpk()
        rebuildCentroids()
        Engine.listener = this
    }

    fun applySpk() {
        speakers.threshold = spkThr()
        speakers.max = if (maxSpk > 1) maxSpk else 8
    }

    fun start(ctx: Context): Boolean {
        sinceRegroup = 0
        return Engine.start(ctx, Settings(model, lang, diarOn, cloud, Cloud.normalizeUrl(cloudUrl), cloudCode.trim(), nemo))
    }

    // ---------- Engine.Listener (ana iş parçacığı) ----------
    override fun onText(text: String, emb: FloatArray?, dur: Float, session: Int) {
        val t = cleanText(text)
        if (t.isNotEmpty()) {
            val spk = if (diarOn) speakers.assign(emb, dur) else 0
            add(t, spk, Piece(t, dur, emb?.let { packEmb(it) }), session)
            if (Engine.recording && diarOn && ++sinceRegroup >= 8) { sinceRegroup = 0; liveRegroup() }
        }
        maybeFinalRegroup()
    }

    override fun onInterim(text: String?) { ui?.interim(text) }
    override fun onLevel(level: Float) { ui?.level(level) }
    override fun onStatus(text: String, error: Boolean) { lastStatus = text; lastStatusError = error; ui?.status(text, error) }
    override fun onStateChanged() {
        if (!Engine.recording) { needRegroup = true; maybeFinalRegroup() }
        ui?.stateChanged()
    }

    private fun maybeFinalRegroup() {
        if (needRegroup && !Engine.recording && !Engine.working) {
            needRegroup = false
            if (!Transcript.manual && diarOn && regroup()) ui?.message("Konuşmacılar tüm konuşmaya göre yeniden düzenlendi")
            ui?.stateChanged()
        }
    }

    private fun add(text: String, spk: Int, piece: Piece, session: Int) {
        val last = Transcript.paras.lastOrNull()
        val limit = if (spk > 0) 1800 else 900
        val cont = last != null && last.spk == spk && session == lastSession && last.text.length < limit
        lastSession = session
        if (cont) {
            last!!.text = (last.text.trimEnd() + " " + text).trim()
            last.pieces.add(piece)
            ui?.appended(last, false)
        } else {
            if (spk > 0 && !Transcript.names.containsKey(spk)) Transcript.names[spk] = Transcript.name(spk)
            val p = Para(spk, text, mutableListOf(piece))
            Transcript.paras.add(p)
            ui?.appended(p, true)
        }
        persist()
    }

    // ---------- konuşmacıları yeniden düzenleme ----------
    private class Part(val text: String, val pieces: List<Piece>, val v: FloatArray?, val w: Float, val edited: Boolean, var spk: Int = 0)

    /** Tüm ses izlerine bakarak konuşmacıları yeniden grupla; değişiklik olduysa true. */
    fun regroup(): Boolean {
        if (!diarOn) return false
        val parts = mutableListOf<Part>()
        for (p in Transcript.paras) {
            val joined = p.pieces.joinToString(" ") { it.t }.trim()
            if (!p.edited && p.pieces.isNotEmpty() && joined == p.text.trim()) {
                for (x in p.pieces) parts += Part(x.t, listOf(x), x.e?.let { unpackEmb(it) }, x.d, false)
            } else {
                var v: FloatArray? = null
                var w = 0f
                for (x in p.pieces) {
                    val a = x.e?.let { unpackEmb(it) } ?: continue
                    val acc = v
                    v = if (acc == null) FloatArray(a.size) { a[it] * x.d } else FloatArray(acc.size) { acc[it] + a[it] * x.d }
                    w += x.d
                }
                parts += Part(p.text, p.pieces.toList(), v, if (w > 0) w else 1f, true)
            }
        }
        val withV = parts.filter { it.v != null }
        if (withV.size < 3 || withV.size > 600) return false
        val r = recluster(withV.map { Item(it.v!!, it.w) }, spkThr(), if (maxSpk > 1) maxSpk else 0)
        withV.forEachIndexed { i, u -> u.spk = r.labels[i] }
        var prev = 0
        for (u in parts) { if (u.spk > 0) prev = u.spk else u.spk = prev }
        var next = 1
        for (u in parts.asReversed()) { if (u.spk > 0) next = u.spk else u.spk = next }
        val paras = mutableListOf<Para>()
        for (u in parts) {
            val last = paras.lastOrNull()
            if (last != null && last.spk == u.spk && last.text.length < 1800) {
                last.text = (last.text + " " + u.text).trim(); last.pieces.addAll(u.pieces); if (u.edited) last.edited = true
            } else paras += Para(u.spk, u.text, u.pieces.toMutableList(), u.edited)
        }
        val sig = { ps: List<Para> -> ps.joinToString("\u0001") { "${it.spk}\u0002${it.text}" } }
        val changed = sig(paras) != sig(Transcript.paras)
        Transcript.paras.clear(); Transcript.paras.addAll(paras); Transcript.names.clear()
        speakers.reset(r.centroids, paras.lastOrNull()?.spk ?: 0)
        if (changed) ui?.rerender()
        persist()
        return changed
    }

    private fun liveRegroup() {
        if (Transcript.manual) return
        if (Transcript.paras.sumOf { it.pieces.size } > 400) return  // çok uzun toplantıda yalnızca durdurunca
        regroup()
    }

    /** Ayar değişince (kayıt yokken) mevcut metni yeniden düzenle. */
    fun settingsChanged(): Boolean {
        applySpk()
        return !Engine.recording && !Engine.working && !Transcript.manual && diarOn && regroup()
    }

    /** Mevcut etiketlerden konuşmacı ortalamalarını yeniden kur (açılışta, elle değişiklikten sonra). */
    fun rebuildCentroids() {
        val sums = LinkedHashMap<Int, FloatArray>()
        for (p in Transcript.paras) for (x in p.pieces) {
            val a = x.e?.let { unpackEmb(it) } ?: continue
            val s = sums.getOrPut(p.spk) { FloatArray(a.size) }
            for (i in a.indices) s[i] += a[i] * x.d
        }
        speakers.cents.clear()
        sums.forEach { (id, v) -> if (id > 0) speakers.cents.add(Centroid(id, norm(v), 10)) }
        speakers.last = Transcript.paras.lastOrNull()?.spk ?: 0
    }

    fun clear() {
        Transcript.clear(); speakers.reset(emptyList(), 0); lastSession = -1
        ui?.rerender(); persist()
    }

    private val saveRunnable = Runnable { Transcript.save(app) }
    fun persist() { main.removeCallbacks(saveRunnable); main.postDelayed(saveRunnable, 800) }
    fun saveNow() { main.removeCallbacks(saveRunnable); Transcript.save(app) }
}
