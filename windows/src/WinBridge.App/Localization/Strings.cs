using System.Globalization;

namespace WinBridge.App.Localization;

/// <summary>
/// UI text in English and Turkish.
///
/// English is the base language and the fallback: a device set to any other
/// language gets English, never Turkish. A missing key returns the English
/// string rather than throwing, so a translation gap degrades to readable text
/// instead of a crash.
/// </summary>
public static class Strings
{
    private static Dictionary<string, string> _active = English;

    public static string CurrentLanguage { get; private set; } = "en";

    /// <param name="preference">"auto", "en" or "tr".</param>
    public static void Apply(string preference)
    {
        string language = preference switch
        {
            "en" or "tr" => preference,
            _ => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "tr" ? "tr" : "en",
        };

        CurrentLanguage = language;
        _active = language == "tr" ? Turkish : English;
    }

    public static string Get(string key) =>
        _active.TryGetValue(key, out var value) ? value
        : English.TryGetValue(key, out var fallback) ? fallback
        : key;

    public static string Format(string key, params object[] args) =>
        string.Format(CultureInfo.CurrentCulture, Get(key), args);

    private static readonly Dictionary<string, string> English = new()
    {
        ["app.name"] = "WinBridge",
        ["tray.open"] = "Open WinBridge",
        ["tray.pair"] = "Pair a phone…",
        ["tray.send"] = "Send files to phone…",
        ["tray.phonescreen"] = "Show phone screen",
        ["tray.clipboard"] = "Send clipboard to phone",
        ["tray.exit"] = "Exit",
        ["tray.tooltip.idle"] = "WinBridge — no phone connected",
        ["tray.tooltip.connected"] = "WinBridge — {0} connected",

        ["window.title"] = "WinBridge",
        ["tab.status"] = "Status",
        ["tab.devices"] = "Devices",
        ["tab.settings"] = "Settings",
        ["tab.log"] = "Activity",

        ["status.connections"] = "Connected phones",
        ["status.none"] = "No phone is connected right now.",
        ["status.carrier"] = "over {0}",
        ["status.bluetooth"] = "Bluetooth",
        ["status.lan"] = "Wi-Fi / LAN",
        ["status.host"] = "This PC",
        ["status.addresses"] = "Reachable at",
        ["status.btaddress"] = "Bluetooth address",
        ["status.btnone"] = "no Bluetooth adapter",

        ["devices.paired"] = "Paired devices",
        ["devices.none"] = "No devices paired yet. Use “Pair a phone” to add one.",
        ["devices.lastseen"] = "last seen {0}",
        ["devices.forget"] = "Forget",
        ["devices.forget.confirm"] = "Remove {0}? The phone will have to be paired again.",

        ["pair.title"] = "Pair a phone",
        ["pair.scan"] = "Scan this code in the WinBridge app on your phone.",
        ["pair.pin"] = "Or enter this code on the phone:",
        ["pair.expires"] = "This code expires in {0} seconds.",
        ["pair.expired"] = "The code expired. Close this window and try again.",
        ["pair.success"] = "Paired with {0}.",
        ["pair.bluetoothnote"] = "For Bluetooth, pair the phone in Windows Settings first.",
        ["pair.close"] = "Close",

        ["settings.startup"] = "Start automatically when I sign in",
        ["settings.bluetooth"] = "Accept Bluetooth connections",
        ["settings.lan"] = "Accept Wi-Fi / LAN connections",
        ["settings.preferbt"] = "Prefer Bluetooth when both are available",
        ["settings.remotepairing"] = "Allow pairing from outside the local network",
        ["settings.remotepairing.hint"] =
            "Leave this off unless you have forwarded the port and understand the risk.",
        ["settings.port"] = "Listening port",
        ["settings.language"] = "Language",
        ["settings.language.auto"] = "Match Windows",
        ["settings.restartnote"] = "Changes to ports and connection types apply after a restart.",

        ["common.yes"] = "Yes",
        ["common.no"] = "No",
        ["common.cancel"] = "Cancel",
    
        // ---- 0.2.0 -----------------------------------------------------
        ["tab.features"] = "Features",
        ["tab.automations"] = "Automations",

        ["features.clipboard"] = "Clipboard",
        ["features.clip.tophone"] = "Send what I copy here to the phone",
        ["features.clip.fromphone"] = "Apply what the phone copies to this clipboard",
        ["features.clip.images"] = "Include copied images (larger, Wi-Fi only)",

        ["features.files"] = "Files",
        ["features.files.enabled"] = "Accept file transfers",
        ["features.files.auto"] = "Accept small files without asking",
        ["features.files.shellmenu"] = "Add \"Send to phone\" to the Explorer right-click menu",
        ["features.files.saveto"] = "Save to",

        ["features.screen"] = "Screen sharing",
        ["features.screen.share"] = "Let a paired phone view this screen",
        ["features.screen.audio"] = "Send this PC audio with the screen",
        ["features.screen.viewphone"] = "Let me open the phone screen from here",
        ["features.screen.input"] = "Let the phone control this mouse and keyboard",
        ["features.screen.carrierhint"] =
            "Mirroring and audio need the Wi-Fi link. Bluetooth cannot carry them and will refuse rather than crawl.",

        ["features.audio"] = "Audio",
        ["features.audio.tophone"] = "Send this PC audio to the phone",
        ["features.audio.fromphone"] = "Play phone audio here",
        ["features.audio.mictophone"] = "Send this PC microphone to the phone",
        ["features.audio.micfromphone"] = "Use the phone microphone here",
        ["features.audio.renderdevice"] = "Play phone audio on",
        ["features.audio.systemdefault"] = "System default",
        ["features.audio.cablehint"] =
            "Pointing this at a virtual cable (VB-Audio, VAC) makes the phone microphone selectable as a recording device in other apps. Windows will not let an app create a sound card of its own.",

        ["features.notifications"] = "Notifications",
        ["features.notif.enabled"] = "Mirror phone notifications to this PC",
        ["features.notif.toasts"] = "Show a popup for each one",
        ["features.notif.reply"] = "Allow replying from here",

        ["features.presence"] = "Presence",
        ["features.presence.lock"] = "Lock this PC when the phone disconnects",

        ["auto.enabled"] = "Allow the phone to run automations",
        ["auto.authoring"] = "Allow the phone to create and edit them",
        ["auto.shell"] = "Allow steps that run CMD or PowerShell commands",
        ["auto.shell.hint"] =
            "This lets an approved automation run commands as you. Every automation still has to be approved here, approval is bound to the exact command text, and editing one character asks again.",
        ["auto.trustmode"] = "When a trusted phone runs one",
        ["auto.trustmode.strict"] = "Always ask me (strict)",
        ["auto.trustmode.trusted"] = "Skip the prompt for allowlisted commands",
        ["auto.allowlist"] = "Allowlist — one command or program per line",
        ["auto.allowelevated"] = "Allow steps marked \"as administrator\"",
        ["auto.allownetwork"] = "Allow steps that make HTTP requests",
        ["auto.allowfilewrite"] = "Allow steps that write, move or delete files",
        ["auto.panic"] = "Stop everything — refuse all automations now",
        ["auto.panic.hint"] =
            "One switch that overrides the rest, for when something is wrong and you do not want to unpick which setting caused it.",
        ["auto.runs"] = "Recent runs",
        ["auto.runs.none"] = "Nothing has run yet.",

        ["approve.title.save"] = "Approve automation",
        ["approve.title.run"] = "Run automation",
        ["approve.new"] = "New automation from your phone",
        ["approve.changed"] = "Automation changed on your phone",
        ["approve.run"] = "Run this on your PC?",
        ["approve.allow"] = "Allow",
        ["approve.refuse"] = "Refuse",
        ["approve.nosteps"] = "(no steps)",
        ["approve.risk.dangerous"] =
            "This automation contains commands that can delete data or change how Windows starts. Read every line below before allowing it.",
        ["approve.risk.shell"] = "This automation runs shell commands on this PC with your account.",
        ["approve.risk.input"] = "This automation controls windows, processes, files or input on this PC.",
        ["approve.risk.safe"] = "This automation only reads state and controls media.",

        ["xfer.incoming.title"] = "WinBridge — incoming file",
        ["xfer.incoming.body"] = "Save \"{0}\" ({1}) to\n{2}?",
        ["xfer.failed"] = "Transfer failed",
        ["xfer.received"] = "File received",
        ["xfer.sending"] = "Sending to {0}",
        ["xfer.items"] = "{0} items",
        ["nophone.title"] = "No phone connected",
        ["nophone.body"] = "Connect a phone and try again.",
        ["clip.copied"] = "Copied from {0}",
    };

    private static readonly Dictionary<string, string> Turkish = new()
    {
        ["app.name"] = "WinBridge",
        ["tray.open"] = "WinBridge'i aç",
        ["tray.pair"] = "Telefon eşleştir…",
        ["tray.send"] = "Telefona dosya gönder…",
        ["tray.phonescreen"] = "Telefon ekranını göster",
        ["tray.clipboard"] = "Panoyu telefona gönder",
        ["tray.exit"] = "Çıkış",
        ["tray.tooltip.idle"] = "WinBridge — bağlı telefon yok",
        ["tray.tooltip.connected"] = "WinBridge — {0} bağlı",

        ["window.title"] = "WinBridge",
        ["tab.status"] = "Durum",
        ["tab.devices"] = "Cihazlar",
        ["tab.settings"] = "Ayarlar",
        ["tab.log"] = "Etkinlik",

        ["status.connections"] = "Bağlı telefonlar",
        ["status.none"] = "Şu anda bağlı telefon yok.",
        ["status.carrier"] = "{0} üzerinden",
        ["status.bluetooth"] = "Bluetooth",
        ["status.lan"] = "Wi-Fi / LAN",
        ["status.host"] = "Bu bilgisayar",
        ["status.addresses"] = "Erişilebilir adresler",
        ["status.btaddress"] = "Bluetooth adresi",
        ["status.btnone"] = "Bluetooth adaptörü yok",

        ["devices.paired"] = "Eşleşmiş cihazlar",
        ["devices.none"] = "Henüz eşleşmiş cihaz yok. “Telefon eşleştir” ile ekleyin.",
        ["devices.lastseen"] = "son görülme {0}",
        ["devices.forget"] = "Kaldır",
        ["devices.forget.confirm"] = "{0} kaldırılsın mı? Telefonun yeniden eşleştirilmesi gerekir.",

        ["pair.title"] = "Telefon eşleştir",
        ["pair.scan"] = "Telefonunuzdaki WinBridge uygulamasıyla bu kodu okutun.",
        ["pair.pin"] = "Veya telefona şu kodu girin:",
        ["pair.expires"] = "Bu kodun süresi {0} saniye sonra doluyor.",
        ["pair.expired"] = "Kodun süresi doldu. Pencereyi kapatıp tekrar deneyin.",
        ["pair.success"] = "{0} ile eşleşildi.",
        ["pair.bluetoothnote"] = "Bluetooth için önce Windows Ayarlar'dan telefonu eşleştirin.",
        ["pair.close"] = "Kapat",

        ["settings.startup"] = "Oturum açtığımda otomatik başlat",
        ["settings.bluetooth"] = "Bluetooth bağlantılarını kabul et",
        ["settings.lan"] = "Wi-Fi / LAN bağlantılarını kabul et",
        ["settings.preferbt"] = "İkisi de varsa Bluetooth'u tercih et",
        ["settings.remotepairing"] = "Yerel ağ dışından eşleştirmeye izin ver",
        ["settings.remotepairing.hint"] =
            "Portu dışarı açmadıysanız ve riski bilmiyorsanız kapalı bırakın.",
        ["settings.port"] = "Dinleme portu",
        ["settings.language"] = "Dil",
        ["settings.language.auto"] = "Windows ile aynı",
        ["settings.restartnote"] = "Port ve bağlantı türü değişiklikleri yeniden başlatınca geçerli olur.",

        ["common.yes"] = "Evet",
        ["common.no"] = "Hayır",
        ["common.cancel"] = "İptal",
    
        // ---- 0.2.0 -----------------------------------------------------
        ["tab.features"] = "Özellikler",
        ["tab.automations"] = "Otomasyonlar",

        ["features.clipboard"] = "Pano",
        ["features.clip.tophone"] = "Burada kopyaladığımı telefona gönder",
        ["features.clip.fromphone"] = "Telefonun kopyaladığını bu panoya uygula",
        ["features.clip.images"] = "Kopyalanan görselleri de gönder (daha büyük, yalnızca Wi-Fi)",

        ["features.files"] = "Dosyalar",
        ["features.files.enabled"] = "Dosya aktarımlarını kabul et",
        ["features.files.auto"] = "Küçük dosyaları sormadan kabul et",
        ["features.files.shellmenu"] = "Gezgin sağ tık menüsüne \"Telefona gönder\" ekle",
        ["features.files.saveto"] = "Kayıt yeri",

        ["features.screen"] = "Ekran paylaşımı",
        ["features.screen.share"] = "Eşleşmiş telefon bu ekranı görebilsin",
        ["features.screen.audio"] = "Ekranla birlikte bilgisayar sesini de gönder",
        ["features.screen.viewphone"] = "Telefon ekranını buradan açabileyim",
        ["features.screen.input"] = "Telefon bu fare ve klavyeyi kontrol edebilsin",
        ["features.screen.carrierhint"] =
            "Yansıtma ve ses Wi-Fi bağlantısı gerektirir. Bluetooth bunları taşıyamaz; sürünmek yerine reddeder.",

        ["features.audio"] = "Ses",
        ["features.audio.tophone"] = "Bilgisayar sesini telefona gönder",
        ["features.audio.fromphone"] = "Telefon sesini burada çal",
        ["features.audio.mictophone"] = "Bilgisayar mikrofonunu telefona gönder",
        ["features.audio.micfromphone"] = "Telefon mikrofonunu burada kullan",
        ["features.audio.renderdevice"] = "Telefon sesini şurada çal",
        ["features.audio.systemdefault"] = "Sistem varsayılanı",
        ["features.audio.cablehint"] =
            "Bunu sanal bir kabloya (VB-Audio, VAC) yönlendirirseniz telefon mikrofonu diğer uygulamalarda kayıt cihazı olarak seçilebilir hale gelir. Windows, bir uygulamanın kendi ses kartını oluşturmasına izin vermez.",

        ["features.notifications"] = "Bildirimler",
        ["features.notif.enabled"] = "Telefon bildirimlerini bu bilgisayara yansıt",
        ["features.notif.toasts"] = "Her biri için açılır pencere göster",
        ["features.notif.reply"] = "Buradan yanıtlamaya izin ver",

        ["features.presence"] = "Yakınlık",
        ["features.presence.lock"] = "Telefon bağlantısı kesilince bu bilgisayarı kilitle",

        ["auto.enabled"] = "Telefon otomasyon çalıştırabilsin",
        ["auto.authoring"] = "Telefon otomasyon oluşturup düzenleyebilsin",
        ["auto.shell"] = "CMD veya PowerShell komutu çalıştıran adımlara izin ver",
        ["auto.shell.hint"] =
            "Bu, onaylanmış bir otomasyonun sizin adınıza komut çalıştırmasına izin verir. Her otomasyon yine burada onaylanmalıdır; onay tam komut metnine bağlıdır ve tek karakter değişirse yeniden sorulur.",
        ["auto.trustmode"] = "Güvenilen bir telefon çalıştırdığında",
        ["auto.trustmode.strict"] = "Her seferinde bana sor (sıkı)",
        ["auto.trustmode.trusted"] = "İzin listesindeki komutlarda sorma",
        ["auto.allowlist"] = "İzin listesi — her satıra bir komut veya program",
        ["auto.allowelevated"] = "\"Yönetici olarak\" işaretli adımlara izin ver",
        ["auto.allownetwork"] = "HTTP isteği yapan adımlara izin ver",
        ["auto.allowfilewrite"] = "Dosya yazan, taşıyan veya silen adımlara izin ver",
        ["auto.panic"] = "Her şeyi durdur — tüm otomasyonları şimdi reddet",
        ["auto.panic.hint"] =
            "Diğerlerini geçersiz kılan tek anahtar; bir şey ters gittiğinde hangi ayarın sebep olduğunu çözmek zorunda kalmayın diye.",
        ["auto.runs"] = "Son çalışmalar",
        ["auto.runs.none"] = "Henüz hiçbir şey çalışmadı.",

        ["approve.title.save"] = "Otomasyonu onayla",
        ["approve.title.run"] = "Otomasyonu çalıştır",
        ["approve.new"] = "Telefonunuzdan yeni otomasyon",
        ["approve.changed"] = "Telefonunuzda otomasyon değişti",
        ["approve.run"] = "Bunu bilgisayarınızda çalıştırayım mı?",
        ["approve.allow"] = "İzin ver",
        ["approve.refuse"] = "Reddet",
        ["approve.nosteps"] = "(adım yok)",
        ["approve.risk.dangerous"] =
            "Bu otomasyon veri silebilecek veya Windows'un başlangıcını değiştirebilecek komutlar içeriyor. İzin vermeden önce aşağıdaki her satırı okuyun.",
        ["approve.risk.shell"] = "Bu otomasyon bu bilgisayarda sizin hesabınızla kabuk komutları çalıştırır.",
        ["approve.risk.input"] = "Bu otomasyon bu bilgisayarda pencereleri, süreçleri, dosyaları veya girdiyi kontrol eder.",
        ["approve.risk.safe"] = "Bu otomasyon yalnızca durum okur ve medyayı kontrol eder.",

        ["xfer.incoming.title"] = "WinBridge — gelen dosya",
        ["xfer.incoming.body"] = "\"{0}\" ({1}) şuraya kaydedilsin mi?\n{2}",
        ["xfer.failed"] = "Aktarım başarısız",
        ["xfer.received"] = "Dosya alındı",
        ["xfer.sending"] = "{0} cihazına gönderiliyor",
        ["xfer.items"] = "{0} öğe",
        ["nophone.title"] = "Bağlı telefon yok",
        ["nophone.body"] = "Bir telefon bağlayıp tekrar deneyin.",
        ["clip.copied"] = "{0} üzerinde kopyalandı",
    };
}
