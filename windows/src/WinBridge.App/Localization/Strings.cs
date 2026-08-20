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
    };
}
