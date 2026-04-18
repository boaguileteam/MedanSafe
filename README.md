# MedanSafe – Smart Safety Navigation

MedanSafe adalah aplikasi mobile berbasis Android yang menyediakan navigasi berbasis keamanan dengan memanfaatkan data komunitas secara real-time. Aplikasi ini dirancang untuk membantu masyarakat Kota Medan dalam memilih rute perjalanan yang lebih aman serta menyediakan sistem darurat berbasis lokasi.

---

## Deskripsi Singkat

MedanSafe merupakan **Smart Safety Navigation System** yang mengintegrasikan navigasi peta, pelaporan insiden berbasis crowdsourcing, analisis risiko rute, dan fitur darurat SOS dalam satu platform. Aplikasi ini tidak hanya mempertimbangkan jarak dan waktu, tetapi juga faktor keamanan dalam menentukan rute perjalanan.

---

## Latar Belakang

Kota Medan sebagai salah satu kota metropolitan terbesar di Indonesia menghadapi berbagai permasalahan keamanan, seperti:

- Tingginya angka kriminalitas jalanan (begal, penjambretan)
- Kecelakaan lalu lintas
- Minimnya penerangan di beberapa wilayah
- Tidak adanya platform digital untuk berbagi informasi keamanan secara real-time

Kondisi ini menyebabkan masyarakat kesulitan dalam menentukan rute yang aman saat beraktivitas, terutama pada malam hari. MedanSafe hadir sebagai solusi berbasis teknologi dan partisipasi komunitas untuk meningkatkan keamanan kota secara kolektif. :contentReference[oaicite:0]{index=0} :contentReference[oaicite:1]{index=1}

---

## Tujuan Aplikasi

Pengembangan MedanSafe bertujuan untuk:

- Menyediakan platform pelaporan insiden keamanan secara real-time berbasis komunitas
- Memberikan rekomendasi rute perjalanan yang lebih aman
- Menghadirkan fitur darurat SOS dengan pengiriman lokasi otomatis
- Membangun ekosistem keamanan kota berbasis partisipasi masyarakat
- Mendukung implementasi konsep Smart City di Kota Medan

---

## Fitur Utama

### 🔐 Peta Keamanan Real-time
- Visualisasi heatmap dan marker insiden
- Data diperbarui secara real-time dari komunitas

### 🧭 Navigasi Rute Aman
- Mode **FASTEST** (tercepat) dan **SAFEST** (teraman)
- Skor keamanan rute (0–100)

### 🚗 Mode Kendaraan Adaptif
- Kereta/Motor: dapat melalui gang dan jalan kecil
- Mobil: hanya melalui jalan utama

### 📢 Pelaporan Insiden
- Kategori: Begal, Jalan Gelap, Kecelakaan, Lainnya
- Dilengkapi lokasi GPS dan foto opsional

### 🚨 SOS Darurat
- Tombol satu ketuk untuk mengirim lokasi GPS
- Terhubung ke kontak darurat melalui SMS dan notifikasi

### 🧑‍💼 Panel Admin
- Verifikasi laporan insiden
- Menjaga akurasi dan kualitas data

### 📊 Community Feed
- Feed laporan insiden real-time
- Fitur upvote dan komentar

---

## Teknologi yang Digunakan

| Kategori            | Teknologi                         |
|--------------------|----------------------------------|
| Bahasa             | Kotlin (Android Native)           |
| Arsitektur         | MVVM                             |
| Database           | Firebase Firestore               |
| Autentikasi        | Firebase Authentication          |
| Notifikasi         | Firebase Cloud Messaging (FCM)   |
| Peta               | OpenStreetMap (OSMDroid)         |
| Routing            | OSRM (Open Source Routing)       |
| Geocoding          | Nominatim API                    |
| Lokasi             | Fused Location Provider          |
| HTTP Client        | OkHttp3                          |
| UI/UX Design       | Figma                            |

---

## Cara Instalasi

### Prasyarat
- Android Studio (versi terbaru)
- JDK 17+
- Perangkat Android minimal API 21 (Android 5.0)

### Langkah Instalasi

```bash
# Clone repository
git clone https://github.com/username/MedanSafe.git

# Masuk ke direktori project
cd MedanSafe

# Buka di Android Studio
