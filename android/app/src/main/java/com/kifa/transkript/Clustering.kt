package com.kifa.transkript

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Web sürümündeki (transkript/engine.js) konuşmacı mantığının Kotlin karşılığı.

fun norm(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x * x
    val d = sqrt(s).toFloat().takeIf { it > 0f } ?: 1f
    return FloatArray(v.size) { v[it] / d }
}

fun cos(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

class Centroid(val id: Int, var v: FloatArray, var n: Int)

/**
 * Canlı konuşmacı ataması. Yanlışlıkla yeni konuşmacı açmamak için:
 *  • benzerlik ≥ eşik → o konuşmacı
 *  • önceki konuşmacı en iyiye çok yakınsa onu tercih et (insanlar art arda konuşur)
 *  • eşiğin biraz altı (belirsiz bölge) → en yakın konuşmacı, ortalamayı bozmadan
 *  • yeni konuşmacıyı yalnızca ≥ 2 sn ve açıkça farklı bir bölüm açabilir
 */
class Speakers(var threshold: Float = 0.68f, var max: Int = 8) {
    val cents = mutableListOf<Centroid>()
    var last = 0

    fun nextId() = (cents.maxOfOrNull { it.id } ?: 0) + 1

    fun assign(emb: FloatArray?, dur: Float): Int {
        if (emb == null || emb.isEmpty()) return if (last > 0) last else newSpk(null)
        val v = norm(emb)
        var best: Centroid? = null
        var bestS = -2f
        var lastS = -2f
        for (c in cents) {
            val s = cos(v, c.v)
            if (s > bestS) { bestS = s; best = c }
            if (c.id == last) lastS = s
        }
        if (best == null) return newSpk(v)
        val soft = threshold - 0.1f
        var pick: Centroid = best
        var pickS = bestS
        if (last > 0 && best.id != last && lastS >= soft && lastS >= bestS - 0.04f) {
            cents.firstOrNull { it.id == last }?.let { pick = it; pickS = lastS }
        }
        val canCreate = dur >= 2f && cents.size < max
        if (pickS >= soft || !canCreate) {
            if (pickS >= threshold && dur >= 1.5f) update(pick, v)
            last = pick.id
            return last
        }
        return newSpk(v)
    }

    private fun newSpk(v: FloatArray?): Int {
        val id = nextId()
        if (v != null) cents.add(Centroid(id, v, 1))
        last = id
        return id
    }

    private fun update(c: Centroid, v: FloatArray) {
        val w = min(c.n, 20).toFloat()
        c.v = norm(FloatArray(v.size) { (c.v[it] * w + v[it]) / (w + 1) })
        c.n++
    }

    fun reset(centroids: List<FloatArray>, lastId: Int) {
        cents.clear()
        centroids.forEachIndexed { i, v -> cents.add(Centroid(i + 1, v, 10)) }
        last = lastId
    }
}

class Item(val v: FloatArray, val w: Float)
class ReclusterResult(val labels: IntArray, val centroids: List<FloatArray>)

/**
 * Tüm konuşma üzerinde toplu yeniden gruplama (ortalama bağlantılı hiyerarşik kümeleme).
 * k > 0 ise tam k gruba; değilse birleşme benzerliğinin en sert düştüğü yerden kişi sayısı seçilir.
 * Tek cümlelik ya da toplam sürenin %1,5'inden küçük gruplar en yakın büyük gruba katılır.
 */
fun recluster(items: List<Item>, threshold: Float, k: Int): ReclusterResult {
    val n = items.size
    class Cl(var idx: MutableList<Int>, var v: FloatArray, var w: Float, var on: Boolean = true)
    val cl = items.mapIndexed { i, it -> Cl(mutableListOf(i), norm(it.v), max(it.w, 0.1f)) }
    val S = Array(n) { FloatArray(n) }
    for (i in 0 until n) for (j in i + 1 until n) { val s = cos(cl[i].v, cl[j].v); S[i][j] = s; S[j][i] = s }

    fun snap() = cl.filter { it.on }.map { Cl(it.idx.toMutableList(), it.v.copyOf(), it.w) }
    val snaps = HashMap<Int, List<Cl>>()
    val mergeSim = HashMap<Int, Float>()
    var alive = n
    if (alive <= 9) snaps[alive] = snap()
    while (alive > 1) {
        var bi = -1; var bj = -1; var bs = -2f
        for (i in 0 until n) if (cl[i].on) for (j in i + 1 until n) if (cl[j].on && S[i][j] > bs) { bs = S[i][j]; bi = i; bj = j }
        mergeSim[alive] = bs
        val a = cl[bi]; val c = cl[bj]
        a.v = norm(FloatArray(a.v.size) { a.v[it] * a.w + c.v[it] * c.w })
        a.w += c.w; a.idx.addAll(c.idx)
        c.on = false; alive--
        for (j in 0 until n) if (cl[j].on && j != bi) { val s = cos(a.v, cl[j].v); S[bi][j] = s; S[j][bi] = s }
        if (alive <= 9) snaps[alive] = snap()
    }
    var K = if (k > 0) min(k, n) else 1
    if (k <= 0) {
        var bestGap = 0f
        for (m in 2..min(8, n)) {
            val refuse = mergeSim[m] ?: continue
            val did = mergeSim[m + 1] ?: 1f
            if (refuse >= threshold) continue
            val gap = did - refuse
            if (gap > bestGap) { bestGap = gap; K = m }
        }
    }
    var groups: List<Cl> = snaps[K] ?: snap()
    if (k <= 0) {
        val total = groups.sumOf { it.w.toDouble() }.toFloat()
        val big = groups.filter { it.idx.size >= 2 && it.w >= max(3f, total * 0.015f) }
        if (big.isEmpty() && groups.size > 1) {
            val all = groups[0]; for (g in groups.drop(1)) all.idx.addAll(g.idx)
            groups = listOf(all)
        } else if (big.isNotEmpty()) {
            for (g in groups.filter { it !in big }) {
                val best = big.maxByOrNull { cos(g.v, it.v) }!!
                best.idx.addAll(g.idx)
            }
            groups = big
        }
    }
    val lab = IntArray(n)
    groups.forEachIndexed { gi, g -> g.idx.forEach { lab[it] = gi } }
    val order = LinkedHashMap<Int, Int>()
    val labels = IntArray(n) { i -> order.getOrPut(lab[i]) { order.size + 1 } }
    return ReclusterResult(labels, order.keys.map { groups[it].v })
}

// Whisper'ın sessizlik/gürültüde uydurduğu bilinen kalıplar
private val HALLU = listOf(
    Regex("^[^a-zçğıöşü]*alt ?yaz[ıi]"), Regex("izledi[ğg]iniz i[çc]in te[şs]ekk[üu]r"), Regex("abone ol"),
    Regex("kanal[ıi]ma abone"), Regex("thanks? (you )?for watching"), Regex("please subscribe"),
    Regex("^[^a-zçğıöşü]*(music|müzik|alkış|applause)[^a-zçğıöşü]*$"),
    Regex("^[\\s.,!?♪♫*\\-–—…\"'()\\[\\]]*$"), Regex("^\\W*(\\[.*]|\\(.*\\))\\W*$"),
)
private val TR = java.util.Locale("tr", "TR")
private val REPEAT = Regex("(\\b[^.!?]{4,}?[.!?]?)(\\s+\\1){2,}", RegexOption.IGNORE_CASE)

fun cleanText(raw: String): String {
    var t = raw.replace(Regex("\\s+"), " ").trim()
    if (t.isEmpty()) return ""
    val low = t.lowercase(TR)
    if (HALLU.any { it.containsMatchIn(low) }) return ""
    t = REPEAT.replace(t) { it.groupValues[1] }
    return t
}

/** Ses izini 8 bit sıkıştır (512 sayı → ~700 karakter) — kayıt dosyası şişmesin. */
fun packEmb(e: FloatArray): String {
    val v = norm(e)
    var m = 1e-9f
    for (x in v) m = max(m, abs(x))
    val q = ByteArray(v.size) { Math.round(v[it] / m * 127f).toByte() }
    return m.toString() + ":" + android.util.Base64.encodeToString(q, android.util.Base64.NO_WRAP)
}

fun unpackEmb(s: String): FloatArray? = try {
    val (m, b) = s.split(":", limit = 2)
    val scale = m.toFloat()
    val q = android.util.Base64.decode(b, android.util.Base64.NO_WRAP)
    FloatArray(q.size) { q[it] * scale / 127f }
} catch (e: Exception) { null }
