package com.vonmi.photopro

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.Brush
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val Accent = Color(0xFFFFC83D)
private val Panel = Color(0xFF16181D)
private val Dim = Color(0xFF9AA0AA)

class MainActivity : ComponentActivity() {

    private lateinit var ctrl: CameraController
    private var granted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullScreen()
        ctrl = CameraController(applicationContext)
        granted.value = hasCameraPermission()
        handleCaptureIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Panel)) {
                Root(ctrl, granted)
            }
        }
    }

    /** Une autre appli demande une photo ou une vidéo : on lui renvoie le résultat. */
    private fun handleCaptureIntent(i: Intent?) {
        val action = i?.action ?: return
        val isPhoto = action == MediaStore.ACTION_IMAGE_CAPTURE
        val isVideo = action == MediaStore.ACTION_VIDEO_CAPTURE
        if (action == MediaStore.INTENT_ACTION_VIDEO_CAMERA) { ctrl.update(ctrl.settings.copy(video = true, pro = false)); return }
        if (action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) { if (ctrl.settings.video) ctrl.update(ctrl.settings.copy(video = false)); return }
        if (!isPhoto && !isVideo) return
        @Suppress("DEPRECATION")
        val out = i.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT) ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        ctrl.externalOutput = out
        ctrl.update(ctrl.settings.copy(video = isVideo, pro = false, timer = 0))
        ctrl.externalRequest = { uri, bytes ->
            val data = Intent()
            if (uri != null) {
                data.data = uri
                data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else if (bytes != null) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { data.putExtra("data", it) }
            }
            setResult(RESULT_OK, data)
            finish()
        }
    }

    /** Plein écran (barres système masquées, encoche utilisée) + taux de rafraîchissement maximal (120 Hz). */
    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        runCatching {
            @Suppress("DEPRECATION")
            val d = if (Build.VERSION.SDK_INT >= 30) display!! else windowManager.defaultDisplay
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onResume() { super.onResume(); granted.value = hasCameraPermission(); ctrl.onResume() }
    override fun onPause() { ctrl.onPause(); super.onPause() }
    override fun onDestroy() { ctrl.release(); super.onDestroy() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val vol = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        if (vol && granted.value && ctrl.settings.volumeShutter) {
            if (event.repeatCount == 0) ctrl.shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun Root(ctrl: CameraController, granted: MutableState<Boolean>) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted.value = res[Manifest.permission.CAMERA] == true
    }
    val perms = remember {
        if (Build.VERSION.SDK_INT < 29) arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { if (!granted.value) launcher.launch(perms) }

    var player by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player) Box(Modifier.fillMaxSize().systemBarsPadding()) { PlayerScreen(onClose = { player = false }) }
        else if (granted.value) CameraScreen(ctrl, onOpenPlayer = { player = true })
        else Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📷", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text("Photo Pro a besoin de l'appareil photo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Les photos sont enregistrées dans Images/PhotoPro.", color = Dim)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { launcher.launch(perms) }) { Text("Autoriser", color = Color.Black) }
        }
    }
}

private enum class Param(val label: String) {
    ZOOM("Zoom"), EV("Expo"), ISO("ISO"), SPEED("Vitesse"), FOCUS("Mise au point"), WB("Balance blancs"), FX("Effet")
}

@Composable
private fun CameraScreen(ctrl: CameraController, onOpenPlayer: () -> Unit) {
    val s = ctrl.settings
    val caps = ctrl.caps
    val context = androidx.compose.ui.platform.LocalContext.current
    var param by remember { mutableStateOf(Param.ZOOM) }
    var showSettings by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(true) }

    var textureRef by remember { mutableStateOf<TextureView?>(null) }
    val analyzer = remember { SceneAnalyzer() }
    DisposableEffect(Unit) { onDispose { analyzer.close() } }
    val wantScene = s.sceneAnalysis && !s.pro
    LaunchedEffect(wantScene, s.qr, s.histogram, textureRef) {
        val tv = textureRef ?: return@LaunchedEffect
        if (!wantScene) ctrl.clearScene()
        if (!wantScene && !s.qr && !s.histogram) return@LaunchedEffect
        while (true) {
            delay(if (s.histogram) 600 else 1000)
            if (!tv.isAvailable || tv.width == 0) continue
            val w = if (s.qr) 720 else 256
            val h = (w.toFloat() * tv.height / tv.width).roundToInt().coerceAtLeast(1)
            val bmp = runCatching { tv.getBitmap(w, h) }.getOrNull() ?: continue
            if (s.histogram) ctrl.computeHistogram(bmp)
            analyzer.analyze(
                bmp, labels = wantScene, barcodes = s.qr,
                onLabels = { ctrl.onSceneLabels(it) },
                onBarcode = { text, url -> if (text != null) { ctrl.qrText = text; ctrl.qrIsUrl = url } }
            )
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ctrl.onResume() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val msg = ctrl.message
    LaunchedEffect(msg) { if (msg != null) { delay(2500); ctrl.message = null } }
    val qr = ctrl.qrText
    LaunchedEffect(qr) { if (qr != null) { delay(8000); ctrl.qrText = null } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------- Aperçu plein écran ----------
        val ratio = caps?.let { it.previewSize.height.toFloat() / it.previewSize.width } ?: 0.75f
        val screenW = maxWidth; val screenH = maxHeight
        val fill = s.fillPreview
        val (pw, ph) = if ((screenW / screenH > ratio) == fill) Pair(screenW, screenW / ratio) else Pair(screenH * ratio, screenH)
        Box(
            Modifier.requiredSize(pw, ph).align(if (fill) Alignment.Center else Alignment.TopCenter)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o -> ctrl.tapToFocus(o.x / size.width, o.y / size.height) },
                        onLongPress = { o -> ctrl.lockAt(o.x / size.width, o.y / size.height) },
                        onDoubleTap = { ctrl.switchCamera() }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val c = ctrl.caps ?: return@detectTransformGestures
                        val cur = ctrl.settings
                        val z = (cur.zoom * zoom).coerceIn(c.zoomMin, c.zoomMax)
                        if (abs(z - cur.zoom) > 0.005f) ctrl.update(cur.copy(zoom = z))
                    }
                }
        ) {
            AndroidView(
                factory = { c ->
                    TextureView(c).also { textureRef = it }.apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = ctrl.attachSurface(st)
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { ctrl.detachSurface(); return true }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Overlay(ctrl)
        }

        // ---------- Barre du haut ----------
        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                .windowInsetsPadding(WindowInsets.displayCutout).padding(top = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopButton("⚡ ${s.flash.label}", enabled = caps?.hasFlash == true, active = s.flash != FlashMode.OFF) {
                    val next = FlashMode.entries[(s.flash.ordinal + 1) % FlashMode.entries.size]
                    ctrl.update(s.copy(flash = next))
                }
                TopButton(if (s.timer == 0) "⏱ Off" else "⏱ ${s.timer}s", enabled = !s.video, active = s.timer > 0) {
                    val next = when (s.timer) { 0 -> 2; 2 -> 5; 5 -> 10; else -> 0 }
                    ctrl.update(s.copy(timer = next))
                }
                if (s.video) TopButton(if (s.videoRes == 2160) "4K" else "${s.videoRes}p", active = false, enabled = !ctrl.recording) {
                    val opts = caps?.videoHeights?.ifEmpty { null } ?: listOf(1080)
                    val i = opts.indexOf(s.videoRes)
                    ctrl.update(s.copy(videoRes = opts[(i + 1) % opts.size]))
                } else TopButton(if (s.wide) "16:9" else "4:3", active = false) { ctrl.update(s.copy(wide = !s.wide)) }
                TopButton(if (fill) "⛶ Plein" else "▭ Cadre", active = false) { ctrl.update(s.copy(fillPreview = !fill)) }
                TopButton("▶", active = false, enabled = !ctrl.recording) { onOpenPlayer() }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Réglages", tint = Color.White)
                }
            }
            val info = buildString {
                if (caps != null) append(caps.label)
                if (caps?.aperture != null) append(" · f/${fmt1(caps.aperture)}")
                append("   ")
                if (s.video) append("${if (s.videoRes == 2160) "4K" else "${s.videoRes}p"} · ${if (s.fps60 && caps?.fps60 != null) 60 else 30} i/s")
                else if (s.pro) append("ISO ${s.iso} · ${formatShutter(s.exposureNs)}")
                else if (ctrl.live.iso > 0) append("ISO ${ctrl.live.iso} · ${formatShutter(ctrl.live.expNs)}")
            }
            Text(info, color = Color(0xDDFFFFFF), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (wantScene) {
                        val sc = ctrl.scene
                        Text(
                            "${sc.emoji} ${sc.label}" + if (s.sceneAuto && sc != Scene.STANDARD) " · optimisé" else "",
                            color = if (s.sceneAuto && sc != Scene.STANDARD) Accent else Color.White,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x99000000))
                                .clickable {
                                    ctrl.update(s.copy(sceneAuto = !s.sceneAuto))
                                    ctrl.message = if (!s.sceneAuto) "Optimisation automatique activée" else "Optimisation automatique désactivée"
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                        if (ctrl.sceneLabels.isNotEmpty()) Text(
                            ctrl.sceneLabels.joinToString(" · ") { (t, c) -> "${SceneAnalyzer.translate(t)} ${(c * 100).roundToInt()} %" },
                            color = Color(0xCCFFFFFF), fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp).background(Color(0x66000000), RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (ctrl.locked) Text(
                        "🔒 AE/AF verrouillé", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000))
                            .clickable { ctrl.resetFocus() }.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                val hist = ctrl.histogram
                if (s.histogram && hist != null) Histogram(hist)
            }
        }

        // ---------- Messages ----------
        if (ctrl.recording) {
            val t = ctrl.recSeconds
            Text(
                (if (ctrl.recPaused) "❚❚ " else "● ") + "%02d:%02d".format(t / 60, t % 60),
                color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).offset(y = (-160).dp)
                    .background(if (ctrl.recPaused) Color(0xCC555555) else Color(0xFFE53935), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        if (ctrl.countdown > 0) {
            Text("${ctrl.countdown}", color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        val m = ctrl.message
        if (m != null) {
            Text(
                m, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center).offset(y = (-110).dp)
                    .background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        // ---------- Commandes du bas ----------
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000), Color(0xEE000000))))
                .navigationBarsPadding().padding(top = 24.dp)
        ) {
            if (qr != null) Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xEE16181D)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("▦ QR  ", color = Accent, fontWeight = FontWeight.Bold)
                Text(qr, color = Color.White, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f))
                if (ctrl.qrIsUrl) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qr)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    ctrl.qrText = null
                }) { Text("Ouvrir") }
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("QR", qr))
                    ctrl.message = "Copié"; ctrl.qrText = null
                }) { Text("Copier") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    "x${fmt1(s.zoom)}", color = Accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Objectifs
            if (ctrl.lenses.size > 1) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                ctrl.lenses.forEach { l -> Chip(l.chip, caps?.id == l.id) { ctrl.selectLens(l.id) } }
            }

            if (showPanel) {
                val params = Param.entries.filter {
                    when (it) {
                        Param.EV -> !s.pro || s.video
                        Param.ISO, Param.SPEED -> s.pro && !s.video
                        Param.FOCUS -> (caps?.minFocus ?: 0f) > 0f
                        Param.FX -> (caps?.effects?.size ?: 0) > 1
                        else -> true
                    }
                }
                if (param !in params) param = params.first()
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    params.forEach { p -> Chip(p.label, p == param) { param = p } }
                }
                Box(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 12.dp)) {
                    if (caps != null) ParamPanel(param, ctrl, s, caps)
                }
            }

            // Modes
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ModeTab("AUTO", !s.pro && !s.video) {
                    if (!ctrl.recording) { ctrl.update(s.copy(pro = false, video = false)); if (param == Param.ISO || param == Param.SPEED) param = Param.EV }
                }
                Spacer(Modifier.width(14.dp))
                ModeTab("PRO", s.pro && !s.video) {
                    if (ctrl.recording) Unit
                    else if (caps?.manualSensor == true) { ctrl.update(s.copy(pro = true, video = false)); param = Param.ISO }
                    else ctrl.message = "Réglages manuels ISO/vitesse non pris en charge par cette caméra"
                }
                Spacer(Modifier.width(14.dp))
                ModeTab("VIDÉO", s.video) {
                    if (!ctrl.recording) { ctrl.update(s.copy(video = true, pro = false)); if (param == Param.ISO || param == Param.SPEED) param = Param.EV }
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    if (showPanel) "▾ Réglages" else "▴ Réglages", color = Dim, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showPanel = !showPanel }.padding(6.dp)
                )
            }

            // Déclencheur
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ctrl.recording) {
                    RoundButton(if (ctrl.recPaused) "▶" else "❚❚") { ctrl.togglePause() }
                } else {
                    val thumb = ctrl.lastThumb
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
                            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(12.dp))
                            .clickable {
                                val uri = ctrl.lastUri
                                if (uri != null) {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (ctrl.lastIsVideo) "video/mp4" else "image/jpeg")
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } catch (_: ActivityNotFoundException) { ctrl.message = "Aucune galerie trouvée" }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumb != null) Image(thumb.asImageBitmap(), "Dernière photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text("🖼", fontSize = 22.sp)
                    }
                }

                Box(
                    Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                        .pointerInput(s.video) {
                            detectTapGestures(
                                onPress = { tryAwaitRelease(); ctrl.stopBurst() },
                                onLongPress = { if (!s.video) ctrl.startBurst() },
                                onTap = { ctrl.shoot() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val red = Color(0xFFE53935)
                    if (s.video && ctrl.recording) Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(red))
                    else Box(
                        Modifier.fillMaxSize().padding(7.dp).clip(CircleShape)
                            .background(if (ctrl.busy) Dim else if (s.video) red else if (s.pro) Accent else Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        if (ctrl.burstCount > 0 && ctrl.busy && !s.video) Text("${ctrl.burstCount}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                }

                if (ctrl.recording) {
                    RoundButton("📸") { textureRef?.let { tv -> runCatching { tv.bitmap }.getOrNull()?.let { ctrl.saveSnapshot(it) } } }
                } else Box(
                    Modifier.size(54.dp).clip(CircleShape).background(Panel).clickable { ctrl.switchCamera() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Changer de caméra", tint = Color.White)
                }
            }
        }
    }

    if (showSettings) SettingsDialog(ctrl, caps) { showSettings = false }
}

@Composable
private fun RoundButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(CircleShape).background(Panel).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 20.sp) }
}

@Composable
private fun Histogram(bins: FloatArray) {
    Canvas(
        Modifier.size(width = 112.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x88000000))
    ) {
        val bw = size.width / bins.size
        bins.forEachIndexed { i, v ->
            val h = v * size.height
            drawRect(Color(0xCCFFFFFF), topLeft = Offset(i * bw, size.height - h), size = androidx.compose.ui.geometry.Size(bw, h))
        }
    }
}

@Composable
private fun Overlay(ctrl: CameraController) {
    val s = ctrl.settings
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(ctrl.flashScreen) {
        if (ctrl.flashScreen > 0) { flashAlpha.snapTo(0.8f); flashAlpha.animateTo(0f, tween(250)) }
    }
    val fp = ctrl.focusPoint
    val roll = ctrl.rollDeg
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (s.level && abs(roll) < 30f) {
            val ok = abs(roll) < 1.0f
            val col = if (ok) Color(0xFF2ECC71) else Color(0xCCFFFFFF)
            val len = w * 0.22f
            val rad = Math.toRadians(-roll.toDouble())
            val dx = (kotlin.math.cos(rad) * len).toFloat(); val dy = (kotlin.math.sin(rad) * len).toFloat()
            val cx = w / 2f; val cy = h / 2f
            drawLine(col, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), 3f)
            drawLine(Color(0x55FFFFFF), Offset(cx - len * 1.3f, cy), Offset(cx - len * 1.1f, cy), 3f)
            drawLine(Color(0x55FFFFFF), Offset(cx + len * 1.1f, cy), Offset(cx + len * 1.3f, cy), 3f)
        }
        if (s.faces) ctrl.faces.forEach { f ->
            drawRoundRect(
                Color(0xFFFFFFFF), topLeft = Offset(f.left * w, f.top * h),
                size = androidx.compose.ui.geometry.Size((f.right - f.left) * w, (f.bottom - f.top) * h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f), style = Stroke(3f)
            )
        }
        if (fp != null) {
            drawCircle(if (ctrl.locked) Color(0xFFFF9800) else Accent, radius = 46f, center = Offset(fp.first * w, fp.second * h), style = Stroke(4f))
        }
        if (flashAlpha.value > 0f) drawRect(Color.White.copy(alpha = flashAlpha.value))
    }
}

@Composable
private fun ParamPanel(param: Param, ctrl: CameraController, s: CamSettings, c: CamCaps) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        when (param) {
            Param.ZOOM -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val quick = (listOf(c.zoomMin) + listOf(1f, 2f, 3f, 5f, 10f)).distinct().filter { it in c.zoomMin..c.zoomMax }
                    quick.forEach { z -> Chip("x${fmt1(z)}", abs(s.zoom - z) < 0.05f) { ctrl.update(s.copy(zoom = z)) } }
                }
                if (c.zoomMax > c.zoomMin) Slider(
                    value = s.zoom, valueRange = c.zoomMin..c.zoomMax,
                    onValueChange = { ctrl.update(ctrl.settings.copy(zoom = it)) }
                )
            }
            Param.EV -> {
                val evText = "%+.1f EV".format(Locale.FRANCE, s.ev * c.evStep)
                LabelRow("Compensation d'exposition", evText) { ctrl.update(s.copy(ev = 0)) }
                if (c.evRange.upper > c.evRange.lower) Slider(
                    value = s.ev.toFloat(),
                    valueRange = c.evRange.lower.toFloat()..c.evRange.upper.toFloat(),
                    steps = max(0, c.evRange.upper - c.evRange.lower - 1),
                    onValueChange = { ctrl.update(ctrl.settings.copy(ev = it.roundToInt())) }
                ) else Text("Non disponible", color = Dim)
            }
            Param.ISO -> {
                val stops = isoStops(c)
                val idx = stops.indices.minByOrNull { abs(stops[it] - s.iso) } ?: 0
                LabelRow("Sensibilité", "ISO ${s.iso}", null)
                StopSlider(idx, stops.size) { i -> ctrl.update(ctrl.settings.copy(iso = stops[i])) }
            }
            Param.SPEED -> {
                val stops = shutterStops(c)
                val idx = stops.indices.minByOrNull { abs(stops[it] - s.exposureNs) } ?: 0
                LabelRow("Vitesse d'obturation", formatShutter(s.exposureNs), null)
                StopSlider(idx, stops.size) { i -> ctrl.update(ctrl.settings.copy(exposureNs = stops[i])) }
            }
            Param.FOCUS -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("AF auto", !s.manualFocus) { ctrl.update(s.copy(manualFocus = false)) }
                    Chip("Manuel", s.manualFocus) { ctrl.resetFocus(); ctrl.update(s.copy(manualFocus = true)) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (s.manualFocus) focusLabel(s.focus) else "Touchez l'image pour viser",
                        color = if (s.manualFocus) Accent else Dim, fontSize = 13.sp
                    )
                }
                if (s.manualFocus) Slider(
                    value = s.focus, valueRange = 0f..c.minFocus,
                    onValueChange = { ctrl.update(ctrl.settings.copy(focus = it)) }
                )
            }
            Param.WB -> Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                c.awbModes.filter { it != CameraMetadata.CONTROL_AWB_MODE_OFF }.forEach { m ->
                    Chip(awbName(m), s.awb == m) { ctrl.update(s.copy(awb = m)) }
                }
            }
            Param.FX -> Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                c.effects.forEach { e -> Chip(effectName(e), s.effect == e) { ctrl.update(s.copy(effect = e)) } }
            }
        }
    }
}

@Composable
private fun StopSlider(index: Int, count: Int, onIndex: (Int) -> Unit) {
    if (count < 2) { Text("Valeur fixe", color = Dim); return }
    Slider(
        value = index.toFloat(),
        valueRange = 0f..(count - 1).toFloat(),
        steps = max(0, count - 2),
        onValueChange = { v -> val i = v.roundToInt().coerceIn(0, count - 1); if (i != index) onIndex(i) }
    )
}

@Composable
private fun LabelRow(title: String, value: String, onReset: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Dim, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = Accent, fontWeight = FontWeight.Bold)
        if (onReset != null) {
            Spacer(Modifier.width(8.dp))
            Text("0", color = Color.White, modifier = Modifier.clip(CircleShape).background(Panel)
                .clickable { onReset() }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun SettingsDialog(ctrl: CameraController, caps: CamCaps?, onClose: () -> Unit) {
    val s = ctrl.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0F)).systemBarsPadding()) {
            // En-tête
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("←", color = Color.White, fontSize = 22.sp) }
                Text("Réglages", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Section("📷", "Photo") {
                    ChoiceRow("Qualité", "Plus la qualité est haute, plus le fichier est lourd",
                        listOf("Max" to 100, "Haute" to 95, "Moyenne" to 85, "Légère" to 70), s.quality) { ctrl.update(s.copy(quality = it)) }
                    ChoiceRow("Format", caps?.let { "Photo : ${"%.1f".format(Locale.FRANCE, it.jpegSize.width * it.jpegSize.height / 1e6)} Mpx" },
                        listOf("4:3" to false, "16:9" to true), s.wide) { ctrl.update(s.copy(wide = it)) }
                    ToggleRow("Son du déclencheur", null, s.sound) { ctrl.update(s.copy(sound = it)) }
                    ToggleRow("Boutons volume pour déclencher", "Volume + ou − prend la photo / lance la vidéo", s.volumeShutter) { ctrl.update(s.copy(volumeShutter = it)) }
                }
                Section("🎬", "Vidéo") {
                    val heights = caps?.videoHeights?.ifEmpty { null } ?: listOf(1080)
                    ChoiceRow("Résolution", null, heights.map { (if (it == 2160) "4K" else "${it}p") to it }, s.videoRes) { ctrl.update(s.copy(videoRes = it)) }
                    if (caps?.fps60 != null) ChoiceRow("Images par seconde", "60 i/s = mouvements plus fluides",
                        listOf("30" to false, "60" to true), s.fps60) { ctrl.update(s.copy(fps60 = it)) }
                    ToggleRow("Enregistrer le son", null, s.videoAudio) { ctrl.update(s.copy(videoAudio = it)) }
                    if (caps?.ois == true || caps?.videoStab == true)
                        ToggleRow("Stabilisation", "Réduit les tremblements", s.stabilization) { ctrl.update(s.copy(stabilization = it)) }
                }
                Section("🧭", "Affichage") {
                    ToggleRow("Aperçu plein écran", "Désactivé : l'image entière de la photo est visible", s.fillPreview) { ctrl.update(s.copy(fillPreview = it)) }
                    ToggleRow("Niveau (horizon)", "Ligne verte quand le téléphone est droit", s.level) { ctrl.update(s.copy(level = it)) }
                    ToggleRow("Histogramme", "Répartition des zones sombres / claires", s.histogram) { ctrl.update(s.copy(histogram = it)) }
                    if ((caps?.faceMode ?: 0) != 0)
                        ToggleRow("Détection des visages", "Cadre les visages et fait la mise au point dessus", s.faces) { ctrl.update(s.copy(faces = it)) }
                }
                Section("✨", "Intelligence artificielle") {
                    ToggleRow("Reconnaître la scène", "Nuit, portrait, paysage, nourriture, écran… (sur le téléphone, sans internet)", s.sceneAnalysis) { ctrl.update(s.copy(sceneAnalysis = it)) }
                    if (s.sceneAnalysis)
                        ToggleRow("Ajuster les réglages tout seul", "Adapte l'exposition et le mode selon la scène", s.sceneAuto) { ctrl.update(s.copy(sceneAuto = it)) }
                    ToggleRow("Lire les QR codes", "Liens, Wi-Fi, texte", s.qr) { ctrl.update(s.copy(qr = it)) }
                }
                Section("🔭", "Objectifs (${ctrl.lenses.size})") {
                    ctrl.lenses.forEach { l ->
                        val parts = listOfNotNull(
                            l.eqFocal?.let { "${it.roundToInt()} mm" },
                            l.aperture?.let { "f/${fmt1(it)}" },
                            if (l.megapixels > 0f) "${fmt1(l.megapixels)} Mpx" else null,
                            if (l.minFocus > 0f) "autofocus" else "focus fixe",
                            if (l.ois) "stabilisé" else null,
                        )
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (caps?.id == l.id) Color(0x33FFC83D) else Color.Transparent)
                                .clickable { ctrl.selectLens(l.id) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(l.chip, color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(86.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.kind + if (l.hidden) " · caché" else "", color = Color.White, fontSize = 15.sp)
                                Text(parts.joinToString(" · "), color = Dim, fontSize = 12.sp)
                            }
                            if (caps?.id == l.id) Text("✓", color = Accent, fontSize = 18.sp)
                        }
                    }
                    Text("Touchez un objectif pour l'utiliser.", color = Dim, fontSize = 12.sp)
                }
                Section("📌", "Appli par défaut") {
                    Text(
                        "Photo Pro s'ouvre quand une appli demande une photo ou une vidéo, et dans « Ouvrir avec » pour les vidéos et les liens YouTube. Choisissez Photo Pro puis « Toujours ».",
                        color = Dim, fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Applis par défaut") }
                        OutlinedButton(onClick = {
                            val i = if (Build.VERSION.SDK_INT >= 31)
                                Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:" + ctx.packageName))
                            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                            runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Liens YouTube") }
                    }
                }
                Section("💡", "Gestes") {
                    listOf(
                        "Toucher l'image" to "mise au point à cet endroit",
                        "Appui long sur l'image" to "verrouiller expo + mise au point",
                        "Double toucher" to "caméra avant / arrière",
                        "Pincer" to "zoomer",
                        "Appui long sur le déclencheur" to "rafale (30 photos)",
                    ).forEach { (g, d) ->
                        Row { Text(g, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(d, color = Dim, fontSize = 13.sp) }
                    }
                }
                OutlinedButton(
                    onClick = { ctrl.resetFocus(); ctrl.update(CamSettings(wide = s.wide, video = s.video, videoRes = s.videoRes)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Réinitialiser les réglages photo") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(icon: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("$icon  $title", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!value) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Dim, fontSize = 12.sp)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Accent)
        )
    }
}

@Composable
private fun <T> ChoiceRow(title: String, subtitle: String?, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(title, color = Color.White, fontSize = 15.sp)
        if (subtitle != null) Text(subtitle, color = Dim, fontSize = 12.sp)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0C0F)).padding(3.dp)
        ) {
            options.forEach { (label, v) ->
                val on = v == selected
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Accent else Color.Transparent)
                        .clickable { onSelect(v) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (on) Color.Black else Color.White, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun TopButton(text: String, enabled: Boolean = true, active: Boolean, onClick: () -> Unit) {
    Text(
        text, maxLines = 1,
        color = when { !enabled -> Color(0x55FFFFFF); active -> Accent; else -> Color.White },
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Panel)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun ModeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        color = if (selected) Accent else Dim,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 4.dp)
    )
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Accent else Panel,
        border = if (selected) null else BorderStroke(1.dp, Color(0x33FFFFFF))
    ) {
        Text(
            text, fontSize = 13.sp,
            color = if (selected) Color.Black else Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// ---------------------------------------------------------------------
// Valeurs et libellés

private fun fmt1(v: Float) = "%.1f".format(Locale.FRANCE, v)

private fun formatShutter(ns: Long): String {
    if (ns <= 0) return "—"
    val sec = ns / 1e9
    return if (sec >= 0.95) "${"%.0f".format(Locale.FRANCE, sec)}\"" else "1/${(1.0 / sec).roundToInt()}"
}

private fun focusLabel(diopters: Float): String =
    if (diopters <= 0.01f) "∞ (infini)" else {
        val m = 1f / diopters
        if (m < 1f) "${(m * 100).roundToInt()} cm" else "${fmt1(m)} m"
    }

private fun isoStops(c: CamCaps): List<Int> {
    val base = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 25600, 51200)
    return (base.filter { it in c.isoRange.lower..c.isoRange.upper } + c.isoRange.lower + c.isoRange.upper)
        .distinct().sorted()
}

private fun shutterStops(c: CamCaps): List<Long> {
    val fractions = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250,
        200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3, 2)
        .map { 1_000_000_000L / it }
    val seconds = listOf(1L, 2L, 4L, 8L, 15L, 30L).map { it * 1_000_000_000L }
    return (fractions + seconds).filter { it in c.expRange.lower..c.expRange.upper }
        .plus(c.expRange.upper).distinct().sorted()
}

private fun awbName(m: Int) = when (m) {
    CameraMetadata.CONTROL_AWB_MODE_AUTO -> "Auto"
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "💡 Ampoule"
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Néon"
    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Néon chaud"
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "☀ Soleil"
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "☁ Nuageux"
    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "Crépuscule"
    CameraMetadata.CONTROL_AWB_MODE_SHADE -> "Ombre"
    else -> "Mode $m"
}

private fun effectName(e: Int) = when (e) {
    CameraMetadata.CONTROL_EFFECT_MODE_OFF -> "Aucun"
    CameraMetadata.CONTROL_EFFECT_MODE_MONO -> "Noir & blanc"
    CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE -> "Négatif"
    CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE -> "Solarisation"
    CameraMetadata.CONTROL_EFFECT_MODE_SEPIA -> "Sépia"
    CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE -> "Postérisation"
    CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD -> "Tableau blanc"
    CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD -> "Tableau noir"
    CameraMetadata.CONTROL_EFFECT_MODE_AQUA -> "Aqua"
    else -> "Effet $e"
}
