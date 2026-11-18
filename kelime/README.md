# eFAM English

Kişisel kullanım için İngilizce kelime öğrenme uygulaması (PWA). Telefona “Ana ekrana ekle” ile kurulur, internetsiz çalışır, ilerleme yalnızca cihazda saklanır.

- **5.298 kelime**, CEFR seviyelerine göre: A1 892 · A2 799 · B1 1026 · B2 1452 · C1 1129. Her kelimede Türkçe anlam, tür ve örnek cümle var.
- **Aralıklı tekrar (Leitner kutuları):** tekrarlar 1 → 3 → 7 → 16 → 35 gün arayla gelir. 35 günlük son sınavı da geçen kelime “öğrenildi” sayılır ve bir daha sorulmaz. Yanlış cevap kelimeyi 2 kutu geri gönderir.
- **Soru türleri kutuya göre zorlaşır:** çoktan seçmeli (EN→TR, TR→EN) → cümlede boşluğa uyan kelimeyi seç → eksik harfleri tamamla → boşluk doldurma (yazarak) → İngilizcesini elle yaz.
- **Takip:** öğrenildi / öğreniliyor / yeni sayıları, seviye ilerlemesi, kutu dağılımı, son 14 gün, önümüzdeki 7 günün tekrar yükü, seri ve toplam çalışma süresi.

## Kelime listesi
`data/A1.txt` … `data/C1.txt`, satır başına bir kelime:

```
kelime|tür|Türkçe anlam|Örnek cümle, [boşlukta sorulacak biçim] köşeli parantez içinde.
```

## Sürüm numarası
Biçim `BÜYÜK.ORTA.KÜÇÜK` (ör. 1.4.1). Numara logonun yanında görünür, sürüm notları ☰ menüsündedir.

- **Büyük:** köklü değişiklik (yapı ya da veri biçimi değişir)
- **Orta:** yeni özellik
- **Küçük:** düzeltme ve ufak ayar

Her güncellemede üç yeri birlikte değiştir: `index.html` içindeki `APP_VERSION` ve `CHANGELOG`, `sw.js` içindeki `VERSION` (`kelime-v` + aynı numara). Böylece telefonlar yeni sürümü alır.
