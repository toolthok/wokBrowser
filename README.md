# HTML Viewer

App Android super sederhana: membuka file HTML lokal di WebView.

- Tombol **Buka**: pilih file HTML (file picker). File terakhir dibuka otomatis saat app dijalankan.
- Tombol **Muat ulang**: baca ulang file (berguna setelah HTML diedit).
- **Buka dengan** dari file manager juga bisa (file bertipe text/html).
- Tombol **Ekspor** di halaman → dialog "Simpan" muncul, pilih lokasi simpan.
- Tombol **Impor** di halaman → file picker muncul, pilih file JSON.
- `confirm()` / `alert()` halaman tampil sebagai dialog.
- Data `localStorage` dipisah per **nama file**. Ganti nama file = data terlihat kosong
  (kembalikan nama lama, atau pakai Ekspor/Impor).

## Build (GitHub Actions)

1. Push project ini ke repo GitHub (branch `main`).
2. Tab **Actions** → workflow **Build APK** (jalan otomatis saat push, atau Run workflow).
3. Selesai → buka run-nya → **Artifacts** → unduh `HtmlViewer-debug` → ekstrak → `app-debug.apk`.
4. Install di tablet (izinkan "install dari sumber tidak dikenal").

Tidak butuh gradle wrapper; workflow memasang Gradle 8.9 sendiri.
