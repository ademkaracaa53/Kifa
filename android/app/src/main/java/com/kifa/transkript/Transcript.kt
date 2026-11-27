package com.kifa.transkript

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Bir konuşma parçası: Whisper metni, süresi ve (varsa) konuşmacı ses izi. */
/** tr: bu parçanın çevirisi (canlı çeviri açıkken). */
class Piece(val t: String, val d: Float, val e: String?, var tr: String? = null)

/** trFull: elle düzeltilmiş paragrafın baştan çevirisi (varsa parça çevirilerinin yerine geçer). */
class Para(var spk: Int, var text: String, val pieces: MutableList<Piece> = mutableListOf(), var edited: Boolean = false, var trFull: String? = null) {
    fun translation(): String = trFull ?: pieces.mapNotNull { it.tr }.joinToString(" ").trim()
}

/** Uygulamanın tüm metin durumu. Yalnızca ana iş parçacığında değiştirilir. */
object Transcript {
    var title = ""
    val paras = mutableListOf<Para>()
    val names = HashMap<Int, String>()
    var manual = false   // kullanıcı konuşmacıları elle değiştirdi: otomatik düzenleme bunu bozmasın

    fun name(id: Int) = names[id] ?: "Konuşmacı $id"

    fun words() = paras.sumOf { p -> p.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } }
    fun speakerCount() = paras.map { it.spk }.filter { it > 0 }.toSet().size

    fun clear() { title = ""; paras.clear(); names.clear(); manual = false }

    fun joinNeighbours() {
        val out = mutableListOf<Para>()
        for (p in paras) {
            val last = out.lastOrNull()
            if (last != null && last.spk == p.spk && p.spk > 0) {
                if (last.trFull != null || p.trFull != null) last.trFull = (last.translation() + " " + p.translation()).trim()
                last.text = (last.text + " " + p.text).trim()
                last.pieces.addAll(p.pieces)
                last.edited = last.edited || p.edited
            } else out.add(p)
        }
        paras.clear(); paras.addAll(out)
    }

    fun dateText(): String = DateFormat.getDateInstance(DateFormat.LONG, Locale("tr", "TR")).format(Date())

    fun plainText(): String {
        val sb = StringBuilder()
        if (title.isNotBlank()) sb.append(title.trim()).append('\n')
        sb.append(dateText()).append("\n\n")
        for (p in paras) {
            if (p.spk > 0) sb.append(name(p.spk)).append(": ")
            sb.append(p.text.trim()).append("\n")
            val tr = p.translation()
            if (tr.isNotEmpty()) sb.append("→ ").append(tr).append("\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    // ---------- kalıcı kayıt ----------
    private fun file(ctx: Context) = File(ctx.filesDir, "transcript.json")

    fun save(ctx: Context) {
        val o = JSONObject()
        o.put("title", title); o.put("manual", manual)
        val n = JSONObject(); names.forEach { (k, v) -> n.put(k.toString(), v) }; o.put("names", n)
        val a = JSONArray()
        for (p in paras) {
            val po = JSONObject().put("spk", p.spk).put("text", p.text).put("edited", p.edited).put("trFull", p.trFull ?: JSONObject.NULL)
            val pa = JSONArray()
            for (x in p.pieces) pa.put(JSONObject().put("t", x.t).put("d", x.d.toDouble()).put("e", x.e ?: JSONObject.NULL).put("tr", x.tr ?: JSONObject.NULL))
            po.put("pieces", pa); a.put(po)
        }
        o.put("paras", a)
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString()); tmp.renameTo(f)
    }

    fun load(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            clear()
            title = o.optString("title"); manual = o.optBoolean("manual")
            o.optJSONObject("names")?.let { n -> n.keys().forEach { k -> names[k.toInt()] = n.getString(k) } }
            val a = o.optJSONArray("paras") ?: JSONArray()
            for (i in 0 until a.length()) {
                val po = a.getJSONObject(i)
                val p = Para(po.optInt("spk"), po.optString("text"), edited = po.optBoolean("edited"),
                    trFull = if (po.isNull("trFull")) null else po.optString("trFull"))
                val pa = po.optJSONArray("pieces") ?: JSONArray()
                for (j in 0 until pa.length()) {
                    val x = pa.getJSONObject(j)
                    p.pieces.add(Piece(x.optString("t"), x.optDouble("d", 1.0).toFloat(), if (x.isNull("e")) null else x.optString("e"),
                        if (x.isNull("tr")) null else x.optString("tr")))
                }
                paras.add(p)
            }
        } catch (e: Exception) { clear() }
    }
}
