# WinBridge — Windows ↔ Android Bridge

Windows'unuzu telefonunuzdan izleyin ve yönetin. Çalan medya, CPU/GPU/RAM/ağ/pil
durumu telefonda **widget** olarak; kilitleme, uyku, kapatma ve ses kontrolü tek
dokunuşla. Bağlantı **Bluetooth** veya **LAN** üzerinden — bir kere eşleştirin,
ikisi de çalışsın.

🇬🇧 [English README](README.md)

> **Durum:** [v0.1.0 yayınlandı](../../releases/latest). Aşağıdakilerin tamamı
> gerçek bilgisayar, telefon ve saatte denenmiştir.

---

## Ne yapar

**Telefonda görürsünüz**

- Çalan parça, telefonda **gerçek bir medya oturumu** olarak — bildirim
  gölgesinde, kilit ekranında ve Hızlı Ayarlar oynatıcısında kapak görseli,
  kontroller ve ilerleme çubuğuyla, sanki müzik telefonda çalıyormuş gibi.
  Varsayılan açık; Ayarlar'dan kapatılabilir.
- Başlık, sanatçı, albüm, kapak görseli, konum/süre, kaynak uygulama
- CPU, GPU, RAM, ağ (yükleme/indirme), disk kullanımı
- Pil yüzdesi ve şarj durumu (dizüstü bilgisayarlarda)
- Ses seviyesi ve sessize alma durumu

**Telefondan yaparsınız**

- Oynat / duraklat / ileri / geri / sarma
- Ses ayarı ve sessize alma
- Kilitle, uyku, hazırda beklet, oturumu kapat, yeniden başlat, kapat
- Ekranı kapat *(sistem destekliyorsa — desteklenmeyen makinelerde buton pasif olur)*

**Widget'lar** — medya, sistem istatistikleri, birleşik ve güç kontrolleri;
ayrı ayrı veya birlikte kullanılabilir.

**Wear OS** — saatte medya ve sistem durumu, telefon üzerinden çalışır.

---

## Bağlantı

| Yöntem | Ne zaman | Not |
|---|---|---|
| **Bluetooth (RFCOMM)** | Varsayılan tercih | Wi-Fi gerekmez, her yerde çalışır |
| **LAN (TCP)** | Aynı ağdayken | Çok daha yüksek hız — kapak görselleri için ideal |
| **Uzak ağ** | PC'de port yönlendirdiyseniz | Kanal uçtan uca şifreli |

Bir yöntemle eşleştirdiğinizde diğeri **otomatik olarak** eşleşir: eşleşen kanal
üzerinden karşı tarafın adresi ve anahtarı aktarılır.

---

## Kurulum

### Windows

1. [Releases](../../releases) sayfasından `WinBridge-Setup-x.y.z.exe` indirin.
2. Çalıştırın. Yönetici hakkı gerekmez.
3. İlk kurulumda arayüz bir kez açılır; sonrasında sistem tepsisinden erişilir.
4. Oturum açılışında otomatik başlar.

> **SmartScreen uyarısı:** kurulum dosyası imzasızdır (kod imzalama sertifikası
> ücretli bir üründür). "Daha fazla bilgi" → "Yine de çalıştır" deyin.

### Android

1. Aynı sayfadan `app-release.apk` indirin ve kurun (bilinmeyen kaynaklara izin
   vermeniz gerekir).
2. Uygulamayı açın, kurulum sihirbazını izleyin.
3. Windows tepsi menüsünden **Eşleştir** deyin, çıkan QR'ı telefonla okutun.

**Bluetooth kullanacaksanız:** önce Windows Ayarlar'dan telefonu normal şekilde
eşleştirin. Bu adım programatik olarak yapılamaz, işletim sistemi seviyesinde
bir gerekliliktir.

### Wear OS saat

Saat APK'sı ayrı bir dosyadır (`wear-release.apk`) — Play Store dışından
dağıtımda saate ayrıca kurulmalıdır:

```bash
adb connect <saatin-ip-adresi>:5555
adb -s <saatin-ip-adresi>:5555 install wear-release.apk
```

Saatteki uygulama telefona bağlı çalışır; telefonda WinBridge kurulu olmalıdır.

---

## Dil

Uygulamalar **İngilizce** ve **Türkçe** içerir ve cihaz dilinizi izler. Diğer
tüm dillerde İngilizce'ye düşer.

---

## Güvenlik

- Eşleştirme 32 baytlık bir ortak anahtar (PSK) üretir; QR ile aktarılır ve
  **ağdan hiç geçmez**.
- Her oturum ayrıca geçici ECDH (P-256) anahtarı üretir → geçmiş oturumlar
  sonradan çözülemez (forward secrecy).
- Tüm trafik AES-256-GCM ile şifrelenir, çift taraflı kimlik doğrulaması yapılır.
- Eşleştirme modu yalnızca sizin başlatmanızla, 60 saniyeliğine açılır.

Ayrıntılar: [docs/PROTOCOL.md](docs/PROTOCOL.md)

---

## Geliştirme

```bash
# Android
cd android && ./gradlew :app:assembleDebug

# Windows
cd windows && dotnet build
```

Gereksinimler: JDK 17+, Android SDK 36, .NET 10 SDK, Inno Setup 6 (kurulum
paketi için).

Mimari kararlar ve nedenleri: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

## Lisans

MIT
