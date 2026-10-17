package app.floatphone.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Float 小手机安卓壳：全屏 WebView 直接加载线上站点。
 * 网页每次部署即时生效，本壳只负责原生能力（推送长连接、文件上下行、外链）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        val SITE_URL: String = BuildConfig.SITE_URL
        const val VERSION = "1.0.0"
        /** 来电接听等场景的站内深链（必须以 SITE_URL 开头，否则忽略） */
        const val EXTRA_OPEN_URL = "open_url"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 页面加载完后注入的壳增强脚本（通知转发、键盘避让、blob 导出）。 */
    private val shellBridgeScript: String by lazy {
        resources.openRawResource(R.raw.shell_bridge).bufferedReader().use { it.readText() }
    }

    private class PendingSave(
        val stream: OutputStream,
        val uri: Uri?,
        val file: File?,
    )

    private var pendingSave: PendingSave? = null
    private val saveLock = Any()
    private var webNotificationId = 500

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val uris = mutableListOf<Uri>()
        val data = result.data
        val clip = data?.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { uris.add(it) }
            }
        } else if (data?.data != null) {
            uris.add(data.data!!)
        }
        // 取消选择时回 null，WebView 才会正确结束本次 file input。
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) PushService.start(this)
    }

    // 网页侧 getUserMedia（通话按住说话、语音条录音、视频通话摄像头）触发的
    // WebView 权限请求：先要系统运行时权限，拿到后再转授给页面。
    // 不实现 onPermissionRequest 时 WebView 会静默拒绝，页面永远拿不到麦克风。
    private var pendingWebPermissionRequest: android.webkit.PermissionRequest? = null

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val request = pendingWebPermissionRequest ?: return@registerForActivityResult
        pendingWebPermissionRequest = null
        val granted = request.resources.filter { resource ->
            webResourcePermissions(resource).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun webResourcePermissions(resource: String): List<String> = when (resource) {
        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
        else -> emptyList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 刘海/挖孔区域也铺满，避免隐藏状态栏后顶部留一条黑边。
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemStatusBar()
        // 音量键默认调媒体流：WebView 里的语音条/TTS 都走媒体流播放，
        // 不设的话短音频没在播时按键调的是铃声，用户感觉"音量键无效、声音巨大"
        volumeControlStream = AudioManager.STREAM_MUSIC

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            userAgentString = "$userAgentString FloatShell/$VERSION"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.addJavascriptInterface(ShellBridge(), "AndroidShell")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 壳增强脚本必须等页面 DOM 就绪后再注入；SPA 路由切换不会
                // 重新触发 onPageFinished，但已注入的脚本会一直留在页面里。
                runCatching { view.evaluateJavascript(shellBridgeScript, null) }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                // 站内导航留在壳里；http(s) 外链和自定义协议（shortcuts:// 等）交给系统
                if (scheme == "http" || scheme == "https") {
                    if (url.host == Uri.parse(SITE_URL).host) return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url)); true
                    }.getOrDefault(true)
                }
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url)); true
                }.getOrDefault(true)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val supported = request.resources.filter { webResourcePermissions(it).isNotEmpty() }
                if (supported.isEmpty()) { request.deny(); return }
                val missing = supported.flatMap { webResourcePermissions(it) }
                    .distinct()
                    .filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { request.grant(supported.toTypedArray()); return }
                if (pendingWebPermissionRequest != null) { request.deny(); return }
                pendingWebPermissionRequest = request
                webPermissionLauncher.launch(missing.toTypedArray())
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return runCatching {
                    // 网页 accept 里是 .zip/application/zip，部分文件管理器会把
                    // 备份 zip 灰掉。文件选择器放宽成 */*，由网页自己校验内容。
                    val intent = params.createIntent().apply {
                        type = "*/*"
                        removeExtra(Intent.EXTRA_MIME_TYPES)
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    fileChooserLauncher.launch(intent); true
                }.getOrElse {
                    filePathCallback = null; false
                }
            }
        }

        // 备份导出等下载：交给系统下载管理器，落到公共下载目录
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    // blob/data 不能交给 DownloadManager，交给注入脚本分块写 MediaStore。
                    // 正常路径下网页的 <a download> 已被脚本捕获，这里的 handled 去重会跳过。
                    Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
                    webView.evaluateJavascript(
                        "window.__floatShellSaveBlob && window.__floatShellSaveBlob(" +
                            JSONObject.quote(url) + "," +
                            JSONObject.quote(
                                android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                            ) + ");",
                        null,
                    )
                    return@DownloadListener
                }
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType),
                    )
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "已开始下载到「下载」目录", Toast.LENGTH_SHORT).show()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        })

        // 冷启动带深链（如来电接听）直接加载目标；否则加载首页
        webView.loadUrl(consumeOpenUrl(intent) ?: SITE_URL)
        ensurePushService()
    }

    /** 隐藏系统状态栏；用户从顶部上滑时临时出现，松手/失焦后再次隐藏。 */
    private fun hideSystemStatusBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemStatusBar()
    }

    /** singleTask：App 已在运行时（如全屏来电页接听）通过 onNewIntent 送达深链 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = consumeOpenUrl(intent) ?: return
        // SPA 已加载：loadUrl 到同页 hash 只触发 hashchange，不会整页重载
        webView.loadUrl(target)
    }

    private fun consumeOpenUrl(intent: Intent?): String? {
        val target = intent?.getStringExtra(EXTRA_OPEN_URL) ?: return null
        intent.removeExtra(EXTRA_OPEN_URL)
        return target.takeIf { it.startsWith(SITE_URL) }
    }

    private fun ensurePushService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PushService.start(this)
        }
    }

    override fun onDestroy() {
        synchronized(saveLock) { abortPendingSaveLocked() }
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    private fun ensureWebNotificationChannel(): String {
        // 与 PushService 的「角色消息」共用渠道：用户在系统设置里只需管一个开关。
        val channelId = "shell_messages"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "角色消息",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "角色消息提醒（离线推送与网页通知）"
                    },
                )
            }
        }
        return channelId
    }

    private fun nextWebNotificationId(): Int {
        val current = webNotificationId
        webNotificationId = if (webNotificationId >= 900) 500 else webNotificationId + 1
        return current
    }

    private fun sanitizeFilename(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim('.', ' ')
        val name = cleaned.ifBlank { "float-download" }
        if (name.length <= 120) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.take(100) + name.substring(dot) else name.take(120)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists() && index < 1000) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    /** 放弃当前未完成的保存，并删除 MediaStore 里的 pending 项。 */
    private fun abortPendingSaveLocked() {
        val pending = pendingSave ?: return
        pendingSave = null
        runCatching { pending.stream.close() }
        val uri = pending.uri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            runCatching { contentResolver.delete(uri, null, null) }
        }
        pending.file?.delete()
    }

    /** 暴露给网页的原生桥（网页侧可用 window.AndroidShell 特性检测壳环境）。 */
    inner class ShellBridge {
        @JavascriptInterface
        fun getVersion(): String = VERSION

        /** 打开本应用的系统设置页（引导用户关电池限制、开自启动）。 */
        @JavascriptInterface
        fun openAppSettings() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** 请求忽略电池优化（保活关键一步）。 */
        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun requestIgnoreBatteryOptimization() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** 网页本地消息转系统通知。 */
        @JavascriptInterface
        fun notify(title: String, body: String) {
            runCatching {
                val channelId = ensureWebNotificationChannel()
                val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this@MainActivity,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(this@MainActivity, channelId)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title.ifBlank { "小手机" })
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .build()
                getSystemService(NotificationManager::class.java)
                    .notify(nextWebNotificationId(), notification)
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }


        /**
         * 开始一次导出：安卓 10+ 直接写系统「下载」目录（MediaStore，无需存储权限）；
         * 旧系统写应用自己的下载目录。分块写入避免大备份一次性占用内存。
         */
        @JavascriptInterface
        fun beginFileSave(filename: String, mime: String): String = synchronized(saveLock) {
            abortPendingSaveLocked()
            val safeName = sanitizeFilename(filename)
            val safeMime = mime.ifBlank { "application/octet-stream" }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                        put(MediaStore.MediaColumns.MIME_TYPE, safeMime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values,
                    ) ?: throw IllegalStateException("无法创建下载文件")
                    val stream = try {
                        contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("无法写入下载文件")
                    } catch (e: Exception) {
                        runCatching { contentResolver.delete(uri, null, null) }
                        throw e
                    }
                    pendingSave = PendingSave(stream, uri, null)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "正在保存到「下载」…", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: File(filesDir, "downloads").apply { mkdirs() }
                    if (!dir.exists()) dir.mkdirs()
                    val target = uniqueFile(dir, safeName)
                    pendingSave = PendingSave(FileOutputStream(target), null, target)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "正在保存…", Toast.LENGTH_SHORT).show()
                    }
                }
                "ok"
            } catch (e: Exception) {
                "ERR:${e.message ?: "保存失败"}"
            }
        }

        @JavascriptInterface
        fun appendFileSave(base64Chunk: String): String = synchronized(saveLock) {
            val pending = pendingSave
            if (pending == null) {
                "ERR:没有正在保存的文件"
            } else {
                try {
                    pending.stream.write(Base64.decode(base64Chunk, Base64.DEFAULT))
                    "ok"
                } catch (e: Exception) {
                    "ERR:${e.message ?: "写入失败"}"
                }
            }
        }

        @JavascriptInterface
        fun endFileSave(): String = synchronized(saveLock) {
            val pending = pendingSave
            if (pending == null) {
                "ERR:没有正在保存的文件"
            } else {
                pendingSave = null
                try {
                    pending.stream.flush()
                    pending.stream.close()
                    val uri = pending.uri
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        contentResolver.update(uri, values, null, null)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "已保存到「下载」", Toast.LENGTH_SHORT).show()
                        }
                        uri.toString()
                    } else {
                        val path = pending.file?.absolutePath ?: "ok"
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "已保存：$path", Toast.LENGTH_LONG).show()
                        }
                        path
                    }
                } catch (e: Exception) {
                    val pendingUri = pending.uri
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingUri != null) {
                        runCatching { contentResolver.delete(pendingUri, null, null) }
                    }
                    pending.file?.delete()
                    "ERR:${e.message ?: "保存失败"}"
                }
            }
        }

        @JavascriptInterface
        fun abortFileSave() {
            synchronized(saveLock) { abortPendingSaveLocked() }
        }

    }
}
