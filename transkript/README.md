# Transkript

Toplantı, konferans ve sunumlarda konuşmayı **noktalamalı, akan metne** çeviren ve **konuşmacıları ayıran** tarayıcı uygulaması. Sunucu ya da ücretli API gerekmez; her şey kullanıcının cihazında çalışır, ses hiçbir yere gönderilmez. Mikrofon için sayfanın `https` üzerinden açılması gerekir (GitHub Pages uygundur).

## Nasıl çalışır
1. **Ses alma:** canlı modda mikrofon (AudioWorklet), dosya modunda ses/video dosyası 16 kHz monoya çevrilir.
2. **Bölütleme (`engine.js` → `Segmenter`):** enerji tabanlı konuşma algılayıcı, sesi kısa duraklamalardan cümle/bölüm parçalarına ayırır (en fazla ~14 sn; daha uzunsa en sessiz yerden keser).
3. **Metne çevirme (`worker.js`):** her parça [Whisper](https://github.com/openai/whisper) ile [transformers.js](https://github.com/huggingface/transformers.js) üzerinden yazıya dökülür. Whisper noktalama ve büyük harfleri kendisi koyar. “Otomatik” dil seçeneğinde her parça için yalnızca Türkçe ve İngilizce puanları karşılaştırılır.
4. **Konuşmacı ayrımı:** her parçadan [WavLM-SV](https://huggingface.co/Xenova/wavlm-base-plus-sv) ile bir ses izi çıkarılır.
   - *Canlı karar* (`Speakers.assign`): benzerlik eşiği + belirsiz bölgede en yakın kişi, önceki konuşmacıya öncelik; 2 sn'den kısa cümle yeni konuşmacı açamaz.
   - *Toplu yeniden düzenleme* (`recluster`): tüm ses izleri hiyerarşik kümelenir; konuşmacı sayısı verilmemişse benzerliğin en sert düştüğü yerden seçilir, tek cümlelik/çok küçük gruplar en yakın kişiye katılır. Canlı kayıtta her 8 cümlede bir ve durdurunca, dosyada bitince kendiliğinden çalışır (elle konuşmacı değiştirildiyse çalışmaz; “Konuşmacıları düzenle” düğmesiyle istenebilir).
   - Ayar: Toleranslı / Normal / Hassas (eşik 0,60 / 0,68 / 0,76). Ses izleri 8 bit sıkıştırılarak saklanır.
5. **Metin:** aynı konuşmacının cümleleri tek paragrafta birleşir. Whisper'ın sessizlikte uydurduğu bilinen kalıplar (“Altyazı M.K.” vb.) süzülür.

Konuşmacı adına tıklayınca: başka konuşmacıya atama, yeni konuşmacı, yeniden adlandırma (ör. “Ahmet”) ya da bir konuşmacının tümünü başkasına aktarma.

## Hız
- **Ekran kartı (WebGPU):** varsa kullanılır. Telefonda yalnızca Hızlı/Dengeli modellerde açılır; hata verirse kendiliğinden işlemciye (WASM) geçilir ve bu hatırlanır.
- **Çok çekirdek:** GitHub Pages COOP/COEP başlıklarını göndermediği için [coi-serviceworker](https://github.com/gzuidhof/coi-serviceworker) (MIT) sayfayı “cross-origin isolated” yapar; WASM 4 çekirdeğe kadar paralel çalışır. İlk ziyarette sayfa bir kez kendiliğinden yenilenir.
- **Ara metin:** cihaz boştayken, süren konuşmanın o ana kadarki kısmı da çevrilip gri olarak gösterilir; cümle bitince kesin metin gelir.
- Cümle sonu için 0,5 sn sessizlik beklenir, canlı modda parçalar en fazla 10 sn'dir. Konuşmacı ses izi 4 sn'lik kısımdan çıkarılır. “Otomatik” dilde dil her 4 cümlede bir yeniden algılanır.

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
- `coi-serviceworker.js` — çok çekirdek için gerekli başlıkları ekleyen service worker (MIT, değiştirilmedi)
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
