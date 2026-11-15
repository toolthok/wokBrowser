package com.koenink.htmlviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Satu app, dua WebView:
 *  - editor  : halaman editor (CodeMirror) dari assets/editor.html, origin tetap
 *  - preview : pratinjau HTML, origin per NAMA file (localStorage catatan.html tetap sama seperti app lama)
 * Kotlin hanya jadi jembatan file (buka/simpan) dan pengatur layout (Edit / Lihat / Split).
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_OPEN = 1          // buka file ke editor
        private const val REQ_SAVE_DOC = 2      // simpan sebagai (dari editor)
        private const val REQ_DOWNLOAD = 3      // unduhan dari pratinjau (tombol Ekspor)
        private const val REQ_CHOOSER = 4       // <input type=file> di pratinjau (tombol Impor)
        private const val PREFS = "viewer"
        private const val BASE_DOMAIN = "viewer.local"
        private const val EDITOR_ORIGIN = "https://editor.$BASE_DOMAIN/"
        private const val MAX_BYTES = 3_000_000

        // Menangani tombol unduh (blob:/data:) di halaman pratinjau, mis. tombol "Ekspor".
        private val HOOK = """
(function(){
  if (window.__vh) return;
  window.__vh = true;
  window.__vhSave = function(href, name){
    fetch(href).then(function(r){ return r.blob(); }).then(function(b){
      var fr = new FileReader();
      fr.onload = function(){
        var s = String(fr.result);
        Android.saveFile(name, b.type || 'application/octet-stream', s.substring(s.indexOf(',') + 1));
      };
      fr.readAsDataURL(b);
    });
  };
  document.addEventListener('click', function(e){
    var a = (e.target && e.target.closest) ? e.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.href || '';
    if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    window.__vhSave(href, a.getAttribute('download') || 'file');
  }, true);
})();
""".trimIndent()
    }

    private lateinit var editor: WebView
    private lateinit var preview: WebView
    private lateinit var content: LinearLayout
    private lateinit var btnEdit: Button
    private lateinit var btnView: Button
    private lateinit var btnSplit: Button

    private var mode = "edit"
    private var editorReady = false
    private val pendingJs = ArrayList<String>()

    private var chooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: ByteArray? = null
    private var pendingSaveBytes: ByteArray? = null
    private var pendingSaveTab: String? = null

    private fun isWide() = resources.configuration.screenWidthDp >= 600

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        val fg = getColor(R.color.bar_fg)
        val bg = getColor(R.color.bar_bg)
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        fun barButton(label: String, action: (View) -> Unit) =
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                transformationMethod = null
                minWidth = 0
                minimumWidth = 0
                setPadding(px(10), 0, px(10), 0)
                setTextColor(fg)
                setOnClickListener { action(it) }
            }

        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        btnEdit = barButton("Edit") { applyMode("edit") }
        btnView = barButton("Lihat") { applyMode("view") }
        btnSplit = barButton("Split") { applyMode("split") }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
            setPadding(px(4), 0, px(4), 0)
            addView(barButton("Buka") { pickFiles() })
            addView(barButton("Simpan") { callEditor("App.requestSave(false);") })
            addView(barButton("\u22EE") { showMenu(it) })
            addView(spacer)
            addView(btnEdit)
            addView(btnView)
            addView(btnSplit)
        }
        val divider = View(this).apply { setBackgroundColor(getColor(R.color.line)) }

        editor = WebView(this)
        preview = WebView(this)
        content = LinearLayout(this)
        content.addView(editor)
        content.addView(preview)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar, LinearLayout.LayoutParams(match, px(52)))
            addView(divider, LinearLayout.LayoutParams(match, px(1)))
            addView(content, LinearLayout.LayoutParams(match, 0, 1f))
        }
        setContentView(root)

        setupEditor()
        setupPreview()

        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("mode", null)
        applyMode(saved ?: if (isWide()) "split" else "edit")

        val html = assets.open("editor.html").bufferedReader().use { it.readText() }
        editor.loadDataWithBaseURL(EDITOR_ORIGIN, html, "text/html", "UTF-8", EDITOR_ORIGIN)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyMode(mode)
    }

    override fun onPause() {
        super.onPause()
        callEditor("App.persist();")
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (mode == "view" && !isWide()) applyMode("edit") else super.onBackPressed()
    }

    /* ---------------- layout / mode ---------------- */

    private fun applyMode(requested: String) {
        val wide = isWide()
        mode = if (requested == "split" && !wide) "edit" else requested

        val cfg = resources.configuration
        content.orientation = if (cfg.screenWidthDp > cfg.screenHeightDp) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val horizontal = content.orientation == LinearLayout.HORIZONTAL
        for (v in listOf(editor, preview)) {
            v.layoutParams = if (horizontal) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        editor.visibility = if (mode == "view") View.GONE else View.VISIBLE
        preview.visibility = if (mode == "edit") View.GONE else View.VISIBLE

        btnSplit.visibility = if (wide) View.VISIBLE else View.GONE
        for ((b, m) in listOf(btnEdit to "edit", btnView to "view", btnSplit to "split")) {
            val on = mode == m
            b.alpha = if (on) 1f else 0.55f
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("mode", mode).apply()
        callEditor("App.setMode('$mode');")
    }

    private fun showMenu(anchor: View) {
        val items = listOf(
            "Berkas baru" to "App.newTab();",
            "Simpan sebagai\u2026" to "App.requestSave(true);",
            "Cari & ganti" to "App.find();",
            "Perbesar huruf" to "App.font(1);",
            "Perkecil huruf" to "App.font(-1);",
            "Bungkus baris" to "App.toggleWrap();",
            "Muat ulang pratinjau" to "App.pushPreview();"
        )
        val pm = PopupMenu(this, anchor)
        items.forEachIndexed { i, item -> pm.menu.add(0, i, i, item.first) }
        pm.setOnMenuItemClickListener { mi ->
            callEditor(items[mi.itemId].second)
            true
        }
        pm.show()
    }

    /* ---------------- komunikasi ke editor ---------------- */

    private fun callEditor(js: String) {
        if (editorReady) editor.evaluateJavascript(js, null) else pendingJs.add(js)
    }

    private fun q(s: String) = JSONObject.quote(s)

    /* ---------------- WebView ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupEditor() {
        editor.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        editor.addJavascriptInterface(EditorBridge(), "Android")
        editor.webChromeClient = ViewerChrome()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPreview() {
        preview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true       // localStorage (mis. penyimpanan catatan.html)
            allowFileAccess = false
            allowContentAccess = false
        }
        preview.addJavascriptInterface(PreviewBridge(), "Android")
        preview.webChromeClient = ViewerChrome()

        preview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(HOOK, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") {
                    val host = u.host ?: ""
                    if (host.endsWith(BASE_DOMAIN)) return false   // anchor (#bagian) di halaman sendiri
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (_: ActivityNotFoundException) {
                    }
                    return true
                }
                return false
            }
        }

        // Cadangan: unduhan blob:/data: yang tidak lewat <a download>
        preview.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                preview.evaluateJavascript(
                    "window.__vhSave && window.__vhSave(" + q(url) + "," + q(name) + ");", null
                )
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }

    // Dipakai kedua WebView: dialog confirm/alert dan pemilih file (<input type=file>)
    inner class ViewerChrome : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            chooserCallback?.onReceiveValue(null)
            chooserCallback = filePathCallback
            val multiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
            return try {
                startActivityForResult(Intent.createChooser(intent, "Pilih file"), REQ_CHOOSER)
                true
            } catch (e: Exception) {
                chooserCallback = null
                filePathCallback?.onReceiveValue(null)
                false
            }
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            if (result == null) return false
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setNegativeButton("Batal") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            if (result == null) return false
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            return true
        }
    }

    /* ---------------- jembatan JS <-> native ---------------- */

    inner class EditorBridge {
        @JavascriptInterface
        fun ready() {
            runOnUiThread {
                editorReady = true
                pendingJs.forEach { editor.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
        }

        @JavascriptInterface
        fun openFile() {
            runOnUiThread { pickFiles() }
        }

        @JavascriptInterface
        fun save(uri: String, name: String, text: String, tabId: String) {
            runOnUiThread { doSave(uri, name, text, tabId) }
        }

        @JavascriptInterface
        fun preview(name: String, html: String) {
            runOnUiThread {
                val origin = originFor(name)
                this@MainActivity.preview.loadDataWithBaseURL(origin, html, "text/html", "UTF-8", origin)
            }
        }
    }

    // Untuk tombol Ekspor di halaman pratinjau (blob -> base64 -> dialog simpan)
    inner class PreviewBridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            runOnUiThread {
                pendingDownload = bytes
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mime.isEmpty()) "application/octet-stream" else mime
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                try {
                    startActivityForResult(intent, REQ_DOWNLOAD)
                } catch (e: Exception) {
                    pendingDownload = null
                    toast("Tidak bisa membuka dialog simpan")
                }
            }
        }
    }

    /* ---------------- file ---------------- */

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_OPEN)
    }

    private fun persist(uri: Uri, flags: Int) {
        try {
            val f = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (f != 0) contentResolver.takePersistableUriPermission(uri, f)
        } catch (_: Exception) {
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) {
                        val n = c.getString(i)
                        if (!n.isNullOrEmpty()) return n
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file.txt"
    }

    // Penyimpanan pratinjau (localStorage) dipisah per NAMA file, sama seperti versi sebelumnya.
    private fun originFor(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.lowercase().toByteArray())
        val hex = digest.take(8).joinToString("") { "%02x".format(it) }
        return "https://f$hex.$BASE_DOMAIN/"
    }

    private fun openUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("file tidak bisa dibaca")
            if (bytes.size > MAX_BYTES) {
                toast("File terlalu besar (maks 3 MB)")
                return
            }
            if (bytes.any { it.toInt() == 0 }) {
                toast("Sepertinya bukan file teks")
                return
            }
            var text = String(bytes, Charsets.UTF_8)
            if (text.startsWith("\uFEFF")) text = text.substring(1)
            val o = JSONObject()
                .put("uri", uri.toString())
                .put("name", displayName(uri))
                .put("text", text)
            callEditor("App.openTab(" + o.toString() + ");")
        } catch (e: Exception) {
            toast("Gagal membuka: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun writeText(uri: Uri, text: String) {
        val out = contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("tidak bisa menulis")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun doSave(uriStr: String, name: String, text: String, tabId: String) {
        if (uriStr.isNotEmpty()) {
            try {
                val uri = Uri.parse(uriStr)
                writeText(uri, text)
                callEditor("App.saved(" + q(tabId) + "," + q(uriStr) + "," + q(displayName(uri)) + ");")
                toast("Tersimpan")
                return
            } catch (e: Exception) {
                toast("Tidak bisa menulis ke file asal. Pilih lokasi simpan.")
            }
        }
        pendingSaveBytes = text.toByteArray(Charsets.UTF_8)
        pendingSaveTab = tabId
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(intent, REQ_SAVE_DOC)
        } catch (e: Exception) {
            pendingSaveBytes = null
            pendingSaveTab = null
            toast("Tidak bisa membuka dialog simpan")
        }
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data ?: return
        val action = i.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_EDIT) return
        persist(data, i.flags)
        openUri(data)
        val type = i.type ?: contentResolver.getType(data)
        val htmlView = action == Intent.ACTION_VIEW && type == "text/html"
        applyMode(if (htmlView) (if (isWide()) "split" else "view") else "edit")
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OPEN -> {
                if (resultCode != RESULT_OK || data == null) return
                val flags = data.flags
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        val u = clip.getItemAt(i).uri ?: continue
                        persist(u, flags)
                        openUri(u)
                    }
                } else {
                    data.data?.let { persist(it, flags); openUri(it) }
                }
            }

            REQ_SAVE_DOC -> {
                val bytes = pendingSaveBytes
                val id = pendingSaveTab
                pendingSaveBytes = null
                pendingSaveTab = null
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null && bytes != null && id != null) {
                    persist(uri, data.flags)
                    try {
                        writeText(uri, String(bytes, Charsets.UTF_8))
                        callEditor("App.saved(" + q(id) + "," + q(uri.toString()) + "," + q(displayName(uri)) + ");")
                        toast("Tersimpan")
                    } catch (e: Exception) {
                        toast("Gagal menyimpan: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
            }

            REQ_DOWNLOAD -> {
                val bytes = pendingDownload
                pendingDownload = null
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null && bytes != null) {
                    try {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        toast("File tersimpan")
                    } catch (e: Exception) {
                        toast("Gagal menyimpan: " + (e.message ?: e.javaClass.simpleName))
                    }
                }
            }

            REQ_CHOOSER -> {
                val cb = chooserCallback
                chooserCallback = null
                val result = if (resultCode == RESULT_OK)
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                else null
                cb?.onReceiveValue(result)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
