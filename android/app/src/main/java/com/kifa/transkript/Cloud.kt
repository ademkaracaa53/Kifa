package com.kifa.transkript

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Bulut motoru: kullanıcının kendi Cloudflare Worker'ına (cloudflare/worker.js) konuşma parçasını
 * WAV olarak gönderir; Worker, Cloudflare Workers AI'daki Whisper large-v3-turbo ile metne çevirir.
 */
object Cloud {
    class CloudError(msg: String, val fatal: Boolean) : IOException(msg)

    fun wav(samples: FloatArray, sr: Int = 16000): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in samples) data.putShort((max(-1f, min(1f, x)) * 32767f).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + samples.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(sr); h.putInt(sr * 2)
        h.putShort(2); h.putShort(16); h.put("data".toByteArray()); h.putInt(samples.size * 2)
        val out = ByteArrayOutputStream(44 + samples.size * 2)
        out.write(h.array()); out.write(data.array())
        return out.toByteArray()
    }

    /** HTTP başlıkları Türkçe harfleri bozar: kodu URL kodlamasıyla gönder (sunucu çözer). */
    fun encodeCode(code: String): String =
        URLEncoder.encode(java.text.Normalizer.normalize(code.trim(), java.text.Normalizer.Form.NFC), "UTF-8").replace("+", "%20")

    fun normalizeUrl(u: String): String {
        var s = u.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        return s.trimEnd('/')
    }

    /** Parçayı metne çevir. Geçici hatalarda 2 kez yeniden dener. */
    fun transcribe(s: Settings, samples: FloatArray): String {
        val body = wav(samples)
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                return once(s, body)
            } catch (e: CloudError) {
                if (e.fatal) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            Thread.sleep(400L * (attempt + 1))
        }
        throw last ?: IOException("Sunucuya ulaşılamadı")
    }

    private fun once(s: Settings, body: ByteArray): String {
        val url = URL(normalizeUrl(s.url) + "/?lang=" + URLEncoder.encode(s.lang, "UTF-8"))
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("Content-Type", "audio/wav")
            c.setRequestProperty("x-access-code", encodeCode(s.code))
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val o = try { JSONObject(text) } catch (e: Exception) { null }
            return when {
                code in 200..299 -> o?.optString("text") ?: ""
                code == 401 -> throw CloudError("Erişim kodu yanlış (ayarlardan kontrol edin)", true)
                code == 429 -> throw CloudError("Cloudflare günlük ücretsiz kotası doldu ya da sınıra takıldı", true)
                code == 404 -> throw CloudError("Sunucu adresi bulunamadı (ayarlardan kontrol edin)", true)
                else -> throw CloudError("Sunucu hatası $code: ${o?.optString("error") ?: text.take(120)}", code in 400..499)
            }
        } finally {
            c.disconnect()
        }
    }

    /** Ayarlar ekranındaki "Bağlantıyı dene" düğmesi için. */
    fun check(url: String, code: String): String {
        val s = Settings("", "tr", false, cloud = true, url = url, code = code)
        // 1 sn sessizlik: sunucu, kod ve model zinciri uçtan uca denenir
        once(s, wav(FloatArray(16000)))
        return "Bağlantı başarılı"
    }
}
