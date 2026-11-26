# Transkript

Toplantı, konferans ve sunumlarda konuşmayı tarayıcıda metne çeviren uygulama. Kurulum, sunucu ya da ücretli API gerekmez; GitHub Pages gibi herhangi bir statik barındırmada çalışır (mikrofon için `https` şart).

## Özellikler
- **Canlı (mikrofon):** tarayıcının yerleşik Web Speech API'si ile anlık yazıya dökme, zaman damgalı satırlar. Chrome, Edge, Safari'de çalışır. İsteğe bağlı olarak ses de kaydedilir; kayıt indirilebilir ya da Whisper ile yeniden, daha doğru çözülebilir.
- **Ses / video dosyası:** [Whisper](https://github.com/openai/whisper) modeli [transformers.js](https://github.com/huggingface/transformers.js) ile doğrudan tarayıcıda çalışır, ses hiçbir sunucuya gitmez. Tiny / Base / Small / Large v3 Turbo modelleri seçilebilir; WebGPU varsa onu, yoksa WASM'ı kullanır. Uzun kayıtlar ~4 dakikalık parçalarla işlenir, metin parça parça gelir.
- **Düzenleme:** her satır tıklanıp düzeltilebilir, silinebilir; başlık eklenir. Metin tarayıcıda otomatik saklanır.
- **Dışa aktarma:** TXT (UTF-8) ve PDF ([pdfmake](https://github.com/bpampuch/pdfmake), Türkçe karakterler tam). Zaman damgaları isteğe bağlı.
- **Çeviri (deneysel):** masaüstü Chrome 138+ içindeki cihaz içi Translator API ile satır satır çeviri; çeviri TXT/PDF'e de eklenir. Whisper'ın “İngilizceye çevirerek yaz” seçeneği her tarayıcıda çalışır.

## Dosyalar
- `index.html` — arayüz ve uygulama mantığı
- `worker.js` — Whisper'ı arayüzü dondurmadan çalıştıran Web Worker
- Kütüphaneler sabit sürümle jsDelivr'den yüklenir: `@huggingface/transformers@4.3.2`, `pdfmake@0.2.20`.

## Sonraki adımlar
- Chrome dışı tarayıcılar için cihaz içi çeviri: transformers.js ile NLLB-200 ya da OPUS-MT modelleri.
- Konuşmacı ayrımı (diarization), DOCX/SRT çıktısı.
