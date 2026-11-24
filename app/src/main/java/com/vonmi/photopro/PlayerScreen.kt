package com.vonmi.photopro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PAccent = Color(0xFFFFC83D)
private val PPanel = Color(0xFF16181D)
private val PDim = Color(0xFF9AA0AA)

data class VideoItem(val id: Long, val uri: Uri, val name: String, val durationMs: Long, val size: Long)

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun PlayerScreen(onClose: () -> Unit, initialUri: Uri? = null, initialText: String? = null) {
    val sharedUrl = initialText?.let { Regex("https?://\\S+").find(it)?.value }
    val isYt = { u: String -> youTubeWebUrl(u) != null }
    val startUri = initialUri ?: sharedUrl?.takeIf { !isYt(it) }?.let { Uri.parse(it) }
    val startYt = (initialUri?.toString()?.takeIf { isYt(it) }) ?: sharedUrl?.takeIf { isYt(it) }
    val direct = startUri != null && startYt == null
    var tab by remember { mutableIntStateOf(if (startYt != null) 1 else 0) }
    var playing by remember { mutableStateOf(if (direct) startUri else null) }
    var ytUrl by remember { mutableStateOf(startYt ?: "https://m.youtube.com") }

    BackHandler(enabled = tab == 0 && playing == null) { onClose() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("← Caméra", color = Color.White) }
            Spacer(Modifier.weight(1f))
            PTab("🎞 Mes vidéos", tab == 0) { tab = 0 }
            Spacer(Modifier.width(6.dp))
            PTab("▶ YouTube", tab == 1) { tab = 1 }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) LocalVideos(
                onPlay = { playing = it },
                onYouTube = { url -> ytUrl = url; tab = 1 }
            ) else YouTubeTab(ytUrl, onExit = { tab = 0 })
        }
    }

    playing?.let { uri -> VideoPlayer(uri) { if (direct && uri == startUri) onClose() else playing = null } }
}

@Composable
private fun PTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        color = if (selected) Color.Black else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) PAccent else PPanel)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

// ---------------------------------------------------------------------
// Vidéos du téléphone

@Composable
private fun LocalVideos(onPlay: (Uri) -> Unit, onYouTube: (String) -> Unit) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    var refresh by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPerm = it; refresh++
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPlay(uri)
    }
    var videos by remember { mutableStateOf<List<VideoItem>?>(null) }
    LaunchedEffect(refresh) { videos = loadVideos(ctx) }
    var link by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, singleLine = true,
                placeholder = { Text("Coller un lien (YouTube ou vidéo .mp4)") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                val l = link.trim()
                if (l.isNotEmpty()) {
                    val url = if (l.startsWith("http")) l else "https://$l"
                    if (youTubeWebUrl(url) != null) onYouTube(url) else onPlay(Uri.parse(url))
                }
            }) { Text("Lire", color = Color.Black) }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openDoc.launch(arrayOf("video/*")) }) { Text("📂 Fichier") }
            OutlinedButton(onClick = {
                val i = if (Build.VERSION.SDK_INT >= 31)
                    Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:" + ctx.packageName))
                else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("🔗 Liens YouTube") }
            if (!hasPerm) OutlinedButton(onClick = { permLauncher.launch(perm) }) { Text("Voir toutes les vidéos") }
        }
        val list = videos
        when {
            list == null -> Text("Chargement…", color = PDim)
            list.isEmpty() -> Text(
                "Aucune vidéo trouvée. Filmez avec le mode VIDÉO ou ouvrez un fichier.",
                color = PDim, modifier = Modifier.padding(top = 20.dp)
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { v -> VideoRow(v) { onPlay(v.uri) } }
            }
        }
    }
}

@Composable
private fun VideoRow(v: VideoItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, v.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(v.uri, Size(320, 180), null)
                else legacyThumb(ctx, v.id)
            }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PPanel).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 112.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                fmtDuration(v.durationMs), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text("%.1f Mo".format(v.size / 1_048_576.0), color = PDim, fontSize = 12.sp)
        }
    }
}

@Suppress("DEPRECATION")
private fun legacyThumb(ctx: Context, id: Long): Bitmap? =
    MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)

private fun fmtDuration(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

private suspend fun loadVideos(ctx: Context): List<VideoItem> = withContext(Dispatchers.IO) {
    val out = mutableListOf<VideoItem>()
    val proj = arrayOf(
        MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE
    )
    runCatching {
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
            MediaStore.Video.Media.DATE_ADDED + " DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext() && out.size < 500) {
                val id = c.getLong(iId)
                out += VideoItem(
                    id,
                    Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    c.getString(iName) ?: "Vidéo",
                    c.getLong(iDur), c.getLong(iSize)
                )
            }
        }
    }
    out
}

// ---------------------------------------------------------------------
// Lecteur plein écran

@Composable
private fun VideoPlayer(uri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    BackHandler { onClose() }
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                val frame = FrameLayout(c)
                val vv = VideoView(c)
                frame.addView(vv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                ))
                val mc = MediaController(c)
                mc.setAnchorView(frame)
                vv.setMediaController(mc)
                vv.setOnPreparedListener { mp -> player = mp; vv.start(); mc.show(3000) }
                vv.setOnErrorListener { _, _, _ -> false }
                vv.setVideoURI(uri)
                frame
            },
            onRelease = { f -> ((f as FrameLayout).getChildAt(0) as? VideoView)?.stopPlayback() },
            modifier = Modifier.fillMaxSize()
        )
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "✕", color = Color.White, fontSize = 20.sp,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x99000000))
                    .clickable { onClose() }.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            Text(
                "Vitesse x${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}",
                color = PAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x99000000))
                    .clickable {
                        val next = when (speed) { 1f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }
                        val mp = player
                        if (mp != null) runCatching {
                            val playingNow = mp.isPlaying
                            mp.playbackParams = mp.playbackParams.setSpeed(next)
                            if (!playingNow) mp.pause()
                            speed = next
                        }
                    }.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------
// YouTube

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeTab(startUrl: String, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    var custom by remember { mutableStateOf<View?>(null) }
    var customCb by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var progress by remember { mutableIntStateOf(0) }

    val web = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            // YouTube refuse parfois la lecture dans une « WebView » : on se présente comme Chrome mobile
            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            // Consentement déjà donné (« refuser les cookies non essentiels ») : évite le bandeau qui bloque la page
            for (host in listOf("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com")) {
                cm.setCookie(host, "SOCS=CAI; Domain=.youtube.com; Path=/; Secure; Max-Age=31536000")
                cm.setCookie(host, "CONSENT=PENDING+987; Domain=.youtube.com; Path=/; Secure; Max-Age=31536000")
            }
            cm.flush()
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    if (scheme == "http" || scheme == "https") return false
                    // Liens qui voudraient ouvrir l'appli YouTube : on reste dans Photo Pro
                    val inApp = youTubeWebUrl(request.url.toString())
                    if (inApp != null) { view.loadUrl(inApp); return true }
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    custom = view; customCb = callback
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                override fun onHideCustomView() {
                    custom = null; customCb = null
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            loadUrl(youTubeWebUrl(startUrl) ?: startUrl)
        }
    }
    LaunchedEffect(startUrl) {
        val u = youTubeWebUrl(startUrl) ?: startUrl
        if (web.url != u) web.loadUrl(u)
    }
    DisposableEffect(Unit) {
        web.onResume()
        onDispose {
            customCb?.onCustomViewHidden()
            web.stopLoading()
            web.onPause()
            web.destroy()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    BackHandler {
        when {
            custom != null -> customCb?.onCustomViewHidden()
            web.canGoBack() -> web.goBack()
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
        if (progress in 1..99) LinearProgressIndicator(
            progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter), color = PAccent
        )
        val cv = custom
        if (cv != null) key(cv) {
            AndroidView(factory = { cv }, modifier = Modifier.fillMaxSize().background(Color.Black))
        }
    }
}


/** Convertit tout lien YouTube (youtu.be, shorts, vnd.youtube:, intent://, music…) en page m.youtube.com. */
fun youTubeWebUrl(raw: String): String? {
    val u = raw.trim()
    if (u.startsWith("intent://")) {
        Regex("S\\.browser_fallback_url=([^;]+)").find(u)?.groupValues?.get(1)?.let { return Uri.decode(it) }
        val path = u.removePrefix("intent://").substringBefore("#Intent")
        return youTubeWebUrl("https://$path")
    }
    if (u.startsWith("vnd.youtube:")) {
        val id = u.removePrefix("vnd.youtube:").removePrefix("//").substringBefore("?")
        return "https://m.youtube.com/watch?v=$id"
    }
    val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val id: String? = when {
        host.endsWith("youtu.be") -> uri.pathSegments.firstOrNull()
        host.contains("youtube.com") && (uri.pathSegments.firstOrNull() ?: "") in listOf("shorts", "live", "embed") ->
            uri.pathSegments.getOrNull(1)
        host.contains("youtube.com") -> uri.getQueryParameter("v")
        else -> return null
    }
    if (id.isNullOrBlank()) return if (host.contains("youtube.com")) u.replace("://www.", "://m.").replace("://music.", "://m.") else null
    val t = uri.getQueryParameter("t")?.let { "&t=$it" } ?: ""
    return "https://m.youtube.com/watch?v=$id$t"
}
