# ESP32 Hub

Aplikasi Android (±54 KB, tanpa library tambahan) untuk mengunduh file `.csv` dari LittleFS/SPIFFS di banyak ESP32.
Aplikasi tersambung ke satu ESP pada satu waktu, dan tiap ESP punya WiFi (hotspot) sendiri.

```
ESP32Hub.apk            APK siap pasang (ditandatangani debug key, untuk uji coba)
Panduan-ESP32Hub.pdf    Panduan pemakaian bergambar
firmware/EspHub/        Sketch Arduino untuk ESP32 (opsional, lihat "Firmware lain")
android/                Kode sumber aplikasi + build.sh
```

## 1. Firmware ESP32
1. Buka `firmware/EspHub/EspHub.ino` di Arduino IDE (board ESP32, core 2.x atau 3.x).
2. Pilih skema partisi yang memiliki SPIFFS/LittleFS, lalu upload.
3. Tambahkan kode logging Anda di `setup()` / `loop()` (sudah ada penandanya). Tulis data sebagai file `.csv` ke LittleFS.

Setelah menyala pertama kali:
- SSID: `ESP32-xxxxxx` (6 digit terakhir MAC address)
- Password: `12345678`
- Untuk reset ke pengaturan awal: tahan tombol BOOT saat ESP dinyalakan selama ±3 detik.

Upload firmware cukup sekali. Setelah itu, SSID dan password diubah dari aplikasi.

### Firmware lain (tanpa upload ulang)
Aplikasi juga bisa dipakai dengan firmware yang sudah ada, misalnya Aspol, asalkan web server-nya punya:
- Halaman utama `/` yang memuat link unduhan seperti `<a href='/download?file=/nama.csv'>`. Ukuran file di baris tabel yang sama (mis. `12.34 KB`) ikut terbaca. Baris dengan ukuran `-` dilewati.
- Atau alamat unduh tanpa nama file yang disebut di halaman atau file JavaScript-nya, misalnya `/api/data/download` (TDS) atau `/getData` (water level). Alamat ini hanya dianggap file bila ESP menjawab dengan CSV.
- `POST /config` dengan field `ssid` dan `password` (opsional), untuk mengubah WiFi dari aplikasi.

Aplikasi selalu mencoba `/list` lebih dulu. Kalau ESP menjawab 404, aplikasi beralih membaca halaman utama.
Alamat yang mengandung kata seperti `delete`, `restart`, `set`, `save`, `config` tidak pernah dipanggil.

## 2. Aplikasi
- **⚙ Pengaturan**
  - **+ Tambah ESP**: isi ID, SSID, dan password.
  - Untuk ESP baru: centang **Kirim juga ke ESP**, lalu isi login awal (`ESP32-xxxxxx` / `12345678`). Aplikasi akan tersambung ke ESP, mengirim SSID dan password baru, lalu ESP restart.
  - **+ Tambah banyak**: membuat banyak ESP sekaligus, misalnya ESP 1–50 dengan SSID ESP32-1–ESP32-50.
  - Ketuk sebuah ESP untuk mengubah atau menghapusnya.
  - **Tampilan**: Sistem / Terang / Gelap.
- **Download**: tiap ESP tampil sebagai satu kartu.
  - **Buka**: sambung ke ESP dan tampilkan daftar file. **Putus**: lepas WiFi ESP.
  - Ketuk file untuk mengunduh (progres per file). **Unduh semua (N)** mengunduh semua file ESP itu.
  - File bertanda **✓** diketuk untuk dibuka di Google Sheets (atau aplikasi CSV lain). Tekan lama untuk unduh ulang.
  - **Buka web**: membuka halaman web ESP di dalam aplikasi untuk melihat dashboard dan mengatur alat.
  - **Unduh semua** (bawah layar): unduh dari semua ESP yang tampil, satu per satu.
- File tersimpan di `Download/ESP32Hub/<ID ESP>/` dengan tanggal di nama file, misalnya `data_2026-10-01_2026-10-31.csv`.
  Tanggal diambil dari isi file (pertama dan terakhir); bila tidak ada, dari halaman web ESP, lalu dari tanggal HP.

## Catatan
- Android 10 ke atas selalu menampilkan pop-up "Hubungkan ke …" saat tersambung ke ESP. Ketuk **Hubungkan**. Android 11 ke atas biasanya mengingat pilihan ini.
- Minimal Android 10.
- Password ESP disimpan di HP (data aplikasi, tidak di-backup).
- Selama mengunduh, ESP sebaiknya tidak sedang menulis ke file yang sama.

## Build ulang APK
Butuh Android SDK (platform 34) dan JDK 11 atau lebih baru. Tidak perlu Gradle.
```bash
bash android/build.sh
```
Untuk APK rilis dengan keystore sendiri:
```bash
KEYSTORE=rilis.jks KS_PASS=xxx KEY_ALIAS=xxx bash android/build.sh
```
