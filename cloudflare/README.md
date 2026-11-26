# eFAM Transkript — Cloudflare bulut motoru

Bu klasördeki `worker.js`, Cloudflare hesabınızda çalışan küçük bir sunucudur. Uygulamanın (web ve Android) gönderdiği kısa konuşma parçalarını **Cloudflare Workers AI** üzerindeki **Whisper large-v3-turbo** modeliyle metne çevirir. Model Cloudflare'in ekran kartlarında çalıştığı için telefondaki modelden hem çok daha doğru hem çok daha hızlıdır.

- Konuşma algılama ve konuşmacı ayrımı yine cihazda yapılır; buluta yalnızca cümle cümle ses parçaları gider.
- Sunucu bir **erişim kodu** ile korunur; kodu bilmeyen kotanızı kullanamaz.
- API anahtarı gerekmez: Worker, Cloudflare hesabınızın yapay zekâ hizmetine doğrudan bağlanır.

## Kurulum (yaklaşık 5 dakika, yalnızca bir kez)

Cloudflare panelinin menü adları zamanla değişebilir; aşağıdakiler genel yoldur.

1. **dash.cloudflare.com** → **Workers & Pages** → **Create** → **Worker** (“Hello World” şablonu).
   Ad olarak örneğin `efam-transkript` yazın → **Deploy**.
2. **Edit code**: sağdaki kodun tamamını silin, bu klasördeki `worker.js` dosyasının içeriğini yapıştırın → **Deploy**.
3. Worker → **Settings → Bindings → Add → Workers AI**. Değişken adı: **`AI`** → kaydedin.
4. Worker → **Settings → Variables and Secrets → Add**: Tür **Secret**, ad **`ACCESS_CODE`**, değer: tahmin edilmesi zor bir kod (ör. 16+ karakter). Kaydedip yeniden **Deploy** edin.
5. Worker'ın adresini kopyalayın (ör. `https://efam-transkript.ADINIZ.workers.dev`). Tarayıcıda açtığınızda şuna benzer bir yanıt görmelisiniz:
   ```
   {"ok":true,"service":"eFAM Transkript","model":"@cf/openai/whisper-large-v3-turbo","ai":true,"code":true}
   ```
   `"ai":false` ise 3. adımı, `"code":false` ise 4. adımı kontrol edin.
6. *(İsteğe bağlı)* Kendi alan adınızla kullanmak için Worker → **Settings → Domains & Routes → Add → Custom domain**: ör. `transkript.alanadiniz.com`.

## Uygulamada

**Ayarlar → Metne çeviren motor → Bulut · Cloudflare**, ardından sunucu adresini ve erişim kodunu girip **Bağlantıyı dene**.

## Ücret

- Workers AI'ın **günlük ücretsiz kotası** vardır. Cloudflare'in kendi belgelerinde Whisper için iki farklı sınır geçiyor (günde ~10 dakika ile ~200 dakika arası); kesin kalan miktarı Cloudflare panelinde **AI → Workers AI** sayfasında görebilirsiniz.
- Kota bittiğinde uygulama “günlük ücretsiz kota doldu” der ve kaydı durdurur. O gün için **Bu cihazda** motoruna geçebilirsiniz.
- Daha fazla kullanım için Workers Paid planında Whisper large-v3-turbo, Cloudflare'in fiyat sayfasına göre ses dakikası başına yaklaşık **0,0005 $** (saatte ~0,03 $).

## Gizlilik

Bulut motorunda ses parçaları Cloudflare'e gider ve orada işlenir. Gizli toplantılarda **Bu cihazda** motorunu kullanın; o modda ses telefondan/bilgisayardan çıkmaz.

## İstek biçimi (geliştiriciler için)

```
POST https://<worker>/?lang=tr        (tr | en | auto)
x-access-code: <ACCESS_CODE>
Content-Type: audio/wav               (16 kHz mono, en fazla ~60 sn)

→ 200 {"text":"...","language":"tr"}
→ 401 erişim kodu yanlış · 413 çok uzun · 429 kota/sınır · 502 model hatası
```
