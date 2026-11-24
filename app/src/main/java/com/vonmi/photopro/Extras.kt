package com.vonmi.photopro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

// ---------------------------------------------------------------------
// Déclenchement par sourire ou main levée

class GestureTrigger {
    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )
    private val pose = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build()
    )
    @Volatile private var running = 0

    /** onTrigger reçoit "sourire" ou "main". */
    fun check(bmp: Bitmap, onTrigger: (String) -> Unit) {
        if (running > 0) return
        val img = InputImage.fromBitmap(bmp, 0)
        running += 2
        faces.process(img)
            .addOnSuccessListener { list ->
                if (list.any { (it.smilingProbability ?: 0f) > 0.85f }) onTrigger("sourire")
            }
            .addOnCompleteListener { running-- }
        pose.process(img)
            .addOnSuccessListener { p ->
                val nose = p.getPoseLandmark(PoseLandmark.NOSE)
                val lw = p.getPoseLandmark(PoseLandmark.LEFT_WRIST)
                val rw = p.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
                if (nose != null && nose.inFrameLikelihood > 0.6f) {
                    val up = listOfNotNull(lw, rw).any { it.inFrameLikelihood > 0.6f && it.position.y < nose.position.y - 10f }
                    if (up) onTrigger("main")
                }
            }
            .addOnCompleteListener { running-- }
    }

    fun close() { faces.close(); pose.close() }
}

// ---------------------------------------------------------------------
// Commande vocale

class VoiceShutter(private val ctx: Context, private val onCommand: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var active = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        if (active || !available) return
        active = true
        sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { restart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1500 else 400) }
                override fun onResults(results: Bundle?) { handle(results); restart(200) }
                override fun onPartialResults(partialResults: Bundle?) { handle(partialResults) }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        listen()
    }

    private var lastFire = 0L

    private fun handle(b: Bundle?) {
        val words = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.joinToString(" ")?.lowercase() ?: return
        val now = System.currentTimeMillis()
        if (now - lastFire < 2500) return
        val cmd = when {
            listOf("photo", "ouistiti", "cheese", "clic", "souriez").any { words.contains(it) } -> "photo"
            listOf("action", "filme", "enregistre").any { words.contains(it) } -> "video"
            else -> null
        }
        if (cmd != null) { lastFire = now; main.post { onCommand(cmd) } }
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { sr?.startListening(i) }
    }

    private fun restart(delayMs: Long) {
        if (!active) return
        main.postDelayed({ if (active) { runCatching { sr?.cancel() }; listen() } }, delayMs)
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        runCatching { sr?.cancel(); sr?.destroy() }
        sr = null
    }
}

// ---------------------------------------------------------------------
// Focus peaking : contours nets surlignés

fun computePeaking(src: Bitmap): Bitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val lum = IntArray(w * h)
    for (i in px.indices) {
        val p = px[i]
        lum[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
    }
    val out = IntArray(w * h)
    var maxG = 1
    val g = IntArray(w * h)
    for (y in 1 until h - 1) {
        val o = y * w
        for (x in 1 until w - 1) {
            val i = o + x
            val gx = -lum[i - w - 1] - 2 * lum[i - 1] - lum[i + w - 1] + lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]
            val gy = -lum[i - w - 1] - 2 * lum[i - w] - lum[i - w + 1] + lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]
            val m = abs(gx) + abs(gy)
            g[i] = m
            if (m > maxG) maxG = m
        }
    }
    val thr = maxOf(120, (maxG * 0.35f).toInt())
    for (i in g.indices) if (g[i] >= thr) out[i] = 0xFF39FF6A.toInt()
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

// ---------------------------------------------------------------------
// Texte : lecture (OCR), copie, traduction

fun recognizeText(bmp: Bitmap, onResult: (String) -> Unit) {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        .process(InputImage.fromBitmap(bmp, 0))
        .addOnSuccessListener { onResult(it.text) }
        .addOnFailureListener { onResult("") }
}

@Composable
fun TextResultDialog(text: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var translated by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (text.isBlank()) "Aucun texte trouvé" else "Texte détecté") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (text.isBlank()) Text("Rapprochez-vous du texte et assurez-vous qu'il est net et bien éclairé.")
                else SelectionContainer { Text(text, fontSize = 14.sp) }
                val t = translated
                if (t != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("Traduction", color = Color(0xFFFFC83D), fontWeight = FontWeight.Bold)
                    SelectionContainer { Text(t, fontSize = 14.sp) }
                }
                status?.let { Text(it, color = Color(0xFF9AA0AA), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (text.isNotBlank()) Row {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("Texte", translated ?: text))
                    status = "Copié dans le presse-papiers"
                }) { Text("Copier") }
                TextButton(onClick = {
                    status = "Traduction… (1re fois : téléchargement du modèle, ~30 Mo)"
                    translate(text) { r, err -> translated = r; status = err }
                }) { Text("Traduire") }
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, translated ?: text), "Partager"
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Partager") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Fermer") } }
    )
}

/** Détecte la langue, traduit vers le français (ou vers l'anglais si le texte est déjà en français). */
private fun translate(text: String, done: (String?, String?) -> Unit) {
    LanguageIdentification.getClient().identifyLanguage(text)
        .addOnSuccessListener { code ->
            val src = TranslateLanguage.fromLanguageTag(code) ?: TranslateLanguage.ENGLISH
            val dst = if (src == TranslateLanguage.FRENCH) TranslateLanguage.ENGLISH else TranslateLanguage.FRENCH
            val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    tr.translate(text)
                        .addOnSuccessListener { done(it, "Traduit (${src.uppercase()} → ${dst.uppercase()})"); tr.close() }
                        .addOnFailureListener { done(null, "Traduction impossible"); tr.close() }
                }
                .addOnFailureListener { done(null, "Téléchargement du modèle impossible (internet requis la 1re fois)"); tr.close() }
        }
        .addOnFailureListener { done(null, "Langue non reconnue") }
}

// ---------------------------------------------------------------------
// Scanner de documents : enregistrement du PDF

fun savePdf(ctx: Context, src: Uri): Uri? = runCatching {
    val name = "Scan_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.FRANCE).format(java.util.Date()) + ".pdf"
    val resolver = ctx.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/PhotoPro")
    }
    val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.getContentUri("external") else return@runCatching src
    val out = resolver.insert(collection, values) ?: return@runCatching null
    resolver.openInputStream(src)?.use { i -> resolver.openOutputStream(out)?.use { o -> i.copyTo(o) } }
    out
}.getOrNull()

// ---------------------------------------------------------------------
// Feuille « Outils »

@Composable
fun ToolsSheet(onScan: () -> Unit, onText: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Outils") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolItem("📄", "Scanner un document", "Détecte la feuille, la redresse, enregistre en PDF (Documents/PhotoPro)") { onClose(); onScan() }
                ToolItem("🔤", "Lire le texte", "Copie ou traduit le texte visible à l'écran") { onClose(); onText() }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } }
    )
}

@Composable
private fun ToolItem(icon: String, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B0C0F)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 26.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(sub, color = Color(0xFF9AA0AA), fontSize = 12.sp)
        }
    }
}
