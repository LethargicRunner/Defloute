package com.lethargicrunner.defloute

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var tab by remember { mutableIntStateOf(0) }
                var showHelp by remember { mutableStateOf(false) }
                Scaffold(
                    topBar = { AppTopBar(onHelp = { showHelp = true }) },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Déflouter") }, icon = {})
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Scanner") }, icon = {})
                        }
                    }
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        if (tab == 0) DeblurScreen() else ScanScreen()
                    }
                }
                if (showHelp) HelpDialog(onClose = { showHelp = false })
            }
        }
    }
}

// ------------------------------------------------------------------ Barre du haut + mode d'emploi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(onHelp: () -> Unit) {
    TopAppBar(
        title = { Text("Défloute") },
        actions = {
            IconButton(onClick = onHelp) {
                Text("?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } },
        title = { Text("Mode d'emploi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HelpSection(
                    "À quoi sert l'application ?",
                    "Défloute améliore tes photos floues, bruitées ou voilées, et repère les photos floues dans un dossier. Tout se passe sur ton téléphone : aucune image n'est envoyée sur Internet."
                )
                HelpSection(
                    "Onglet « Déflouter »",
                    "1. Appuie sur « Choisir une image ».\n" +
                        "2. Choisis un mode :\n" +
                        "• Classique : rapide (quelques secondes), le résultat se met à jour quand tu bouges les réglages.\n" +
                        "• IA Real-ESRGAN : plus lent (1 à 5 minutes) mais bien plus détaillé. Appuie sur « Lancer l'amélioration IA » ; tu peux annuler à tout moment.\n" +
                        "3. Compare avec les boutons « Avant » / « Après ».\n" +
                        "4. Appuie sur « Enregistrer dans un dossier » : la photo améliorée est ajoutée (jamais d'écrasement de l'original)."
                )
                HelpSection(
                    "Les réglages",
                    "• Force : intensité de la netteté (mode Classique). Au-delà de 70 %, des halos peuvent apparaître.\n" +
                        "• Upscale x2 : double la taille de l'image avant le traitement (mode Classique).\n" +
                        "• Réduire le bruit : adoucit le grain sans effacer les contours. Garde-le activé pour les photos sombres ou prises de nuit.\n" +
                        "• Corriger lumière et contraste : étire les contrastes, éclaircit les ombres et tasse un peu les zones très claires.\n" +
                        "• Netteté : le score avant → après. Plus il est élevé, plus l'image est nette."
                )
                HelpSection(
                    "Onglet « Scanner »",
                    "Choisis un dossier : toutes les images (sous-dossiers compris) sont analysées et les plus floues apparaissent en premier. Les deux curseurs règlent la sévérité :\n" +
                        "• Seuil score : en dessous, l'image est jugée floue.\n" +
                        "• Seuil ratio : repère les images floues mais bruitées. Baisse-le pour être plus sévère.\n" +
                        "Le scan ne modifie ni ne supprime jamais tes photos."
                )
                HelpSection(
                    "Ce qu'il faut savoir",
                    "• Aucune méthode ne retrouve un détail complètement perdu : l'IA le réinvente de façon plausible. Un visage ou un texte très flou peut sembler net mais être légèrement différent de la réalité.\n" +
                        "• Les zones entièrement blanches (surexposées) ou noires ne se récupèrent pas.\n" +
                        "• Le mode IA réduit d'abord les grandes photos (1024 px max) puis les agrandit 4 fois : le résultat peut atteindre 4096 px.\n" +
                        "• Le mode IA chauffe le téléphone et consomme de la batterie : branche-le pour les grosses images.\n" +
                        "• Si l'application indique un manque de mémoire, désactive l'upscale x2 ou choisis une image plus petite."
                )
            }
        }
    )
}

@Composable
private fun HelpSection(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))
}

// ------------------------------------------------------------------ Déflouter

@Composable
fun DeblurScreen(vm: DeblurViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.load(it) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { saveUri ->
            val ctx = vm.getApplication<android.app.Application>()
            s.result?.let { bmp ->
                val name = "defloute-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                ImageSaver.saveToTree(ctx, saveUri, bmp, name)
            }
        }
    }
    val isAi = s.mode == DeblurViewModel.Mode.AI

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- choix du mode
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !isAi, enabled = !s.processing,
                onClick = { vm.setMode(DeblurViewModel.Mode.CLASSIC) },
                label = { Text("Classique") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = isAi, enabled = !s.processing,
                onClick = { vm.setMode(DeblurViewModel.Mode.AI) },
                label = { Text("IA Real-ESRGAN") }
            )
        }
        Spacer(Modifier.height(12.dp))

        Button(onClick = { pickImage.launch(arrayOf("image/*")) }, enabled = !s.processing) {
            Text("Choisir une image")
        }

        s.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))

        val shown = if (s.showAfter) (s.result ?: s.source) else s.source
        shown?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = if (s.showAfter) "Après" else "Avant",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !s.showAfter, onClick = { vm.toggleCompare() }, label = { Text("Avant") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = s.showAfter, onClick = { vm.toggleCompare() }, label = { Text("Après") })
                Spacer(Modifier.width(16.dp))
                if (s.result != null) {
                    Text("Netteté : ${s.scoreBefore} → ${s.scoreAfter}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- réglages communs
        SwitchRow("Réduire le bruit", s.denoise, enabled = !s.processing) { vm.toggleDenoise() }
        SwitchRow("Corriger lumière et contraste", s.tone, enabled = !s.processing) { vm.toggleTone() }

        if (!isAi) {
            Spacer(Modifier.height(8.dp))
            Text("Force : ${(s.strength * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
            Slider(value = s.strength, onValueChange = { vm.setStrength(it) }, valueRange = 0f..1f)
            SwitchRow("Upscale x2", s.upscale, enabled = !s.processing) { vm.toggleUpscale() }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "L'IA agrandit l'image 4 fois (1024 px max en entrée) et dessine les détails manquants. Compte 1 à 5 minutes selon le téléphone.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.processAi() }, enabled = s.source != null && !s.processing) {
                Text("Lancer l'amélioration IA")
            }
        }

        // --- progression
        Spacer(Modifier.height(8.dp))
        if (s.processing) {
            if (isAi && s.progress > 0f) {
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            if (s.stage.isNotEmpty()) Text(s.stage, style = MaterialTheme.typography.bodySmall)
            if (isAi) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { vm.cancel() }) { Text("Annuler") }
            }
        }

        if (s.result != null && !s.processing) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { pickFolder.launch(null) }) { Text("Enregistrer dans un dossier") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

// ------------------------------------------------------------------ Scanner

@Composable
fun ScanScreen(vm: ScanViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.scan(it) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Scanner un dossier", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { pickFolder.launch(null) }, enabled = !s.scanning) {
            Text(if (s.scanning) "Scan en cours…" else "Choisir un dossier")
        }
        if (s.scanning && s.total > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { s.progress.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
            Text("${s.progress}/${s.total}", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        Text("Seuil score : ${s.settings.threshold.toInt()}", style = MaterialTheme.typography.bodySmall)
        Slider(value = s.settings.threshold.toFloat(), onValueChange = { vm.setThreshold(it) }, valueRange = 0f..500f)
        Text("Seuil ratio : ${"%.2f".format(s.settings.ratioThreshold)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = s.settings.ratioThreshold, onValueChange = { vm.setRatio(it) }, valueRange = 0f..1.5f)

        Spacer(Modifier.height(12.dp))
        val sorted = s.items.sortedWith(
            compareByDescending<ImageScanner.ScanItem> { it.isBlurry(s.settings) }.thenBy { it.fineScore }
        )
        sorted.forEach { item ->
            val blurry = item.isBlurry(s.settings)
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        "${if (blurry) "FLOUE" else "NETTE"} · score ${BlurDetector.displayScore(item.fineScore)} · ratio ${"%.2f".format(item.ratio)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (blurry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
