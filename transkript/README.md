# Transkript

Toplantı, konferans ve sunumlarda konuşmayı **noktalamalı, akan metne** çeviren ve **konuşmacıları ayıran** tarayıcı uygulaması. Sunucu ya da ücretli API gerekmez; her şey kullanıcının cihazında çalışır, ses hiçbir yere gönderilmez. Mikrofon için sayfanın `https` üzerinden açılması gerekir (GitHub Pages uygundur).

## Nasıl çalışır
1. **Ses alma:** canlı modda mikrofon (AudioWorklet), dosya modunda ses/video dosyası 16 kHz monoya çevrilir.
2. **Bölütleme (`engine.js` → `Segmenter`):** enerji tabanlı konuşma algılayıcı, sesi kısa duraklamalardan cümle/bölüm parçalarına ayırır (en fazla ~14 sn; daha uzunsa en sessiz yerden keser).
3. **Metne çevirme (`worker.js`):** her parça [Whisper](https://github.com/openai/whisper) ile [transformers.js](https://github.com/huggingface/transformers.js) üzerinden yazıya dökülür. Whisper noktalama ve büyük harfleri kendisi koyar. “Otomatik” dil seçeneğinde her parça için yalnızca Türkçe ve İngilizce puanları karşılaştırılır.
4. **Konuşmacı ayrımı:** her parçadan [WavLM-SV](https://huggingface.co/Xenova/wavlm-base-plus-sv) ile bir ses izi çıkarılır; `Speakers` bunları benzerliğe göre “Konuşmacı 1, 2…” olarak gruplar. Konuşmacı sayısı biliniyorsa ayarlardan seçmek doğruluğu artırır.
5. **Metin:** aynı konuşmacının cümleleri tek paragrafta birleşir. Whisper'ın sessizlikte uydurduğu bilinen kalıplar (“Altyazı M.K.” vb.) süzülür.

Konuşmacı adına tıklayınca: başka konuşmacıya atama, yeni konuşmacı, yeniden adlandırma (ör. “Ahmet”) ya da bir konuşmacının tümünü başkasına aktarma.

## Dışa aktarma
TXT (UTF-8) ve PDF ([pdfmake](https://github.com/bpampuch/pdfmake), Türkçe karakterler tam). Biçim:

```
Konuşmacı 1: Merhaba, hoş geldiniz. Bugün bütçeyi konuşacağız.

Konuşmacı 2: Teşekkürler. Ben ilk olarak…
```

## Dosyalar
- `index.html` — arayüz, mikrofon, iş kuyruğu, dışa aktarma
- `engine.js` — modelden bağımsız mantık (bölütleme, yeniden örnekleme, konuşmacı gruplama, metin temizliği)
- `worker.js` — Whisper ve WavLM-SV modelleri (Web Worker)
- Kütüphaneler sabit sürümle jsDelivr'den yüklenir: `@huggingface/transformers@4.3.2`, `pdfmake@0.2.20`.

## Model seçimi
| Ayar | Model | Not |
|---|---|---|
| Hızlı | whisper-tiny | zayıf telefonlar |
| Dengeli | whisper-base | WebGPU yoksa varsayılan |
| Doğru | whisper-small | WebGPU varsa varsayılan |
| En doğru | whisper-large-v3-turbo | güçlü ekran kartı gerekir |

Canlı modda cihaz yetişemezse sırada bekleyen parça sayısı gösterilir; daha hızlı bir ayar seçin.

## 2. aşama
- Çeviri (cihaz içinde: NLLB-200 / OPUS-MT, transformers.js ile).
