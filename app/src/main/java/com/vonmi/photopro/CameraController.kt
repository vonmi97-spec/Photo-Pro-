package com.vonmi.photopro

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ExifInterface
import android.media.ImageReader
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class FlashMode(val label: String) { OFF("Off"), AUTO("Auto"), ON("On"), TORCH("Torche") }

data class CamSettings(
    val pro: Boolean = false,
    val iso: Int = 200,
    val exposureNs: Long = 8_000_000L,
    val manualFocus: Boolean = false,
    val focus: Float = 0f,
    val awb: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val ev: Int = 0,
    val zoom: Float = 1f,
    val flash: FlashMode = FlashMode.OFF,
    val effect: Int = CameraMetadata.CONTROL_EFFECT_MODE_OFF,
    val grid: Boolean = true,
    val timer: Int = 0,
    val quality: Int = 95,
    val wide: Boolean = false,
    val stabilization: Boolean = true,
    val sound: Boolean = true,
    val volumeShutter: Boolean = true,
    val level: Boolean = true,
)

data class CamCaps(
    val id: String,
    val front: Boolean,
    val label: String,
    val sensorOrientation: Int,
    val manualSensor: Boolean,
    val isoRange: Range<Int>,
    val expRange: Range<Long>,
    val minFocus: Float,
    val awbModes: List<Int>,
    val afModes: List<Int>,
    val effects: List<Int>,
    val evRange: Range<Int>,
    val evStep: Float,
    val zoomMin: Float,
    val zoomMax: Float,
    val useZoomRatio: Boolean,
    val hasFlash: Boolean,
    val ois: Boolean,
    val activeArray: Rect,
    val maxAfRegions: Int,
    val maxAeRegions: Int,
    val jpegSize: Size,
    val previewSize: Size,
    val aperture: Float?,
    val focal: Float?,
)

data class LiveInfo(val iso: Int = 0, val expNs: Long = 0, val focus: Float = 0f)

class CameraController(private val ctx: Context) {

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("photo", Context.MODE_PRIVATE)
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    // ---- état observé par l'interface ----
    var settings by mutableStateOf(loadSettings()); private set
    var caps by mutableStateOf<CamCaps?>(null); private set
    var live by mutableStateOf(LiveInfo()); private set
    var lastThumb by mutableStateOf<Bitmap?>(null); private set
    var lastUri by mutableStateOf<Uri?>(null); private set
    var countdown by mutableStateOf(0); private set
    var flashScreen by mutableStateOf(0); private set
    var message by mutableStateOf<String?>(null)
    var focusPoint by mutableStateOf<Pair<Float, Float>?>(null); private set
    var deviceRotation by mutableStateOf(0); private set
    var rollDeg by mutableStateOf(0f); private set
    var busy by mutableStateOf(false); private set

    @Volatile private var cfg: CamSettings = settings
    @Volatile private var cur: CamCaps? = null
    @Volatile private var tapRegion: MeteringRectangle? = null

    val cameraIds: List<String> = runCatching {
        manager.cameraIdList.filter { id ->
            val c = manager.getCameraCharacteristics(id)
            val cp = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
        }
    }.getOrDefault(emptyList())

    private var index = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var texture: SurfaceTexture? = null
    private var resumed = false
    private var opening = false
    private var lastLive = 0L
    private var pendingOrientation = 0

    private val orientationListener = object : OrientationEventListener(ctx) {
        override fun onOrientationChanged(o: Int) {
            if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
            val snapped = when (o) {
                in 45 until 135 -> 90
                in 135 until 225 -> 180
                in 225 until 315 -> 270
                else -> 0
            }
            if (snapped != deviceRotation) deviceRotation = snapped
            var r = o.toFloat()
            if (r > 180f) r -= 360f
            val roll = when (snapped) { 90 -> r - 90f; 270 -> r + 90f; 180 -> 0f; else -> r }
            rollDeg = roll
        }
    }

    // ------------------------------------------------------------------
    // Cycle de vie

    fun onResume() {
        resumed = true
        orientationListener.enable()
        openIfReady()
    }

    fun onPause() {
        resumed = false
        orientationListener.disable()
        bg.post { closeCamera() }
    }

    fun attachSurface(st: SurfaceTexture) {
        texture = st
        openIfReady()
    }

    fun detachSurface() {
        texture = null
        bg.post { closeCamera() }
    }

    fun release() {
        bg.post {
            closeCamera()
            sound.release()
            thread.quitSafely()
        }
    }

    private fun openIfReady() {
        if (!resumed || texture == null || cameraIds.isEmpty()) return
        bg.post { if (device == null && !opening) openCamera() }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = cameraIds.getOrNull(index) ?: return
        val c = try { buildCaps(id) } catch (e: Exception) { post("Caméra indisponible"); return }
        cur = c
        main.post { caps = c; clampSettingsTo(c) }
        opening = true
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    opening = false
                    if (!resumed || texture == null) { d.close(); return }
                    device = d
                    createSession()
                }
                override fun onDisconnected(d: CameraDevice) {
                    opening = false; d.close(); if (device == d) device = null
                }
                override fun onError(d: CameraDevice, error: Int) {
                    opening = false; d.close(); if (device == d) device = null
                    post("Erreur caméra ($error)")
                }
            }, bg)
        } catch (e: Exception) {
            opening = false
            post("Impossible d'ouvrir la caméra : ${e.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        previewSurface?.release()
        previewSurface = null
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val d = device ?: return
        val c = cur ?: return
        val st = texture ?: return
        st.setDefaultBufferSize(c.previewSize.width, c.previewSize.height)
        val ps = Surface(st)
        previewSurface = ps
        val r = ImageReader.newInstance(c.jpegSize.width, c.jpegSize.height, ImageFormat.JPEG, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                saveJpeg(bytes)
            } catch (e: Exception) {
                post("Échec de l'enregistrement")
            } finally {
                img.close()
                main.post { busy = false }
            }
        }, bg)
        reader = r
        try {
            d.createCaptureSession(listOf(ps, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device == null) { s.close(); return }
                    session = s
                    updatePreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    post("Configuration de la caméra impossible")
                }
            }, bg)
        } catch (e: Exception) {
            post("Erreur session : ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Capacités du capteur

    private fun buildCaps(id: String): CamCaps {
        val ch = manager.getCameraCharacteristics(id)
        val capsList = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manual = capsList.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 1600)
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 250_000_000L)
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awb = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        val af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val fx = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)?.toList() ?: listOf(CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        val evR = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evS = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val aperture = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()

        var zMin = 1f
        var zMax = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        var useRatio = false
        if (Build.VERSION.SDK_INT >= 30) {
            val zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zr != null) { zMin = zr.lower; zMax = zr.upper; useRatio = true }
        }

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val ratio = if (cfg.wide) 16f / 9f else 4f / 3f
        fun matches(s: Size) = abs(s.width.toFloat() / s.height - ratio) < 0.03f
        val jpegs = map.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
        val jpeg = jpegs.filter(::matches).maxByOrNull { it.width.toLong() * it.height }
            ?: jpegs.maxBy { it.width.toLong() * it.height }
        val prevs = map.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1280, 720))
        val prev = prevs.filter { matches(it) && it.width <= 1920 && it.height <= 1440 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: prevs.minBy { abs(it.width.toFloat() / it.height - ratio) }

        val dir = if (front) "Avant" else "Arrière"
        val lens = if (focal != null) " · ${"%.1f".format(Locale.FRANCE, focal)} mm" else ""
        return CamCaps(
            id, front, "$dir$lens", orient, manual, iso, exp, minFocus, awb, af, fx,
            evR, evS, zMin, zMax, useRatio, flash, ois, active, maxAf, maxAe, jpeg, prev, aperture, focal
        )
    }

    private fun clampSettingsTo(c: CamCaps) {
        val s = settings
        update(
            s.copy(
                iso = s.iso.coerceIn(c.isoRange.lower, c.isoRange.upper),
                exposureNs = s.exposureNs.coerceIn(c.expRange.lower, c.expRange.upper),
                focus = s.focus.coerceIn(0f, max(c.minFocus, 0f)),
                awb = if (c.awbModes.contains(s.awb)) s.awb else CameraMetadata.CONTROL_AWB_MODE_AUTO,
                effect = if (c.effects.contains(s.effect)) s.effect else CameraMetadata.CONTROL_EFFECT_MODE_OFF,
                ev = s.ev.coerceIn(c.evRange.lower, c.evRange.upper),
                zoom = 1f.coerceIn(c.zoomMin, c.zoomMax),
                pro = s.pro && c.manualSensor,
                manualFocus = s.manualFocus && c.minFocus > 0f,
                flash = if (c.hasFlash) s.flash else FlashMode.OFF,
            ),
            refresh = false
        )
    }

    // ------------------------------------------------------------------
    // Réglages

    fun update(newSettings: CamSettings, refresh: Boolean = true) {
        val old = settings
        settings = newSettings
        cfg = newSettings
        saveSettings(newSettings)
        if (old.wide != newSettings.wide) {
            bg.post { closeCamera(); if (resumed && texture != null) openCamera() }
            return
        }
        if (refresh) bg.post { updatePreview() }
    }

    fun switchCamera() {
        if (cameraIds.size < 2) return
        index = (index + 1) % cameraIds.size
        tapRegion = null
        focusPoint = null
        bg.post {
            closeCamera()
            if (resumed && texture != null) openCamera()
        }
    }

    fun resetFocus() {
        tapRegion = null
        focusPoint = null
        bg.post { updatePreview() }
    }

    /** Appui sur l'aperçu : mise au point + mesure à cet endroit (x, y entre 0 et 1). */
    fun tapToFocus(x: Float, y: Float) {
        val c = cur ?: return
        if (settings.manualFocus) return
        val (sx, sy) = when (c.sensorOrientation) {
            90 -> if (c.front) Pair(y, x) else Pair(y, 1f - x)
            270 -> if (c.front) Pair(1f - y, 1f - x) else Pair(1f - y, x)
            180 -> Pair(1f - x, 1f - y)
            else -> Pair(x, y)
        }
        val a = c.activeArray
        val half = (a.width() * 0.06f).toInt()
        val cx = (a.left + sx * a.width()).toInt()
        val cy = (a.top + sy * a.height()).toInt()
        val rect = Rect(
            (cx - half).coerceAtLeast(a.left), (cy - half).coerceAtLeast(a.top),
            (cx + half).coerceAtMost(a.right - 1), (cy + half).coerceAtMost(a.bottom - 1)
        )
        tapRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        focusPoint = Pair(x, y)
        bg.post {
            val s = session ?: return@post
            val d = device ?: return@post
            val ps = previewSurface ?: return@post
            try {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(ps)
                apply(b, capture = false)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, bg)
                updatePreview()
            } catch (_: Exception) {}
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLive < 250) return
            lastLive = now
            val info = LiveInfo(
                res.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                res.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                res.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
            )
            main.post { live = info }
        }
    }

    private fun updatePreview() {
        val s = session ?: return
        val d = device ?: return
        val ps = previewSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            apply(b, capture = false)
            s.setRepeatingRequest(b.build(), previewCallback, bg)
        } catch (e: CameraAccessException) {
            post("Caméra occupée")
        } catch (_: IllegalStateException) {
        } catch (e: IllegalArgumentException) {
            post("Réglage refusé par la caméra")
        }
    }

    private fun apply(b: CaptureRequest.Builder, capture: Boolean) {
        val c = cur ?: return
        val s = cfg
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Exposition
        if (s.pro && c.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            var exp = s.exposureNs
            var iso = s.iso
            if (!capture && exp > PREVIEW_MAX_EXP) {
                // L'aperçu reste fluide : on raccourcit la pose et on compense en ISO
                val f = exp.toDouble() / PREVIEW_MAX_EXP
                exp = PREVIEW_MAX_EXP
                iso = (iso * f).roundToInt().coerceIn(c.isoRange.lower, c.isoRange.upper)
            }
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, exp)
            val fm = when {
                !c.hasFlash -> CameraMetadata.FLASH_MODE_OFF
                s.flash == FlashMode.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                capture && (s.flash == FlashMode.ON || s.flash == FlashMode.AUTO) -> CameraMetadata.FLASH_MODE_SINGLE
                else -> CameraMetadata.FLASH_MODE_OFF
            }
            b.set(CaptureRequest.FLASH_MODE, fm)
        } else {
            val ae = if (!c.hasFlash) CameraMetadata.CONTROL_AE_MODE_ON else when (s.flash) {
                FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, ae)
            b.set(
                CaptureRequest.FLASH_MODE,
                if (c.hasFlash && s.flash == FlashMode.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
            )
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.ev.coerceIn(c.evRange.lower, c.evRange.upper))
        }

        // Mise au point
        val region = tapRegion
        when {
            s.manualFocus && c.minFocus > 0f -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, s.focus)
            }
            region != null && c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                if (c.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        }
        if (region != null && c.maxAeRegions > 0 && !s.pro) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))

        // Couleur
        b.set(CaptureRequest.CONTROL_AWB_MODE, s.awb)
        b.set(CaptureRequest.CONTROL_EFFECT_MODE, s.effect)

        // Zoom
        if (c.useZoomRatio && Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoom.coerceIn(c.zoomMin, c.zoomMax))
        } else if (s.zoom > 1f) {
            val a = c.activeArray
            val z = s.zoom.coerceIn(1f, c.zoomMax)
            val w = (a.width() / z).toInt()
            val h = (a.height() / z).toInt()
            val l = a.left + (a.width() - w) / 2
            val t = a.top + (a.height() - h) / 2
            b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
        }

        // Stabilisation
        if (c.ois) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (s.stabilization) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        if (capture) {
            b.set(CaptureRequest.JPEG_QUALITY, s.quality.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(c))
            b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
        }
    }

    private fun jpegOrientation(c: CamCaps): Int {
        val dev = pendingOrientation
        return if (c.front) (c.sensorOrientation - dev + 360) % 360 else (c.sensorOrientation + dev) % 360
    }

    // ------------------------------------------------------------------
    // Prise de vue

    fun shoot() {
        if (busy || countdown > 0 || session == null) return
        val t = settings.timer
        if (t <= 0) { capture(); return }
        countdown = t
        fun tick() {
            if (countdown <= 1) { countdown = 0; capture(); return }
            countdown -= 1
            main.postDelayed({ tick() }, 1000)
        }
        main.postDelayed({ tick() }, 1000)
    }

    private fun capture() {
        busy = true
        pendingOrientation = deviceRotation
        val s = settings
        val c = caps
        val precapture = c != null && c.hasFlash && !s.pro && (s.flash == FlashMode.AUTO || s.flash == FlashMode.ON)
        bg.post {
            val ss = session; val d = device; val ps = previewSurface
            if (ss == null || d == null || ps == null) { main.post { busy = false }; return@post }
            if (precapture) {
                try {
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    b.addTarget(ps)
                    apply(b, capture = false)
                    b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    ss.capture(b.build(), null, bg)
                } catch (_: Exception) {}
                bg.postDelayed({ still() }, 700)
            } else still()
        }
    }

    private fun still() {
        val ss = session; val d = device; val r = reader; val ps = previewSurface
        if (ss == null || d == null || r == null || ps == null) { main.post { busy = false }; return }
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            b.addTarget(ps)
            apply(b, capture = true)
            ss.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, req: CaptureRequest, ts: Long, frame: Long) {
                    main.post {
                        if (cfg.sound) sound.play(MediaActionSound.SHUTTER_CLICK)
                        flashScreen++
                    }
                }
                override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                    post("La photo a échoué"); main.post { busy = false }
                }
            }, bg)
            val exp = cfg.exposureNs
            if (cfg.pro && exp > 1_000_000_000L) post("Pose longue : ne bougez pas…")
        } catch (e: Exception) {
            post("La photo a échoué : ${e.message}")
            main.post { busy = false }
        }
    }

    private fun saveJpeg(bytes: ByteArray) {
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.FRANCE).format(Date()) + ".jpg"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoPro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { post("Stockage indisponible"); return }
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        val thumb = makeThumb(bytes)
        main.post { lastUri = uri; if (thumb != null) lastThumb = thumb }
    }

    private fun makeThumb(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 200) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val deg = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (bmp != null && deg != 0f) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        } else bmp
    } catch (_: Exception) { null }

    private fun post(msg: String) { main.post { message = msg } }

    // ------------------------------------------------------------------
    // Sauvegarde des préférences

    private fun loadSettings(): CamSettings {
        val d = CamSettings()
        return CamSettings(
            pro = prefs.getBoolean("pro", d.pro),
            iso = prefs.getInt("iso", d.iso),
            exposureNs = prefs.getLong("exp", d.exposureNs),
            manualFocus = prefs.getBoolean("mf", d.manualFocus),
            focus = prefs.getFloat("focus", d.focus),
            awb = prefs.getInt("awb", d.awb),
            ev = prefs.getInt("ev", d.ev),
            flash = runCatching { FlashMode.valueOf(prefs.getString("flash", d.flash.name)!!) }.getOrDefault(d.flash),
            effect = prefs.getInt("effect", d.effect),
            grid = prefs.getBoolean("grid", d.grid),
            timer = prefs.getInt("timer", d.timer),
            quality = prefs.getInt("quality", d.quality),
            wide = prefs.getBoolean("wide", d.wide),
            stabilization = prefs.getBoolean("stab", d.stabilization),
            sound = prefs.getBoolean("sound", d.sound),
            volumeShutter = prefs.getBoolean("vol", d.volumeShutter),
            level = prefs.getBoolean("level", d.level),
        )
    }

    private fun saveSettings(s: CamSettings) {
        prefs.edit()
            .putBoolean("pro", s.pro).putInt("iso", s.iso).putLong("exp", s.exposureNs)
            .putBoolean("mf", s.manualFocus).putFloat("focus", s.focus).putInt("awb", s.awb)
            .putInt("ev", s.ev).putString("flash", s.flash.name).putInt("effect", s.effect)
            .putBoolean("grid", s.grid).putInt("timer", s.timer).putInt("quality", s.quality)
            .putBoolean("wide", s.wide).putBoolean("stab", s.stabilization).putBoolean("sound", s.sound)
            .putBoolean("vol", s.volumeShutter).putBoolean("level", s.level)
            .apply()
    }

    companion object {
        const val PREVIEW_MAX_EXP = 66_000_000L // 1/15 s
    }
}
