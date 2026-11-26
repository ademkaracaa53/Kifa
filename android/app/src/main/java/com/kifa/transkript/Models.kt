package com.kifa.transkript

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class WhisperModel(val key: String, val label: String)

/**
 * Whisper modelleri (sherpa-onnx int8 sürümleri) ilk kullanımda Hugging Face'ten indirilir:
 * https://huggingface.co/csukuangfj/sherpa-onnx-whisper-<model>
 * Konuşma algılama (silero_vad.onnx) ve konuşmacı ses izi modelleri APK'nın içinde gelir.
 */
object Models {
    val list = listOf(
        WhisperModel("tiny", "Hızlı (tiny) · en zayıf doğruluk"),
        WhisperModel("base", "Dengeli (base)"),
        WhisperModel("small", "Doğru (small) · önerilen"),
        WhisperModel("turbo", "En doğru (turbo) · büyük, yavaş"),
    )
    const val DEFAULT = "small"
    const val VAD_ASSET = "silero_vad.onnx"
    const val SPK_ASSET = "speaker.onnx"

    fun dir(ctx: Context, key: String) = File(ctx.filesDir, "models/whisper-$key")
    fun files(key: String) = listOf("$key-encoder.int8.onnx", "$key-decoder.int8.onnx", "$key-tokens.txt")
    private fun url(key: String, f: String) = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$key/resolve/main/$f"

    fun isReady(ctx: Context, key: String) = files(key).all { File(dir(ctx, key), it).let { f -> f.exists() && f.length() > 0 } }
    fun path(ctx: Context, key: String, i: Int) = File(dir(ctx, key), files(key)[i]).absolutePath

    fun sizeOnDisk(ctx: Context, key: String) = files(key).sumOf { File(dir(ctx, key), it).length() }

    private fun open(u: String, method: String = "GET"): HttpURLConnection {
        var url = URL(u)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Transkript-Android")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Yönlendirme adresi yok")
                url = URL(url, loc); c.disconnect(); return@repeat
            }
            if (code !in 200..299) throw IOException("Sunucu yanıtı $code: ${url.host}")
            return c
        }
        throw IOException("Çok fazla yönlendirme")
    }

    /** Engelleyici indirme; ilerleme (indirilen, toplam bayt) ile bildirilir. */
    fun download(ctx: Context, key: String, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val d = dir(ctx, key); d.mkdirs()
        val todo = files(key).filter { !File(d, it).exists() || File(d, it).length() == 0L }
        val sizes = todo.map { f -> open(url(key, f), "HEAD").let { c -> val n = c.contentLengthLong; c.disconnect(); maxOf(n, 0L) } }
        val total = sizes.sum()
        var done = 0L
        for (f in todo) {
            if (cancelled()) throw IOException("İptal edildi")
            val tmp = File(d, "$f.part")
            val c = open(url(key, f))
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { last = now; progress(done, total) }
                        if (cancelled()) throw IOException("İptal edildi")
                    }
                }
            }
            c.disconnect()
            if (!tmp.renameTo(File(d, f))) throw IOException("Dosya kaydedilemedi: $f")
        }
        progress(total, total)
    }
}
