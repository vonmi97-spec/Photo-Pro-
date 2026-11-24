package com.vonmi.photopro

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
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
        ctrl = CameraController(applicationContext)
        granted.value = hasCameraPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Panel)) {
                Root(ctrl, granted)
            }
        }
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
        if (Build.VERSION.SDK_INT < 29) arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!granted.value) launcher.launch(perms) }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        if (granted.value) CameraScreen(ctrl)
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
private fun CameraScreen(ctrl: CameraController) {
    val s = ctrl.settings
    val caps = ctrl.caps
    val context = androidx.compose.ui.platform.LocalContext.current
    var param by remember { mutableStateOf(Param.ZOOM) }
    var showSettings by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ctrl.onResume() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val msg = ctrl.message
    LaunchedEffect(msg) { if (msg != null) { delay(2500); ctrl.message = null } }

    Column(Modifier.fillMaxSize()) {
        // ---------- Barre du haut ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopButton("⚡ ${s.flash.label}", enabled = caps?.hasFlash == true, active = s.flash != FlashMode.OFF) {
                val next = FlashMode.entries[(s.flash.ordinal + 1) % FlashMode.entries.size]
                ctrl.update(s.copy(flash = next))
            }
            TopButton(if (s.timer == 0) "⏱ Off" else "⏱ ${s.timer}s", active = s.timer > 0) {
                val next = when (s.timer) { 0 -> 3; 3 -> 10; else -> 0 }
                ctrl.update(s.copy(timer = next))
            }
            TopButton("# Grille", active = s.grid) { ctrl.update(s.copy(grid = !s.grid)) }
            TopButton(if (s.wide) "16:9" else "4:3", active = false) { ctrl.update(s.copy(wide = !s.wide)) }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Réglages", tint = Color.White)
            }
        }

        // ---------- Infos ----------
        val info = buildString {
            if (caps != null) append(caps.label)
            if (caps?.aperture != null) append(" · f/${fmt1(caps.aperture)}")
            append("   ")
            if (s.pro) append("ISO ${s.iso} · ${formatShutter(s.exposureNs)}")
            else if (ctrl.live.iso > 0) append("ISO ${ctrl.live.iso} · ${formatShutter(ctrl.live.expNs)}")
        }
        Text(info, color = Dim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))

        // ---------- Aperçu ----------
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = caps?.let { it.previewSize.height.toFloat() / it.previewSize.width } ?: 0.75f
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = false)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { o -> ctrl.tapToFocus(o.x / size.width, o.y / size.height) },
                            onLongPress = { ctrl.resetFocus(); ctrl.message = "Mise au point automatique" },
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
                        TextureView(c).apply {
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
            if (ctrl.countdown > 0) {
                Text("${ctrl.countdown}", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
            }
            val m = ctrl.message
            if (m != null) {
                Text(
                    m, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
            Text(
                "x${fmt1(s.zoom)}", color = Accent, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        // ---------- Mode Auto / Pro ----------
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            ModeTab("AUTO", !s.pro) { ctrl.update(s.copy(pro = false)); if (param == Param.ISO || param == Param.SPEED) param = Param.EV }
            Spacer(Modifier.width(24.dp))
            ModeTab("PRO", s.pro) {
                if (caps?.manualSensor == true) { ctrl.update(s.copy(pro = true)); param = Param.ISO }
                else ctrl.message = "Réglages manuels ISO/vitesse non pris en charge par cette caméra"
            }
        }

        // ---------- Choix du réglage ----------
        val params = Param.entries.filter {
            when (it) {
                Param.EV -> !s.pro
                Param.ISO, Param.SPEED -> s.pro
                Param.FOCUS -> (caps?.minFocus ?: 0f) > 0f
                Param.FX -> (caps?.effects?.size ?: 0) > 1
                else -> true
            }
        }
        if (param !in params) param = params.first()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            params.forEach { p -> Chip(p.label, p == param) { param = p } }
        }

        // ---------- Panneau du réglage ----------
        Box(Modifier.fillMaxWidth().height(92.dp).padding(horizontal = 12.dp)) {
            if (caps != null) ParamPanel(param, ctrl, s, caps)
        }

        // ---------- Déclencheur ----------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val thumb = ctrl.lastThumb
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
                    .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(12.dp))
                    .clickable {
                        val uri = ctrl.lastUri
                        if (uri != null) {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
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

            Box(
                Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp)
                    .clip(CircleShape).background(if (ctrl.busy) Dim else if (s.pro) Accent else Color.White)
                    .clickable { ctrl.shoot() }
            )

            Box(
                Modifier.size(54.dp).clip(CircleShape).background(Panel).clickable { ctrl.switchCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Changer de caméra", tint = Color.White)
            }
        }
    }

    if (showSettings) SettingsDialog(ctrl, caps) { showSettings = false }
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
        if (s.grid) {
            val c = Color(0x66FFFFFF)
            for (i in 1..2) {
                drawLine(c, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), 1.5f)
                drawLine(c, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), 1.5f)
            }
        }
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
        if (fp != null) {
            drawCircle(Accent, radius = 46f, center = Offset(fp.first * w, fp.second * h), style = Stroke(4f))
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
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } },
        title = { Text("Réglages") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Qualité JPEG", color = Dim, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    listOf(100 to "Max", 95 to "Haute", 85 to "Moyenne", 70 to "Légère").forEach { (q, n) ->
                        Chip(n, s.quality == q) { ctrl.update(s.copy(quality = q)) }
                    }
                }
                Text("Format", color = Dim, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    Chip("4:3 (pleine résolution)", !s.wide) { ctrl.update(s.copy(wide = false)) }
                    Chip("16:9", s.wide) { ctrl.update(s.copy(wide = true)) }
                }
                if (caps != null) Text(
                    "Photo : ${caps.jpegSize.width}×${caps.jpegSize.height} (${"%.1f".format(Locale.FRANCE, caps.jpegSize.width * caps.jpegSize.height / 1e6)} Mpx)",
                    color = Dim, fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow("Grille (règle des tiers)", s.grid) { ctrl.update(s.copy(grid = it)) }
                SwitchRow("Niveau / horizon", s.level) { ctrl.update(s.copy(level = it)) }
                if (caps?.ois == true) SwitchRow("Stabilisation optique", s.stabilization) { ctrl.update(s.copy(stabilization = it)) }
                SwitchRow("Son du déclencheur", s.sound) { ctrl.update(s.copy(sound = it)) }
                SwitchRow("Boutons volume = déclencher", s.volumeShutter) { ctrl.update(s.copy(volumeShutter = it)) }
                Spacer(Modifier.height(8.dp))
                Text("Astuces : touchez l'image pour faire la mise au point, appui long pour revenir en auto, double-touche pour changer de caméra, pincez pour zoomer.", color = Dim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    ctrl.resetFocus()
                    ctrl.update(CamSettings(wide = s.wide))
                }) { Text("Réinitialiser les réglages photo") }
            }
        }
    )
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
        text,
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
