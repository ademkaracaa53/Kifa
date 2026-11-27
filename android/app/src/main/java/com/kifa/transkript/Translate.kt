package com.kifa.transkript

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Canlı çeviri: Google ML Kit çevirisi telefonda çalışır (ilk kullanımda dil modeli bir kez indirilir,
 * sonra internet gerekmez). Sonuçlar ana iş parçacığında döner.
 */
object Translate {
    /** "en-tr" ya da "tr-en" */
    private var dir = ""
    private var translator: Translator? = null
    @Volatile var ready = false; private set

    fun isReady(d: String) = ready && translator != null && dir == d
    fun source(d: String) = if (d == "tr-en") "tr" else "en"
    fun label(d: String) = if (d == "tr-en") "Türkçe → İngilizce" else "İngilizce → Türkçe"

    /** Çevirmeni hazırla (gerekirse modeli indir). */
    fun ensure(d: String, done: (error: String?) -> Unit) {
        if (translator != null && dir == d && ready) { done(null); return }
        translator?.close(); translator = null; ready = false
        dir = d
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(if (d == "tr-en") TranslateLanguage.TURKISH else TranslateLanguage.ENGLISH)
            .setTargetLanguage(if (d == "tr-en") TranslateLanguage.ENGLISH else TranslateLanguage.TURKISH)
            .build()
        val t = Translation.getClient(opts)
        translator = t
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { if (translator === t) { ready = true; done(null) } }
            .addOnFailureListener { e -> if (translator === t) done(e.message ?: "Çeviri modeli indirilemedi") }
    }

    /** Metni çevir; başarısızsa null. */
    fun translate(text: String, cb: (String?) -> Unit) {
        val t = translator
        if (t == null || !ready || text.isBlank()) { cb(null); return }
        t.translate(text)
            .addOnSuccessListener { cb(it) }
            .addOnFailureListener { cb(null) }
    }
}
