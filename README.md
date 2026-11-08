# ESP32 Hub

Aplikasi Android (±99 KB, tanpa library tambahan) untuk mengunduh file `.csv` dari LittleFS/SPIFFS di banyak ESP32.
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

GPS (untuk layar Peta): sambungkan modul GPS NMEA (mis. NEO-6M) — TX modul ke GPIO16, VCC, GND. Firmware membacanya
tanpa library dan menyediakan `GET /gps`. Pin/baud diatur di `GPS_RX_PIN`, `GPS_BAUD`. Sudah punya kode GPS sendiri?
Set `USE_GPS 0` lalu panggil `gpsSet(lat, lon, alt, sats, hdop)` tiap ada posisi baru.

Upload firmware cukup sekali. Setelah itu, SSID dan password diubah dari aplikasi.

### Firmware lain (tanpa upload ulang)
Aplikasi juga bisa dipakai dengan firmware yang sudah ada, misalnya Aspol, asalkan web server-nya punya:
- Halaman utama `/` yang memuat link unduhan seperti `<a href='/download?file=/nama.csv'>`. Ukuran file di baris tabel yang sama (mis. `12.34 KB`) ikut terbaca. Baris dengan ukuran `-` dilewati.
- Atau alamat unduh tanpa nama file yang disebut di halaman atau file JavaScript-nya, misalnya `/api/data/download` (TDS) atau `/getData` (water level). Alamat ini hanya dianggap file bila ESP menjawab dengan CSV.
- `POST /config` dengan field `ssid` dan `password` (opsional), untuk mengubah WiFi dari aplikasi.

Aplikasi selalu mencoba `/list` lebih dulu. Kalau ESP menjawab 404, aplikasi beralih membaca halaman utama.
Alamat yang mengandung kata seperti `delete`, `restart`, `set`, `save`, `config` tidak pernah dipanggil.

## 2. Aplikasi
- **Panduan** (ikon ? di kanan atas, atau **Buka panduan bergambar** di Pengaturan): panduan singkat bergambar di dalam aplikasi,
  bisa dibuka tanpa internet. Sumbernya `android/app/src/main/assets/panduan.html`.
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
- **Peta** (ikon peta di kanan atas, atau tombol **Peta GPS** di kartu ESP yang tersambung):
  - **Impor**: pilih file peta **GeoJSON** (`.geojson`/`.json`), **KML**, **KMZ**, atau **GeoPDF** (`.pdf`). Bisa beberapa file (lapisan) sekaligus.
    Peta disalin ke HP, jadi tetap tampil **tanpa internet**. Warna dari file (simplestyle GeoJSON, Style/StyleMap KML) ikut dipakai.
  - Titik biru **Sensor** = lokasi GPS sensor, dibaca tiap 2 detik dari `GET /gps` ESP yang sedang tersambung, dengan jejak gerakannya.
    Tanpa sambungan, posisi terakhir tetap tampil (abu-abu, "Sensor (terakhir)").
  - Titik hijau **HP** = lokasi HP sendiri (GPS HP, jalan tanpa internet), dengan lingkaran akurasi. Tampil bersama titik sensor,
    jadi bisa dipakai saat tidak ada sensor atau untuk berjalan menuju sensor. Aplikasi meminta izin lokasi saat Peta pertama dibuka.
  - Geser dengan satu jari, cubit untuk zoom, ketuk dua kali untuk memperbesar. **Semua**: tampilkan seluruh peta.
    **Lokasi**: ke sensor (bila sedang menerima posisi), kalau tidak ke lokasi HP.
  - **Lapisan**: centang untuk menampilkan/menyembunyikan peta, **Hapus…** untuk menghapus dari aplikasi.
  - **+ Titik** → **Tambah titik**: satu tekan menambah titik di posisi **GPS HP** saat ini, dengan nama otomatis
    (Titik 1, Titik 2, … nomornya terus berlanjut). Titik **hanya bisa ditambah bila akurasi GPS HP ≤ 5 m**; kalau lebih dari
    5 m tombolnya redup dan muncul peringatan. Petunjuk di atas tombol menampilkan akurasi saat ini (hijau = siap, oranye = belum).
    Mengetuk peta tidak menambah titik. Ketuk titik oranye untuk mengganti nama atau menghapus.
    Titik tetap tersimpan walau aplikasi ditutup.
  - **Simpan peta**: peta yang tampil + titik disimpan sebagai **file peta baru**:
    - ada peta PDF yang tampil → **GeoPDF** baru (titik dan lapisan vektor digambar di atas peta PDF, koordinat ikut tersimpan);
    - tanpa PDF → **GeoJSON** baru berisi semua bentuk dari lapisan yang tampil + titik (warna ikut tersimpan).
    Peta baru langsung jadi lapisan di aplikasi (lapisan lama disembunyikan), dan salinannya ada di `Download/ESP32Hub/Peta/`
    untuk dikirim lewat WA atau dibuka di QGIS / Avenza / Google Earth.
  - **Data titik (GeoJSON)**: centang "Simpan juga titik saja sebagai GeoJSON" (aktif bawaan) saat Simpan peta. Hasilnya
    `Download/ESP32Hub/Peta/<nama peta> - titik.geojson`, berisi titik saja dengan nama, `lat`, `lon`, waktu dibuat, dan `akurasi_m`,
    juga bila peta dasarnya PDF. Bisa dibuka di QGIS, geojson.io, atau diimpor lagi ke aplikasi ini.
  - **GeoPDF**: PDF yang menyimpan koordinat (ekspor GeoPDF dari QGIS, GDAL, ArcGIS, Avenza, dsb.). Koordinat dibaca dari
    format ISO 32000 (`/VP` + `/Measure` `/GEO`) atau OGC/TerraGo `LGIDict` (proyeksi geografis atau UTM). Halaman digambar
    sebagai gambar (maks. 4096 px) di bawah lapisan lain dan titik GPS. PDF tanpa koordinat (scan biasa) belum bisa dipakai.
  - Belum didukung: gambar foto udara di KML (GroundOverlay), PDF tanpa koordinat, dan peta online. Contoh file ada di `contoh-peta/`.
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
