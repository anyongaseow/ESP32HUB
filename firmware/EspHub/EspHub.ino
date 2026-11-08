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
//   GET  /gps               -> {"fix":true,"lat":-2.123456,"lon":111.123456,"alt":35.2,"sats":8,"hdop":0.9,"age":420}
//                              (dipakai layar Peta di aplikasi; "fix":false bila belum ada sinyal)
//
// GPS (opsional): modul GPS NMEA (mis. NEO-6M/NEO-M8N) di Serial2, dibaca tanpa library.
//   TX modul GPS -> GPIO16 (GPS_RX_PIN), RX modul -> GPIO17 (boleh tidak disambung), VCC, GND.
//   Punya kode GPS sendiri (mis. TinyGPS++)? Set USE_GPS 0 lalu panggil gpsSet(lat, lon, ...) tiap ada fix.

#include <WiFi.h>
#include <WebServer.h>
#include <LittleFS.h>
#include <Preferences.h>

#define RESET_PIN     0            // tombol BOOT
#define DEFAULT_PASS  "12345678"

#define USE_GPS       1            // 0 = tidak membaca Serial2 (isi lokasi sendiri dengan gpsSet)
#define GPS_RX_PIN    16
#define GPS_TX_PIN    17
#define GPS_BAUD      9600
#define GPS_STALE_MS  5000         // fix dianggap hilang bila tidak ada data baru selama ini

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

// ---------------------------------------------------------------- GPS

struct GpsFix {
  bool has;            // pernah dapat posisi
  double lat, lon;
  float alt, hdop;
  int sats;
  unsigned long at;    // millis() saat posisi terakhir
} gps = {false, 0, 0, NAN, NAN, -1, 0};

// Untuk kode GPS sendiri: panggil setiap ada posisi baru.
// Nilai yang tidak ada: alt/hdop = NAN, sats = -1. Contoh: gpsSet(-2.712345, 111.634567, NAN, -1, NAN);
void gpsSet(double lat, double lon, float alt, int sats, float hdop) {
  gps.lat = lat; gps.lon = lon; gps.alt = alt; gps.hdop = hdop;
  if (sats >= 0) gps.sats = sats;
  gps.has = true;
  gps.at = millis();
}

#if USE_GPS
static char nmea[100];
static size_t nmeaLen = 0;

// "ddmm.mmmm" + N/S/E/W -> derajat desimal
static double nmeaDeg(const char *v, const char *hemi) {
  if (!v[0]) return NAN;
  double x = atof(v);
  int deg = (int)(x / 100);
  double d = deg + (x - deg * 100) / 60.0;
  return (hemi[0] == 'S' || hemi[0] == 'W') ? -d : d;
}

static int hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

static void nmeaLine(char *s) {
  if (s[0] != '$') return;
  char *star = strchr(s, '*');
  if (!star || hexVal(star[1]) < 0 || hexVal(star[2]) < 0) return;
  uint8_t sum = 0;
  for (char *p = s + 1; p < star; p++) sum ^= (uint8_t)*p;
  if (sum != (hexVal(star[1]) << 4 | hexVal(star[2]))) return;   // checksum salah
  *star = 0;

  const char *f[20];
  int n = 0;
  f[n++] = s + 1;
  for (char *p = s + 1; *p && n < 20; p++) {
    if (*p == ',') { *p = 0; f[n++] = p + 1; }
  }
  if (strlen(f[0]) < 5) return;
  const char *type = f[0] + strlen(f[0]) - 3;   // GPGGA / GNGGA / GPRMC / ...

  if (!strcmp(type, "GGA") && n >= 10) {
    gps.sats = atoi(f[7]);
    if (atoi(f[6]) > 0) {
      double lat = nmeaDeg(f[2], f[3]), lon = nmeaDeg(f[4], f[5]);
      if (!isnan(lat) && !isnan(lon)) gpsSet(lat, lon, f[9][0] ? atof(f[9]) : NAN, -1, f[8][0] ? atof(f[8]) : NAN);
    }
  } else if (!strcmp(type, "RMC") && n >= 7 && f[2][0] == 'A') {
    double lat = nmeaDeg(f[3], f[4]), lon = nmeaDeg(f[5], f[6]);
    if (!isnan(lat) && !isnan(lon)) {
      gps.lat = lat; gps.lon = lon; gps.has = true; gps.at = millis();
    }
  }
}

static void gpsPoll() {
  while (Serial2.available()) {
    char c = (char)Serial2.read();
    if (c == '\n' || c == '\r') {
      if (nmeaLen) { nmea[nmeaLen] = 0; nmeaLine(nmea); nmeaLen = 0; }
    } else if (nmeaLen < sizeof(nmea) - 1) {
      nmea[nmeaLen++] = c;
    } else {
      nmeaLen = 0;  // baris terlalu panjang: buang
    }
  }
}
#endif

static void handleGps() {
  unsigned long age = gps.has ? millis() - gps.at : 0;
  bool fix = gps.has && age < GPS_STALE_MS;
  String j = "{\"fix\":" + String(fix ? "true" : "false");
  if (gps.has) {
    j += ",\"lat\":" + String(gps.lat, 6) + ",\"lon\":" + String(gps.lon, 6) + ",\"age\":" + String(age);
    if (!isnan(gps.alt)) j += ",\"alt\":" + String(gps.alt, 1);
    if (!isnan(gps.hdop)) j += ",\"hdop\":" + String(gps.hdop, 1);
  }
  if (gps.sats >= 0) j += ",\"sats\":" + String(gps.sats);
  j += "}";
  server.send(200, "application/json", j);
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
  server.on("/gps", HTTP_GET, handleGps);
  server.onNotFound([]() { server.send(404, "text/plain", "not found"); });
  server.begin();

#if USE_GPS
  Serial2.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
#endif

  // >>> Taruh setup sensor / logging Anda di sini <<<
}

void loop() {
  server.handleClient();
  if (restartAt && (long)(millis() - restartAt) > 0) ESP.restart();
#if USE_GPS
  gpsPoll();
#endif

  // >>> Taruh kode logging Anda di sini (tulis ke LittleFS sebagai .csv) <<<
  // Jangan blok terlalu lama (pakai millis(), bukan delay panjang),
  // supaya server tetap responsif saat aplikasi mengunduh.
}
