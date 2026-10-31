// ESP32 Hub - firmware sisi ESP32 (Arduino core ESP32 2.x / 3.x)
//
// ESP32 jadi hotspot WiFi sendiri. Aplikasi Android terhubung ke hotspot ini,
// lalu mengunduh file .csv dari LittleFS lewat HTTP di 192.168.4.1.
//
// SSID & password disimpan di flash (Preferences), bisa diubah dari aplikasi
// tanpa upload ulang firmware.
//   Default pertama kali : SSID "ESP32-xxxxxx" (6 digit akhir MAC), password "12345678"
//   Reset ke default     : tahan tombol BOOT (GPIO0) saat ESP menyala, ~3 detik.
//
// Endpoint:
//   GET  /info              -> {"id","ssid","total","used"}
//   GET  /list              -> [{"n":"/log.csv","s":1234}, ...]  (semua .csv, termasuk subfolder)
//   GET  /file?n=/log.csv   -> isi file (dengan Content-Length)
//   POST /config  id,ssid,pass  -> simpan, lalu ESP restart dengan WiFi baru

#include <WiFi.h>
#include <WebServer.h>
#include <LittleFS.h>
#include <Preferences.h>

#define RESET_PIN     0            // tombol BOOT
#define DEFAULT_PASS  "12345678"

WebServer server(80);
Preferences prefs;
String apId, apSsid, apPass;
unsigned long restartAt = 0;

static String defaultName() {
  String mac = WiFi.softAPmacAddress();
  mac.replace(":", "");
  return "ESP32-" + mac.substring(6);
}

static void loadConfig() {
  prefs.begin("hub", true);
  String def = defaultName();
  apId   = prefs.getString("id",   def);
  apSsid = prefs.getString("ssid", def);
  apPass = prefs.getString("pass", DEFAULT_PASS);
  prefs.end();
}

static void resetIfButtonHeld() {
  pinMode(RESET_PIN, INPUT_PULLUP);
  unsigned long t = millis();
  while (digitalRead(RESET_PIN) == LOW) {
    if (millis() - t > 3000) {
      prefs.begin("hub", false);
      prefs.clear();
      prefs.end();
      Serial.println("Konfigurasi WiFi direset ke default");
      return;
    }
    delay(10);
  }
}

static String jsonStr(const String &s) {
  String o = "\"";
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\') { o += '\\'; o += c; }
    else if ((uint8_t)c < 0x20) o += ' ';
    else o += c;
  }
  return o + "\"";
}

static bool isCsv(const String &name) {
  String n = name;
  n.toLowerCase();
  return n.endsWith(".csv");
}

static void handleInfo() {
  String j = "{\"id\":" + jsonStr(apId) + ",\"ssid\":" + jsonStr(apSsid) +
             ",\"total\":" + String((uint32_t)LittleFS.totalBytes()) +
             ",\"used\":" + String((uint32_t)LittleFS.usedBytes()) + "}";
  server.send(200, "application/json", j);
}

// Dikirim bertahap supaya tetap aman walau file sangat banyak.
static bool firstItem;
static void listDir(const char *path, int depth) {
  File dir = LittleFS.open(path);
  if (!dir || !dir.isDirectory()) return;
  for (File f = dir.openNextFile(); f; f = dir.openNextFile()) {
    String p = f.path();
    if (f.isDirectory()) {
      if (depth < 4) listDir(p.c_str(), depth + 1);
    } else if (isCsv(p)) {
      String item = (firstItem ? "" : ",");
      item += "{\"n\":" + jsonStr(p) + ",\"s\":" + String((uint32_t)f.size()) + "}";
      server.sendContent(item);
      firstItem = false;
    }
    f.close();
  }
}

static void handleList() {
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.send(200, "application/json", "");
  server.sendContent("[");
  firstItem = true;
  listDir("/", 0);
  server.sendContent("]");
  server.sendContent("");  // akhir chunked response
}

static void handleFile() {
  String n = server.arg("n");
  if (!n.startsWith("/")) n = "/" + n;
  if (n.indexOf("..") >= 0 || !isCsv(n) || !LittleFS.exists(n)) {
    server.send(404, "text/plain", "not found");
    return;
  }
  File f = LittleFS.open(n, "r");
  if (!f) { server.send(500, "text/plain", "open failed"); return; }
  // streamFile mengirim Content-Length dan membaca file sedikit demi sedikit (hemat RAM)
  server.streamFile(f, "text/csv");
  f.close();
}

static void handleConfig() {
  String id = server.arg("id"), ssid = server.arg("ssid"), pass = server.arg("pass");
  id.trim(); ssid.trim();
  if (ssid.length() < 1 || ssid.length() > 32) { server.send(400, "text/plain", "SSID 1-32 karakter"); return; }
  if (pass.length() != 0 && (pass.length() < 8 || pass.length() > 63)) { server.send(400, "text/plain", "Password 8-63 karakter"); return; }
  if (id.length() == 0) id = ssid;
  prefs.begin("hub", false);
  prefs.putString("id", id);
  prefs.putString("ssid", ssid);
  prefs.putString("pass", pass);
  prefs.end();
  server.send(200, "text/plain", "ok");
  restartAt = millis() + 1500;  // beri waktu jawaban terkirim
}

void setup() {
  Serial.begin(115200);
  WiFi.mode(WIFI_AP);
  resetIfButtonHeld();
  loadConfig();

  if (!LittleFS.begin(true)) Serial.println("LittleFS gagal");

  if (apPass.length() >= 8) WiFi.softAP(apSsid.c_str(), apPass.c_str());
  else WiFi.softAP(apSsid.c_str());
  Serial.printf("Hotspot: %s  IP: %s\n", apSsid.c_str(), WiFi.softAPIP().toString().c_str());

  server.on("/info", HTTP_GET, handleInfo);
  server.on("/list", HTTP_GET, handleList);
  server.on("/file", HTTP_GET, handleFile);
  server.on("/config", HTTP_POST, handleConfig);
  server.onNotFound([]() { server.send(404, "text/plain", "not found"); });
  server.begin();

  // >>> Taruh setup sensor / logging Anda di sini <<<
}

void loop() {
  server.handleClient();
  if (restartAt && (long)(millis() - restartAt) > 0) ESP.restart();

  // >>> Taruh kode logging Anda di sini (tulis ke LittleFS sebagai .csv) <<<
  // Jangan blok terlalu lama (pakai millis(), bukan delay panjang),
  // supaya server tetap responsif saat aplikasi mengunduh.
}
