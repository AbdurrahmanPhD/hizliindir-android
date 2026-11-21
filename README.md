# Hızlıİndir (Android)

YouTube ve yt-dlp'nin desteklediği 1000+ siteden video ve MP3 indiren Android uygulaması.
Masaüstündeki Hızlıİndir ile aynı motoru (yt-dlp + ffmpeg) kullanır.

## Özellikler
- Bağlantı yapıştır → **Video** ya da **MP3** → İndir
- YouTube uygulamasında **Paylaş → Hızlıİndir**
- Kalite seçimi (En iyi / 1080p / 720p / 480p / 360p), oynatma listesinin tamamı
- Arka planda indirme (uygulama kapansa da sürer), bildirimde ilerleme ve İptal düğmesi
- İnternet yoksa sıraya alır, bağlantı gelince başlar
- Dosyalar: **İndirilenler/Hizliindir**
- Menüden indirme motorunu güncelleme (YouTube değişince)

## APK'yı GitHub'da derleme (bilgisayara bir şey kurmadan)
1. github.com'da yeni bir depo (repository) oluşturun.
2. Bu klasördeki dosyaları depoya yükleyin (`.github` klasörü dahil).
3. **Actions** sekmesinde "APK derle" işi kendiliğinden başlar (~5-8 dk).
4. Bitince **Releases** bölümünde APK'lar görünür. Çoğu telefon için `HizliIndir-arm64-v8a.apk`.

## Android Studio ile derleme
Klasörü Android Studio'da açın, **Build > Build APK(s)**.

## Telefona kurma
APK'yı telefonda açın; "Bilinmeyen kaynaklardan yüklemeye izin ver" sorulursa izin verin.
Google Play, YouTube indirici uygulamalarına izin vermediği için uygulama Play Store'da yayınlanamaz.

## Proje yapısı
```
app/src/main/java/com/hizliindir/app/
├── App.kt             motoru başlatır, bildirim kanalları
├── MainActivity.kt    ana ekran, paylaş menüsü, izinler
├── DownloadWorker.kt  arka planda indirme (masaüstündeki media.py karşılığı)
├── JobAdapter.kt      indirme listesi
└── Storage.kt         dosyaları İndirilenler klasörüne kaydeder
```

Yalnızca indirme izniniz olan içerikleri indirin.
