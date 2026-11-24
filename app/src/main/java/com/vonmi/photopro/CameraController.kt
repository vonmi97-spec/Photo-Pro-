package com.vonmi.photopro

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
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
import kotlin.math.min
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
    val grid: Boolean = false,
    val timer: Int = 0,
    val quality: Int = 95,
    val wide: Boolean = false,
    val stabilization: Boolean = true,
    val sound: Boolean = true,
    val volumeShutter: Boolean = true,
    val level: Boolean = true,
    val video: Boolean = false,
    val videoRes: Int = 1080,
    val fps60: Boolean = false,
    val videoAudio: Boolean = true,
    val sceneAnalysis: Boolean = true,
    val sceneAuto: Boolean = true,
    val fillPreview: Boolean = true,
    val faces: Boolean = true,
    val qr: Boolean = true,
    val histogram: Boolean = false,
    val raw: Boolean = false,
    val watermark: Int = 0,
    val watermarkText: String = "",
    val gps: Boolean = false,
    val peaking: Boolean = true,
    val gestureShutter: Boolean = false,
    val voice: Boolean = false,
    /** Mode photo spécial : 0 aucun, 1 Nuit, 2 HDR, 3 Panorama. */
    val special: Int = 0,
    /** Type de vidéo : 0 normale, 1 ralenti, 2 timelapse. */
    val videoKind: Int = 0,
    val lapseMs: Int = 1000,
    val slowFps: Int = 120,
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
    val videoSize: Size,
    val videoHeights: List<Int>,
    val fps30: Range<Int>?,
    val fps60: Range<Int>?,
    val videoStab: Boolean,
    val sceneModes: List<Int>,
    val faceMode: Int,
    val rawSize: Size?,
    /** Ralenti « vrai » (haute vitesse) : taille + images/s. */
    val slowModes: List<Pair<Size, Int>>,
)

data class LensInfo(
    val id: String,
    val front: Boolean,
    val eqFocal: Float?,
    val megapixels: Float,
    val aperture: Float?,
    val minFocus: Float,
    val ois: Boolean,
    val manual: Boolean,
    val raw: Boolean,
    val hidden: Boolean,
    var factor: Float = 1f,
    var kind: String = "Principal",
) {
    val chip: String get() = when {
        front -> "🤳 Avant"
        kind == "Macro" -> "🌸 Macro"
        kind == "Profondeur" -> "Profondeur"
        else -> "×" + (if (factor < 1f) "%.1f" else "%.0f").format(Locale.FRANCE, factor)
    }
}

enum class Scene(val label: String, val emoji: String, val sceneMode: Int?) {
    STANDARD("Standard", "📷", null),
    NIGHT("Nuit", "🌙", CameraMetadata.CONTROL_SCENE_MODE_NIGHT),
    PORTRAIT("Portrait", "👤", CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT),
    LANDSCAPE("Paysage", "🏞", CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE),
    SUNSET("Coucher de soleil", "🌅", CameraMetadata.CONTROL_SCENE_MODE_SUNSET),
    BEACH("Plage", "🏖", CameraMetadata.CONTROL_SCENE_MODE_BEACH),
    SNOW("Neige", "❄", CameraMetadata.CONTROL_SCENE_MODE_SNOW),
    FOOD("Nourriture", "🍽", null),
    PET("Animal", "🐾", CameraMetadata.CONTROL_SCENE_MODE_ACTION),
    FLOWER("Fleurs / plantes", "🌸", null),
    SCREEN("Écran", "🖥", null),
    DOCUMENT("Texte / document", "📄", null),
    SPORT("Sport", "🏀", CameraMetadata.CONTROL_SCENE_MODE_SPORTS),
}

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
    var recording by mutableStateOf(false); private set
    var recSeconds by mutableStateOf(0); private set
    var lastIsVideo by mutableStateOf(false); private set
    var recPaused by mutableStateOf(false); private set
    var burstCount by mutableStateOf(0); private set
    var locked by mutableStateOf(false); private set
    var faces by mutableStateOf<List<android.graphics.RectF>>(emptyList()); private set
    var histogram by mutableStateOf<FloatArray?>(null); private set
    var qrText by mutableStateOf<String?>(null)
    var qrIsUrl by mutableStateOf(false)
    @Volatile private var burstActive = false
    @Volatile private var lockedFlag = false
    private var pausedAccum = 0L
    private var pausedAt = 0L
    private var lastFaces = 0L

    @Volatile private var cfg: CamSettings = settings
    @Volatile private var cur: CamCaps? = null
    @Volatile private var tapRegion: MeteringRectangle? = null

    /** Objectifs déclarés + objectifs « cachés » trouvés en sondant les identifiants 0 à 9. */
    val lenses: List<LensInfo> = detectLenses()
    val cameraIds: List<String> = lenses.map { it.id }

    // ---- analyse de scène ----
    var scene by mutableStateOf(Scene.STANDARD); private set
    var sceneLabels by mutableStateOf<List<Pair<String, Float>>>(emptyList()); private set
    @Volatile private var activeScene = Scene.STANDARD
    private var candidate = Scene.STANDARD
    private var candidateCount = 0

    // ---- appel par une autre appli (appareil photo par défaut) ----
    /** Non nul quand une autre appli a demandé une photo / vidéo : on lui renvoie le résultat. */
    var externalRequest: ((Uri?, ByteArray?) -> Unit)? = null
    var externalOutput: Uri? = null

    private var index = lenses.indexOfFirst { !it.front && it.kind == "Principal" }.coerceAtLeast(0)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var rawReader: ImageReader? = null
    @Volatile private var curChars: CameraCharacteristics? = null
    private var rawFailed = false
    private var pendingRawImage: android.media.Image? = null
    private var pendingRawResult: TotalCaptureResult? = null
    private var pendingRawOrientation = 0

    // ---- position GPS ----
    @Volatile var location: android.location.Location? = null; private set
    @Volatile var placeName: String? = null; private set
    private var locListening = false
    private val locListener = object : android.location.LocationListener {
        override fun onLocationChanged(l: android.location.Location) {
            val prev = location
            location = l
            if (prev == null || prev.distanceTo(l) > 300f || placeName == null) bg.post { lookupPlace(l) }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    private var previewSurface: Surface? = null
    private var texture: SurfaceTexture? = null
    private var resumed = false
    private var opening = false
    private var lastLive = 0L
    private var pendingOrientation = 0
    private var recorder: MediaRecorder? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recUri: Uri? = null
    private var recStartedAt = 0L
    private val recTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val now = SystemClock.elapsedRealtime()
            val paused = pausedAccum + if (recPaused) now - pausedAt else 0L
            recSeconds = ((now - recStartedAt - paused) / 1000).toInt()
            main.postDelayed(this, 500)
        }
    }

    init { PostProcess.init(ctx.applicationContext) }

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
        startLocation()
        orientationListener.enable()
        openIfReady()
    }

    fun onPause() {
        resumed = false
        stopLocation()
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
        if (recorder != null) finishRecording()
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { pendingRawImage?.close() } catch (_: Exception) {}
        pendingRawImage = null; pendingRawResult = null
        try { rawReader?.close() } catch (_: Exception) {}
        rawReader = null
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
            val sink = frameSink
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                if (sink != null) sink(bytes) else saveJpeg(bytes)
            } catch (e: Exception) {
                post("Échec de l'enregistrement")
            } finally {
                img.close()
                if (sink != null) {
                    // capture multi-images : rien d'autre à faire ici
                } else if (burstActive) {
                    main.post { burstCount++; if (burstCount >= MAX_BURST) stopBurst() }
                    bg.postDelayed({ if (burstActive) still() else main.post { busy = false } }, 30)
                } else main.post { busy = false }
            }
        }, bg)
        reader = r
        val rs = c.rawSize
        val rr = if (cfg.raw && !cfg.video && rs != null && !rawFailed) {
            ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2).also { raw ->
                raw.setOnImageAvailableListener({ rd ->
                    val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
                    pendingRawImage?.close()
                    pendingRawImage = img
                    tryWriteDng()
                }, bg)
            }
        } else null
        rawReader = rr
        val outputs = listOfNotNull(ps, r.surface, rr?.surface)
        try {
            d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device == null) { s.close(); return }
                    session = s
                    updatePreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (rr != null) {
                        rawFailed = true
                        post("RAW impossible avec ce réglage : photo JPEG seule")
                        try { rr.close() } catch (_: Exception) {}
                        rawReader = null
                        try { r.close() } catch (_: Exception) {}
                        reader = null
                        createSession()
                    } else post("Configuration de la caméra impossible")
                }
            }, bg)
        } catch (e: Exception) {
            post("Erreur session : ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Objectifs

    private fun detectLenses(): List<LensInfo> {
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val probed = (0..9).map { it.toString() }.filter { id ->
            id !in listed && runCatching { manager.getCameraCharacteristics(id) }.isSuccess
        }
        val out = (listed + probed).mapNotNull { id ->
            runCatching {
                val ch = manager.getCameraCharacteristics(id)
                val cp = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                if (!cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) return@runCatching null
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val eq = if (focal != null && phys != null && phys.width > 0f) {
                    focal * 43.27f / kotlin.math.sqrt(phys.width * phys.width + phys.height * phys.height)
                } else null
                val px = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                LensInfo(
                    id = id,
                    front = ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT,
                    eqFocal = eq,
                    megapixels = if (px != null) px.width * px.height / 1e6f else 0f,
                    aperture = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
                    minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                    ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                    manual = cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                    raw = cp.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
                    hidden = id !in listed,
                )
            }.getOrNull()
        }
        val main = out.firstOrNull { !it.front && !it.hidden } ?: out.firstOrNull { !it.front }
        val mainEq = main?.eqFocal
        out.forEach { l ->
            val e = l.eqFocal
            l.factor = if (e != null && mainEq != null && mainEq > 0f) e / mainEq else 1f
            l.kind = when {
                l.front -> "Avant"
                l === main -> "Principal"
                l.megapixels in 0.1f..5.5f && l.minFocus >= 15f -> "Macro"
                l.megapixels in 0.1f..2.5f -> "Profondeur"
                l.factor < 0.8f -> "Ultra grand-angle"
                l.factor > 1.5f -> "Téléobjectif"
                else -> "Principal"
            }
        }
        // Objectifs arrière triés du plus large au plus serré, puis l'avant
        return out.sortedBy { it.hidden }
            .distinctBy { Triple(it.front, it.eqFocal?.roundToInt(), it.megapixels.roundToInt()) }
            .sortedWith(compareBy<LensInfo> { it.front }.thenBy { it.factor })
    }

    fun selectLens(id: String) {
        val i = cameraIds.indexOf(id)
        if (i < 0 || i == index || recording) return
        index = i
        tapRegion = null
        focusPoint = null
        bg.post {
            closeCamera()
            if (resumed && texture != null) openCamera()
        }
    }

    // ------------------------------------------------------------------
    // Capacités du capteur

    private fun buildCaps(id: String): CamCaps {
        val ch = manager.getCameraCharacteristics(id)
        curChars = ch
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
        val ratio = if (cfg.wide || cfg.video) 16f / 9f else 4f / 3f
        fun matches(s: Size) = abs(s.width.toFloat() / s.height - ratio) < 0.03f
        val jpegs = map.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
        val jpeg = jpegs.filter(::matches).maxByOrNull { it.width.toLong() * it.height }
            ?: jpegs.maxBy { it.width.toLong() * it.height }
        val prevs = map.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1280, 720))
        val prev = prevs.filter { matches(it) && it.width <= 1920 && it.height <= 1440 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: prevs.minBy { abs(it.width.toFloat() / it.height - ratio) }

        val vids = map.getOutputSizes(MediaRecorder::class.java) ?: arrayOf()
        val wantW = when (cfg.videoRes) { 2160 -> 3840; 720 -> 1280; else -> 1920 }
        val wantH = cfg.videoRes
        val video = vids.firstOrNull { it.width == wantW && it.height == wantH }
            ?: vids.filter { matches(it) && it.width <= 1920 }.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1280, 720)
        val heights = listOf(720 to 1280, 1080 to 1920, 2160 to 3840)
            .filter { (h, w) -> vids.any { it.width == w && it.height == h } }.map { it.first }
        val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf()
        val f30 = fpsRanges.filter { it.upper == 30 }.maxByOrNull { it.lower }
        val f60 = fpsRanges.filter { it.upper == 60 }.maxByOrNull { it.lower }
        val vstab = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

        val li = lenses.firstOrNull { it.id == id }
        val dir = li?.kind ?: if (front) "Avant" else "Arrière"
        val lens = li?.eqFocal?.let { " · ${it.roundToInt()} mm" } ?: ""
        return CamCaps(
            id, front, "$dir$lens", orient, manual, iso, exp, minFocus, awb, af, fx,
            evR, evS, zMin, zMax, useRatio, flash, ois, active, maxAf, maxAe, jpeg, prev, aperture, focal,
            video, heights, f30, f60, vstab,
            ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList() ?: emptyList(),
            ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)?.let { m ->
                when {
                    m.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                    m.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL) -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                    else -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
                }
            } ?: CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF,
            if (capsList.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW))
                map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height } else null,
            if (capsList.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) runCatching {
                map.highSpeedVideoSizes.flatMap { sz ->
                    map.getHighSpeedVideoFpsRangesFor(sz).filter { it.lower == it.upper && it.upper >= 120 }.map { sz to it.upper }
                }.distinct()
            }.getOrDefault(emptyList()) else emptyList()
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
                pro = s.pro && c.manualSensor && !s.video,
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
        if (old.gps != newSettings.gps) { if (newSettings.gps) startLocation() else stopLocation() }
        if (old.raw != newSettings.raw) {
            rawFailed = false
            bg.post { closeCamera(); if (resumed && texture != null) openCamera() }
            return
        }
        if (old.sceneAuto != newSettings.sceneAuto || old.sceneAnalysis != newSettings.sceneAnalysis) {
            if (!newSettings.sceneAnalysis) { activeScene = Scene.STANDARD; scene = Scene.STANDARD }
        }
        if (old.wide != newSettings.wide || old.video != newSettings.video || old.videoRes != newSettings.videoRes) {
            bg.post { closeCamera(); if (resumed && texture != null) openCamera() }
            return
        }
        if (refresh) bg.post { updatePreview() }
    }

    /** Bascule avant / arrière (objectif principal). */
    fun switchCamera() {
        if (recording) return
        val curFront = lenses.getOrNull(index)?.front == true
        val target = lenses.firstOrNull { it.front != curFront && (it.front || it.kind == "Principal") }
            ?: lenses.firstOrNull { it.front != curFront } ?: return
        selectLens(target.id)
    }

    fun resetFocus() {
        tapRegion = null
        focusPoint = null
        lockedFlag = false
        locked = false
        bg.post { updatePreview() }
    }

    /** Appui long : mise au point + exposition verrouillées à cet endroit. */
    fun lockAt(x: Float, y: Float) {
        if (settings.pro || settings.manualFocus) return
        tapToFocus(x, y, lock = true)
        message = "🔒 Exposition et mise au point verrouillées"
    }

    /** Coordonnées capteur → coordonnées écran (0..1), inverse de tapToFocus. */
    private fun mapRect(c: CamCaps, r: Rect): android.graphics.RectF {
        val a = c.activeArray
        fun conv(px: Int, py: Int): Pair<Float, Float> {
            val sx = (px - a.left).toFloat() / a.width()
            val sy = (py - a.top).toFloat() / a.height()
            return when (c.sensorOrientation) {
                90 -> if (c.front) Pair(sy, sx) else Pair(1f - sy, sx)
                270 -> if (c.front) Pair(1f - sy, 1f - sx) else Pair(sy, 1f - sx)
                180 -> Pair(1f - sx, 1f - sy)
                else -> Pair(sx, sy)
            }
        }
        val (x1, y1) = conv(r.left, r.top)
        val (x2, y2) = conv(r.right, r.bottom)
        return android.graphics.RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /** Appui sur l'aperçu : mise au point + mesure à cet endroit (x, y entre 0 et 1). */
    fun tapToFocus(x: Float, y: Float, lock: Boolean = false) {
        lockedFlag = lock
        locked = lock
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
            val c = cur
            if (c != null && cfg.faces && now - lastFaces > 100) {
                lastFaces = now
                val fs = res.get(CaptureResult.STATISTICS_FACES)
                val mapped = fs?.filter { it.score >= 30 }?.map { mapRect(c, it.bounds) } ?: emptyList()
                if (mapped.isNotEmpty() || faces.isNotEmpty()) main.post { faces = mapped }
            }
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
        if (s.pro && !s.video && c.manualSensor) {
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
            val sc = if (s.sceneAnalysis && s.sceneAuto) activeScene else Scene.STANDARD
            val stepsPerEv = if (c.evStep > 0f) 1f / c.evStep else 1f
            fun has(m: Int?) = m != null && c.sceneModes.contains(m)
            val adj = when (sc) {
                Scene.NIGHT -> if (has(sc.sceneMode)) 0f else 0.7f
                Scene.FOOD, Scene.FLOWER, Scene.DOCUMENT -> 0.3f
                Scene.SNOW, Scene.BEACH -> if (has(sc.sceneMode)) 0f else 0.7f
                Scene.SCREEN -> -1f
                Scene.SUNSET -> if (has(sc.sceneMode)) 0f else -0.3f
                else -> 0f
            }
            b.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                (s.ev + (adj * stepsPerEv).roundToInt()).coerceIn(c.evRange.lower, c.evRange.upper)
            )
            val mode = sc.sceneMode
            if (mode != null && mode in c.sceneModes && !s.video && !s.manualFocus && tapRegion == null &&
                s.awb == CameraMetadata.CONTROL_AWB_MODE_AUTO && s.effect == CameraMetadata.CONTROL_EFFECT_MODE_OFF
            ) {
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                b.set(CaptureRequest.CONTROL_SCENE_MODE, mode)
            }
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
            s.video && c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            c.afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        }
        if (region != null && c.maxAeRegions > 0 && !s.pro) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))

        // Verrouillage expo / balance des blancs
        if (lockedFlag && !s.pro) {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        // Détection des visages
        if (s.faces && c.faceMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF) {
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, c.faceMode)
            if (b.get(CaptureRequest.CONTROL_MODE) != CameraMetadata.CONTROL_MODE_USE_SCENE_MODE &&
                c.sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY) &&
                !s.pro && !s.manualFocus && tapRegion == null
            ) {
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
                b.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY)
            }
        }

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

        if (s.video) {
            val fr = if ((s.fps60 || s.videoKind == 1) && c.fps60 != null) c.fps60 else c.fps30
            if (fr != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fr)
            if (c.videoStab) b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (s.stabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
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
        if (settings.video) { toggleRecording(); return }
        if (busy || countdown > 0 || session == null) return
        if (!settings.pro && externalRequest == null) when (settings.special) {
            1 -> { multiShot(night = true); return }
            2 -> { multiShot(night = false); return }
            3 -> { onPanoToggle?.invoke(); return }
        }
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
            val rr = rawReader
            val withRaw = rr != null && !burstActive && externalRequest == null
            if (withRaw) { b.addTarget(rr!!.surface); pendingRawOrientation = jpegOrientation(cur!!) }
            apply(b, capture = true)
            ss.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    if (withRaw) { pendingRawResult = res; tryWriteDng() }
                }
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

    // ------------------------------------------------------------------
    // Multi-images : Nuit et HDR

    @Volatile private var frameSink: ((ByteArray) -> Unit)? = null
    var processing by mutableStateOf<String?>(null)
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun multiShot(night: Boolean) {
        val c = cur ?: return
        val l = live
        val manual = c.manualSensor && l.iso > 0 && l.expNs > 0
        if (!night && !manual) { message = "HDR indisponible sur cet objectif (réglages manuels requis)"; return }
        busy = true
        pendingOrientation = deviceRotation
        processing = if (night) "🌙 Nuit : ne bougez pas…" else "HDR : ne bougez pas…"
        val n = if (night) 6 else 3
        val maxExp = if (night) 125_000_000L else 500_000_000L
        val exps = if (night) List(n) { min(l.expNs, maxExp) } else listOf(l.expNs / 4, l.expNs, l.expNs * 4)
        bg.post {
            captureFrames(n, { i, b ->
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                if (manual) {
                    val e = exps[i].coerceIn(c.expRange.lower, min(c.expRange.upper, maxExp))
                    var iso = l.iso
                    // Pose trop longue pour le capteur : on compense avec les ISO
                    if (e < exps[i]) iso = (iso * (exps[i].toDouble() / e)).roundToInt().coerceIn(c.isoRange.lower, c.isoRange.upper)
                    if (night && l.expNs > maxExp) iso = (l.iso * (l.expNs.toDouble() / e)).roundToInt().coerceIn(c.isoRange.lower, c.isoRange.upper)
                    b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, e)
                }
            }) { frames ->
                main.post { processing = if (night) "🌙 Assemblage de ${frames.size} images…" else "HDR : fusion…" }
                val out = try {
                    val fused = if (night) Fusion.night(frames, cfg.quality) else Fusion.hdr(frames, cfg.quality)
                    PostProcess.copyOrientation(frames[0], fused)
                } catch (t: Throwable) {
                    post(if (t is OutOfMemoryError) "Mémoire insuffisante : photo normale gardée" else "Fusion impossible : photo normale gardée")
                    frames[frames.size / 2]
                }
                bg.post {
                    try { saveJpeg(out) } catch (_: Exception) {}
                    main.post { busy = false; processing = null; message = if (night) "🌙 Photo de nuit enregistrée" else "HDR enregistré" }
                }
            }
        }
    }

    /** Prend n photos à la suite (sans les enregistrer) puis appelle onDone sur un fil de calcul. */
    private fun captureFrames(n: Int, configure: (Int, CaptureRequest.Builder) -> Unit, onDone: (List<ByteArray>) -> Unit) {
        val ss = session; val d = device; val r = reader; val ps = previewSurface
        if (ss == null || d == null || r == null || ps == null) { main.post { busy = false; processing = null }; return }
        val frames = ArrayList<ByteArray>()
        frameSink = { bytes ->
            frames += bytes
            if (frames.size == n) { frameSink = null; worker.execute { onDone(frames) } }
        }
        var first = true
        try {
            val reqs = (0 until n).map { i ->
                d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(r.surface); addTarget(ps)
                    apply(this, capture = true)
                    configure(i, this)
                }.build()
            }
            ss.captureBurst(reqs, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, req: CaptureRequest, ts: Long, frame: Long) {
                    if (first) { first = false; main.post { if (cfg.sound) sound.play(MediaActionSound.SHUTTER_CLICK); flashScreen++ } }
                }
                override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                    if (frameSink != null) {
                        frameSink = null
                        post("Prise de vue interrompue")
                        main.post { busy = false; processing = null }
                    }
                }
            }, bg)
            bg.postDelayed({
                if (frameSink != null) { frameSink = null; post("Délai dépassé"); main.post { busy = false; processing = null } }
            }, 20_000)
        } catch (e: Exception) {
            frameSink = null
            post("Prise de vue impossible : ${e.message}")
            main.post { busy = false; processing = null }
        }
    }

    /** Enregistre une image déjà calculée (panorama). */
    fun saveBitmap(bmp: Bitmap, done: String) {
        busy = true
        worker.execute {
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, cfg.quality, bos)
            bg.post {
                try { saveJpeg(bos.toByteArray()) } catch (_: Exception) {}
                main.post { busy = false; message = done }
            }
        }
    }


    /** Démarrer / terminer le panorama (géré par l'écran, qui lit les images de l'aperçu). */
    var onPanoToggle: (() -> Unit)? = null

    // ------------------------------------------------------------------
    // RAW (DNG)

    private fun tryWriteDng() {
        val img = pendingRawImage ?: return
        val res = pendingRawResult ?: return
        val ch = curChars ?: return
        pendingRawImage = null; pendingRawResult = null
        try {
            val dng = android.hardware.camera2.DngCreator(ch, res)
            dng.setOrientation(
                when (pendingRawOrientation) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
            )
            location?.let { dng.setLocation(it) }
            val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.FRANCE).format(Date()) + ".dng"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoPro")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { dng.writeImage(it, img) }
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                post("Photo + RAW (DNG) enregistrés")
            }
            dng.close()
        } catch (e: Exception) {
            post("RAW non enregistré : ${e.message}")
        } finally {
            img.close()
        }
    }

    // ------------------------------------------------------------------
    // Position

    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locListening || !cfg.gps) return
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        runCatching {
            for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let { if (location == null) locListener.onLocationChanged(it) }
                    lm.requestLocationUpdates(p, 15_000L, 20f, locListener, Looper.getMainLooper())
                }
            }
            locListening = true
        }
    }

    fun stopLocation() {
        if (!locListening) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        runCatching { lm.removeUpdates(locListener) }
        locListening = false
    }

    @Suppress("DEPRECATION")
    private fun lookupPlace(l: android.location.Location) {
        runCatching {
            if (!android.location.Geocoder.isPresent()) return
            val a = android.location.Geocoder(ctx, Locale.FRANCE).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
            if (a != null) placeName = listOfNotNull(a.locality ?: a.subAdminArea, a.countryName).joinToString(", ")
        }
    }

    // ------------------------------------------------------------------
    // Rafale

    fun startBurst() {
        if (settings.video || busy || session == null || externalRequest != null) return
        burstCount = 0
        busy = true
        burstActive = true
        pendingOrientation = deviceRotation
        bg.post { still() }
    }

    fun stopBurst() {
        if (!burstActive) return
        burstActive = false
        main.postDelayed({ message = "Rafale : $burstCount photos" }, 300)
    }

    // ------------------------------------------------------------------
    // Vidéo : pause et photo pendant l'enregistrement

    fun togglePause() {
        val r = recorder ?: return
        if (!recording) return
        try {
            if (recPaused) {
                r.resume()
                pausedAccum += SystemClock.elapsedRealtime() - pausedAt
                recPaused = false
            } else {
                r.pause()
                pausedAt = SystemClock.elapsedRealtime()
                recPaused = true
            }
        } catch (e: Exception) { message = "Pause impossible" }
    }

    /** Enregistre l'image affichée (photo pendant une vidéo). */
    fun saveSnapshot(bmp: Bitmap) {
        val rot = if (cur?.front == true) (360 - deviceRotation) % 360 else deviceRotation
        flashScreen++
        bg.post {
            val out = if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp
            val bos = java.io.ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, cfg.quality, bos)
            val ext = externalRequest
            externalRequest = null
            try { saveJpeg(bos.toByteArray()) } finally { externalRequest = ext }
            post("Photo enregistrée")
        }
    }

    // ------------------------------------------------------------------
    // Histogramme

    fun computeHistogram(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val bins = FloatArray(64)
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val l = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            bins[l shr 2] += 1f
            i += 3
        }
        val m = bins.maxOrNull() ?: 1f
        if (m > 0f) for (k in bins.indices) bins[k] /= m
        histogram = bins
    }

    // ------------------------------------------------------------------
    // Analyse de scène

    fun onSceneLabels(labels: List<Pair<String, Float>>) {
        sceneLabels = labels.take(3)
        var sc = classifyScene(labels)
        val l = live
        val dark = l.iso >= 1600 || l.expNs >= 40_000_000L
        if (dark && !settings.pro && sc != Scene.SCREEN) sc = Scene.NIGHT
        if (sc == candidate) candidateCount++ else { candidate = sc; candidateCount = 1 }
        if (candidateCount >= 2 && sc != scene) {
            scene = sc
            activeScene = sc
            if (cfg.sceneAuto) bg.post { updatePreview() }
        }
    }

    fun clearScene() {
        sceneLabels = emptyList()
        if (scene != Scene.STANDARD) {
            scene = Scene.STANDARD; activeScene = Scene.STANDARD
            bg.post { updatePreview() }
        }
    }

    // ------------------------------------------------------------------
    // Vidéo

    fun toggleRecording() {
        if (busy) return
        busy = true
        pendingOrientation = deviceRotation
        if (recording) bg.post { finishRecording(); createSession(); main.post { busy = false } }
        else bg.post { startRecording(); main.post { busy = false } }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        val d = device; val c = cur; val ps = previewSurface
        if (d == null || c == null || ps == null) return
        try { session?.close() } catch (_: Exception) {}
        session = null
        val s = cfg
        val resolver = ctx.contentResolver
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PhotoPro")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val extOut = if (externalRequest != null) externalOutput else null
        val uri = extOut ?: resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        val pfd = uri?.let { runCatching { resolver.openFileDescriptor(it, "rw") }.getOrNull() ?: runCatching { resolver.openFileDescriptor(it, "w") }.getOrNull() }
        if (uri == null || pfd == null) { post("Stockage indisponible"); createSession(); return }
        val audio = s.videoAudio && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val kind = s.videoKind
        val hs = if (kind == 1) c.slowModes.filter { it.second == s.slowFps && it.first.width <= 1920 }
            .maxByOrNull { it.first.width } ?: c.slowModes.filter { it.first.width <= 1920 }.minByOrNull { abs(it.second - s.slowFps) }
        else null
        val slowFps = hs?.second ?: if (c.fps60 != null) 60 else 30
        val sixty = (s.fps60 || kind == 1) && c.fps60 != null
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            val withAudio = audio && kind == 0
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(pfd.fileDescriptor)
            val px = c.videoSize.width.toLong() * c.videoSize.height
            val rate = when {
                px >= 3840L * 2160 -> 45_000_000
                px >= 1920L * 1080 -> if (sixty) 24_000_000 else 16_000_000
                else -> 8_000_000
            }
            val vsize = hs?.first ?: c.videoSize
            when (kind) {
                1 -> { // ralenti : capturé vite, lu à 30 i/s
                    r.setVideoEncodingBitRate(min(60_000_000, rate * slowFps / 30))
                    r.setVideoFrameRate(30)
                    r.setCaptureRate(slowFps.toDouble())
                }
                2 -> { // timelapse : 1 image toutes les lapseMs, lue à 30 i/s
                    r.setVideoEncodingBitRate(rate)
                    r.setVideoFrameRate(30)
                    r.setCaptureRate(1000.0 / s.lapseMs)
                }
                else -> {
                    r.setVideoEncodingBitRate(rate)
                    r.setVideoFrameRate(if (sixty) 60 else 30)
                }
            }
            r.setVideoSize(vsize.width, vsize.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(192_000)
                r.setAudioSamplingRate(48_000)
            }
            r.setOrientationHint(jpegOrientation(c))
            r.prepare()
        } catch (e: Exception) {
            r.release(); pfd.close(); resolver.delete(uri, null, null)
            post("Enregistrement impossible : ${e.message}")
            createSession(); return
        }
        recorder = r; recPfd = pfd; recUri = uri
        val rs = r.surface
        if (hs != null) { startHighSpeed(d, ps, rs, r, hs.first, hs.second); return }
        try {
            d.createCaptureSession(listOf(ps, rs), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(ss: CameraCaptureSession) {
                    if (device == null) { ss.close(); return }
                    session = ss
                    try {
                        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.addTarget(ps); b.addTarget(rs)
                        apply(b, capture = false)
                        ss.setRepeatingRequest(b.build(), previewCallback, bg)
                        r.start()
                        recStartedAt = SystemClock.elapsedRealtime()
                        pausedAccum = 0L
                        main.post { recording = true; recSeconds = 0; main.post(recTick) }
                        if (cfg.sound) main.post { sound.play(MediaActionSound.START_VIDEO_RECORDING) }
                    } catch (e: Exception) {
                        post("Enregistrement impossible : ${e.message}")
                        finishRecording(); createSession()
                    }
                }
                override fun onConfigureFailed(ss: CameraCaptureSession) {
                    post("Ce format vidéo n'est pas pris en charge, essayez 1080p 30 i/s")
                    finishRecording(); createSession()
                }
            }, bg)
        } catch (e: Exception) {
            post("Enregistrement impossible : ${e.message}")
            finishRecording(); createSession()
        }
    }

    @Suppress("DEPRECATION")
    private fun startHighSpeed(d: CameraDevice, ps: Surface, rs: Surface, r: MediaRecorder, size: Size, fps: Int) {
        texture?.setDefaultBufferSize(size.width, size.height)
        try {
            d.createConstrainedHighSpeedCaptureSession(listOf(ps, rs), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(ss: CameraCaptureSession) {
                    if (device == null) { ss.close(); return }
                    session = ss
                    try {
                        val hss = ss as android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
                        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.addTarget(ps); b.addTarget(rs)
                        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        hss.setRepeatingBurst(hss.createHighSpeedRequestList(b.build()), null, bg)
                        r.start()
                        recStartedAt = SystemClock.elapsedRealtime()
                        pausedAccum = 0L
                        main.post { recording = true; recSeconds = 0; main.post(recTick); message = "Ralenti $fps i/s" }
                    } catch (e: Exception) {
                        post("Ralenti impossible : ${e.message}")
                        finishRecording(); createSession()
                    }
                }
                override fun onConfigureFailed(ss: CameraCaptureSession) {
                    post("Ralenti $fps i/s refusé par la caméra")
                    finishRecording(); createSession()
                }
            }, bg)
        } catch (e: Exception) {
            post("Ralenti impossible : ${e.message}")
            finishRecording(); createSession()
        }
    }

    /** Arrête l'enregistrement (thread caméra). */
    private fun finishRecording() {
        val r = recorder ?: return
        val uri = recUri
        recorder = null; recUri = null
        try { session?.stopRepeating() } catch (_: Exception) {}
        var ok = recording
        try { r.stop() } catch (_: RuntimeException) { ok = false }
        try { r.reset(); r.release() } catch (_: Exception) {}
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        main.post { recording = false; recPaused = false; main.removeCallbacks(recTick) }
        if (uri == null) return
        val resolver = ctx.contentResolver
        val ext = externalRequest
        val isExtOut = ext != null && externalOutput == uri
        if (!ok) {
            if (!isExtOut) try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            post("Vidéo trop courte, non enregistrée")
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && !isExtOut) {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            try { resolver.update(uri, v, null, null) } catch (_: Exception) {}
        }
        if (cfg.sound) main.post { sound.play(MediaActionSound.STOP_VIDEO_RECORDING) }
        if (ext != null) { main.post { ext(uri, null) }; return }
        val thumb = try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(ctx, uri)
            val f = mmr.getFrameAtTime(0)
            mmr.release()
            f?.let { Bitmap.createScaledBitmap(it, 240, (240f * it.height / it.width).toInt().coerceAtLeast(1), true) }
        } catch (_: Exception) { null }
        main.post { lastUri = uri; lastIsVideo = true; if (thumb != null) lastThumb = thumb }
        post(when (cfg.videoKind) { 1 -> "Ralenti enregistré"; 2 -> "Timelapse enregistré"; else -> "Vidéo enregistrée" } + " dans Films/PhotoPro")
    }

    private fun saveJpeg(original: ByteArray) {
        val st = cfg
        val bytes = if (st.watermark > 0 || st.watermarkText.isNotBlank() || (st.gps && location != null)) {
            PostProcess.apply(ctx, original, st.watermark, st.watermarkText, if (st.gps) location else null, placeName, st.quality)
        } else original
        val ext = externalRequest
        if (ext != null) {
            val out = externalOutput
            if (out != null) {
                try { ctx.contentResolver.openOutputStream(out, "w")?.use { it.write(bytes) } } catch (_: Exception) {}
            }
            main.post { ext(out, bytes) }
            return
        }
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
        main.post { lastUri = uri; lastIsVideo = false; if (thumb != null) lastThumb = thumb }
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
            video = prefs.getBoolean("video", d.video),
            videoRes = prefs.getInt("vres", d.videoRes),
            fps60 = prefs.getBoolean("fps60", d.fps60),
            videoAudio = prefs.getBoolean("vaudio", d.videoAudio),
            sceneAnalysis = prefs.getBoolean("scene", d.sceneAnalysis),
            sceneAuto = prefs.getBoolean("sceneAuto", d.sceneAuto),
            fillPreview = prefs.getBoolean("fill", d.fillPreview),
            faces = prefs.getBoolean("faces", d.faces),
            qr = prefs.getBoolean("qr", d.qr),
            histogram = prefs.getBoolean("histo", d.histogram),
            raw = prefs.getBoolean("raw", d.raw),
            watermark = prefs.getInt("wm", d.watermark),
            watermarkText = prefs.getString("wmText", d.watermarkText) ?: "",
            gps = prefs.getBoolean("gps", d.gps),
            peaking = prefs.getBoolean("peak", d.peaking),
            gestureShutter = prefs.getBoolean("gesture", d.gestureShutter),
            voice = prefs.getBoolean("voice", d.voice),
            special = prefs.getInt("special", d.special),
            videoKind = prefs.getInt("vkind", d.videoKind),
            lapseMs = prefs.getInt("lapse", d.lapseMs),
            slowFps = prefs.getInt("slowFps", d.slowFps),
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
            .putBoolean("video", s.video).putInt("vres", s.videoRes).putBoolean("fps60", s.fps60)
            .putBoolean("vaudio", s.videoAudio)
            .putBoolean("scene", s.sceneAnalysis).putBoolean("sceneAuto", s.sceneAuto)
            .putBoolean("fill", s.fillPreview).putBoolean("faces", s.faces).putBoolean("qr", s.qr)
            .putBoolean("histo", s.histogram).putBoolean("raw", s.raw).putInt("wm", s.watermark)
            .putString("wmText", s.watermarkText).putBoolean("gps", s.gps).putBoolean("peak", s.peaking)
            .putBoolean("gesture", s.gestureShutter).putBoolean("voice", s.voice)
            .putInt("special", s.special).putInt("vkind", s.videoKind).putInt("lapse", s.lapseMs).putInt("slowFps", s.slowFps)
            .apply()
    }

    companion object {
        const val PREVIEW_MAX_EXP = 66_000_000L // 1/15 s
        const val MAX_BURST = 30
    }
}


/** Associe les étiquettes de l'IA (ML Kit, en anglais) à un type de scène. */
fun classifyScene(labels: List<Pair<String, Float>>): Scene {
    val map = listOf(
        Scene.SCREEN to listOf("monitor", "computer", "screenshot", "television", "display device", "laptop"),
        Scene.DOCUMENT to listOf("paper", "poster", "menu", "receipt", "newspaper", "handwriting", "text"),
        Scene.FOOD to listOf("food", "dish", "cuisine", "dessert", "cake", "bread", "pizza", "fast food", "meal",
            "fruit", "vegetable", "cookie", "sushi", "pasta", "coffee", "drink", "cheese", "sandwich", "salad"),
        Scene.PET to listOf("dog", "cat", "pet", "bird", "horse", "animal", "insect", "butterfly"),
        Scene.SPORT to listOf("sports", "basketball", "football", "soccer", "ball", "skateboard", "bicycle", "racing"),
        Scene.PORTRAIT to listOf("selfie", "smile", "beard", "hair", "eyelash", "skin", "lipstick", "moustache", "person"),
        Scene.SUNSET to listOf("sunset", "dusk", "sunrise"),
        Scene.SNOW to listOf("snow", "skiing", "ski", "ice"),
        Scene.BEACH to listOf("beach", "sand", "surfing"),
        Scene.FLOWER to listOf("flower", "petal", "plant", "garden", "rose"),
        Scene.LANDSCAPE to listOf("sky", "mountain", "lake", "river", "cliff", "forest", "field", "waterfall",
            "sea", "cloud", "nature", "landscape", "tree"),
    )
    for ((text, _) in labels.sortedByDescending { it.second }) {
        val t = text.lowercase(Locale.ROOT)
        val words = t.split(' ', '-')
        for ((sc, keys) in map) if (keys.any { t == it || words.contains(it) }) return sc
    }
    return Scene.STANDARD
}
