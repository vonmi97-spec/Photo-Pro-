package com.vonmi.photopro

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.Locale

/** Reconnaissance de scène sur l'appareil (ML Kit, hors ligne). */
class SceneAnalyzer {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()
    )
    @Volatile private var running = false

    fun analyze(bmp: Bitmap, onResult: (List<Pair<String, Float>>) -> Unit) {
        if (running) return
        running = true
        labeler.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { labels -> onResult(labels.map { it.text to it.confidence }) }
            .addOnCompleteListener { running = false }
    }

    fun close() = labeler.close()

    companion object {
        private val fr = mapOf(
            "monitor" to "Écran", "computer" to "Ordinateur", "screenshot" to "Capture d'écran",
            "television" to "Télévision", "laptop" to "Portable", "paper" to "Papier", "poster" to "Affiche",
            "menu" to "Menu", "food" to "Nourriture", "dish" to "Plat", "cuisine" to "Cuisine", "dessert" to "Dessert",
            "cake" to "Gâteau", "bread" to "Pain", "pizza" to "Pizza", "fast food" to "Fast-food", "fruit" to "Fruit",
            "vegetable" to "Légume", "coffee" to "Café", "drink" to "Boisson", "dog" to "Chien", "cat" to "Chat",
            "pet" to "Animal", "bird" to "Oiseau", "horse" to "Cheval", "selfie" to "Selfie", "smile" to "Sourire",
            "hair" to "Cheveux", "beard" to "Barbe", "skin" to "Peau", "sky" to "Ciel", "mountain" to "Montagne",
            "lake" to "Lac", "river" to "Rivière", "forest" to "Forêt", "field" to "Champ", "sea" to "Mer",
            "cloud" to "Nuage", "tree" to "Arbre", "sunset" to "Coucher de soleil", "snow" to "Neige",
            "beach" to "Plage", "sand" to "Sable", "flower" to "Fleur", "plant" to "Plante", "petal" to "Pétale",
            "car" to "Voiture", "vehicle" to "Véhicule", "building" to "Bâtiment", "city" to "Ville", "room" to "Pièce",
            "desk" to "Bureau", "table" to "Table", "chair" to "Chaise", "glasses" to "Lunettes", "hand" to "Main",
            "night" to "Nuit", "light" to "Lumière", "sports" to "Sport", "ball" to "Ballon", "shoe" to "Chaussure",
            "jacket" to "Veste", "toy" to "Jouet", "musical instrument" to "Instrument", "bicycle" to "Vélo",
            "road" to "Route", "street" to "Rue", "water" to "Eau", "bridge" to "Pont", "fun" to "Fun",
            "event" to "Événement", "crowd" to "Foule", "team" to "Équipe", "stadium" to "Stade",
        )
        fun translate(en: String) = fr[en.lowercase(Locale.ROOT)] ?: en
    }
}
