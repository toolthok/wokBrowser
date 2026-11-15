# HTML Editor (viewer + editor)

Satu app Android: editor kode (CodeMirror 6, offline) + pratinjau HTML.

## Cara pakai
- **Buka**: pilih satu/beberapa file → jadi tab. **Simpan** menulis balik ke file asli.
  **⋮ → Simpan sebagai…** untuk file baru / lokasi lain.
- Mode di kanan atas: **Edit**, **Lihat** (pratinjau penuh), **Split** (tablet lebar ≥ 600dp:
  editor + pratinjau berdampingan, pratinjau ikut berubah saat mengetik).
- Bar simbol di atas keyboard: Tab, undo/redo, kursor ◀ ▶, `{} () [] <> "" ''`, dst, dan **Cari**.
- Tab yang belum disimpan ditandai ●. Isi semua tab disimpan otomatis sebagai draf,
  jadi aman kalau app tertutup.
- Pratinjau tetap mendukung tombol **Ekspor/Impor** dan konfirmasi hapus milik catatan.html.
  Data localStorage pratinjau dipisah per **nama file** (sama seperti versi sebelumnya).
- "Buka dengan" dari file manager: HTML → pratinjau, file teks lain → editor.

Syntax highlight: HTML, CSS, JS/TS, JSON, Markdown, Python (sisanya teks biasa).
Batas file: 3 MB, hanya teks UTF-8. Akhiran baris CRLF dipertahankan.

## Build (GitHub Actions)
1. Push project ke repo GitHub (branch `main`).
2. Tab **Actions** → **Build APK** → unduh artifact `HtmlViewer-debug` → ekstrak `app-debug.apk`.
3. Install di tablet.

`debug.keystore` ada di repo, jadi APK dari semua build bertanda tangan sama dan bisa
di-update langsung tanpa uninstall.

> Install pertama atas app HtmlViewer lama: tanda tangan lama berbeda, jadi Android menolak update.
> Ekspor catatan dulu (JSON), uninstall app lama, install ini, lalu Impor.

## Mengubah editor (opsional)
`editor-src/` berisi sumber editor web. Hasil build sudah ada di
`app/src/main/assets/editor.html`; ubah hanya bila perlu:

    cd editor-src && npm install && npm run build
