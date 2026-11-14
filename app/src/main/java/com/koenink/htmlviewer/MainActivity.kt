package com.koenink.htmlviewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.security.MessageDigest

class MainActivity : Activity() {

    companion object {
        private const val REQ_OPEN = 1
        private const val REQ_SAVE = 2
        private const val REQ_CHOOSER = 3
        private const val PREFS = "viewer"
        private const val BASE_DOMAIN = "viewer.local"

        // Menangani tombol unduh (blob:/data:) di dalam halaman, mis. tombol "Ekspor".
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

    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var emptyView: TextView

    private var chooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingBytes: ByteArray? = null
    private var currentUri: Uri? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        val fg = getColor(R.color.bar_fg)
        val bg = getColor(R.color.bar_bg)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        fun barButton(label: String, action: () -> Unit) =
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                transformationMethod = null
                setTextColor(fg)
                setOnClickListener { action() }
            }

        titleView = TextView(this).apply {
            text = "HTML Viewer"
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(fg)
            setPadding(px(8), 0, px(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, wrap, 1f)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
            setPadding(px(6), 0, px(6), 0)
            addView(barButton("Buka") { pickFile() })
            addView(titleView)
            addView(barButton("Muat ulang") { reload() })
        }

        val divider = View(this).apply { setBackgroundColor(getColor(R.color.line)) }

        web = WebView(this)
        emptyView = TextView(this).apply {
            text = "Belum ada file HTML.\nKetuk \"Buka\" untuk memilih file."
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(fg)
            setBackgroundColor(bg)
            setPadding(px(32), px(32), px(32), px(32))
        }
        val frame = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(match, match))
            addView(emptyView, FrameLayout.LayoutParams(match, match))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar, LinearLayout.LayoutParams(match, px(52)))
            addView(divider, LinearLayout.LayoutParams(match, px(1)))
            addView(frame, LinearLayout.LayoutParams(match, 0, 1f))
        }
        setContentView(root)

        setupWebView()

        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null) {
            loadUri(data)
        } else {
            restoreLast()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null) loadUri(data)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true      // supaya localStorage (penyimpanan catatan) jalan
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
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

        web.webChromeClient = object : WebChromeClient() {
            // Input <input type="file"> -> tombol "Impor"
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

            // confirm() dipakai untuk konfirmasi Hapus dan Impor
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

        // Cadangan: unduhan blob:/data: yang tidak lewat tag <a download>
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                web.evaluateJavascript(
                    "window.__vhSave && window.__vhSave(" + JSONObject.quote(url) + "," + JSONObject.quote(name) + ");",
                    null
                )
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }

    // Dipanggil dari JavaScript halaman (lewat HOOK) saat tombol Ekspor ditekan.
    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            runOnUiThread {
                pendingBytes = bytes
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mime.isEmpty()) "application/octet-stream" else mime
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                try {
                    startActivityForResult(intent, REQ_SAVE)
                } catch (e: Exception) {
                    pendingBytes = null
                    toast("Tidak bisa membuka dialog simpan")
                }
            }
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_OPEN)
    }

    private fun reload() {
        val uri = currentUri
        if (uri == null) toast("Belum ada file yang dibuka") else loadUri(uri)
    }

    private fun restoreLast() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString("uri", null) ?: return
        loadUri(Uri.parse(saved))
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
        return uri.lastPathSegment ?: "file.html"
    }

    // Penyimpanan (localStorage) dipisah per NAMA file: file bernama sama = data sama,
    // walau dibuka dari lokasi/aplikasi berbeda.
    private fun originFor(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.lowercase().toByteArray())
        val hex = digest.take(8).joinToString("") { "%02x".format(it) }
        return "https://f$hex.$BASE_DOMAIN/"
    }

    private fun loadUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("file tidak bisa dibaca")
            val html = String(bytes, Charsets.UTF_8)
            val name = displayName(uri)
            val origin = originFor(name)

            currentUri = uri
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("uri", uri.toString()).apply()

            titleView.text = name
            emptyView.visibility = View.GONE
            web.loadDataWithBaseURL(origin, html, "text/html", "UTF-8", origin)
        } catch (e: Exception) {
            toast("Gagal membuka: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OPEN -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {
                    }
                    loadUri(uri)
                }
            }

            REQ_SAVE -> {
                val bytes = pendingBytes
                pendingBytes = null
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
