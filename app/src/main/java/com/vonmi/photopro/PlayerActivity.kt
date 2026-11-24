package com.vonmi.photopro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box

/** Lecteur vidéo ouvert par les autres applis (« Ouvrir avec Photo Pro »). */
class PlayerActivity : ComponentActivity() {
    // Plein écran immersif : barres d'état et de navigation masquées (glisser depuis le bord pour les afficher).
    private fun pleinEcran() {
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pleinEcran()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pleinEcran()
        @Suppress("DEPRECATION")
        val uri = intent?.data ?: intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFC83D))) {
                Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
                    PlayerScreen(onClose = { finish() }, initialUri = uri, initialText = text)
                }
            }
        }
    }
}
