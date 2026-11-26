# Transkript (Android)

Toplantı, konferans ve sunumlarda konuşmayı telefonda, internetsiz, noktalamalı metne çeviren ve konuşmacıları ayıran uygulama. Web sürümünün (`/transkript`) yerel (native) hali: aynı modeller tarayıcı yerine doğrudan telefonun işlemcisinde çalıştığı için çok daha hızlıdır.

## Kurulum (telefon)
1. GitHub'da **Releases → “Transkript Android (son sürüm)”** sayfasını telefonda açın, `Transkript.apk` dosyasını indirin.
2. Açın; Android “bilinmeyen kaynaklardan yükleme” izni ister, verin.
3. Uygulamada **Ayarlar → Modeli indir** (Wi-Fi önerilir). Model bir kez indirilir; sonra internet gerekmez.

## Nasıl çalışır
- **sherpa-onnx** (k2-fsa, Apache-2.0) — Android çalıştırma katmanı, `v2.0.1` AAR'ı derlemede indirilir.
- **Silero VAD** — konuşmayı duraklamalardan cümlelere ayırır (APK içinde).
- **Whisper** (tiny / base / small / turbo, int8) — noktalamalı metin. İlk kullanımda Hugging Face'ten (`csukuangfj/sherpa-onnx-whisper-*`) indirilir.
- **WeSpeaker ResNet34** — konuşmacı ses izi (APK içinde). Konuşmacılar web sürümündeki yöntemle gruplanır: canlı karar + her 8 cümlede ve durdurunca tüm konuşmaya bakarak yeniden düzenleme.
- Kayıt bir **ön plan servisi** ile sürer: ekran kapansa da devam eder, bildirimden durdurulabilir.
- TXT/PDF kaydetme (Android'in kaydet penceresi), paylaşma, kopyalama.

## Derleme
`.github/workflows/android.yml` her değişiklikte APK üretir (Actions → Artifacts). Ana daldaki derleme `android-latest` sürümüne konur.

İmza anahtarı (`keystore/transkript.jks`, parolası `transkript`) kişisel kullanım içindir ve depoda durur ki her yeni sürüm eskisinin üzerine kurulabilsin. Uygulama Play Store'a konacaksa yeni, gizli bir anahtar kullanılmalıdır.
