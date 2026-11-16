package com.lethargicrunner.defloute

import android.content.Intent
import android.net.Uri
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
                Scaffold(bottomBar = {
                    NavigationBar {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Déflouter") }, icon = {})
                        NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Scanner") }, icon = {})
                    }
                }) { pad ->
                    Box(Modifier.padding(pad)) {
                        if (tab == 0) DeblurScreen() else ScanScreen()
                    }
                }
            }
        }
    }
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

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally) {

        Text("Défloute", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Button(onClick = { pickImage.launch(arrayOf("image/*")) }, enabled = !s.processing) {
            Text("Choisir une image")
        }

        s.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))

        val shown = if (s.showAfter) s.result else s.source
        shown?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = if (s.showAfter) "Après" else "Avant",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !s.showAfter, onClick = { vm.toggleCompare() }, label = { Text("Avant") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = s.showAfter, onClick = { vm.toggleCompare() }, label = { Text("Après") })
                Spacer(Modifier.width(16.dp))
                Text("Netteté : ${s.scoreBefore} → ${s.scoreAfter}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Force : ${(s.strength * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
        Slider(value = s.strength, onValueChange = { vm.setStrength(it) }, valueRange = 0f..1f)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = s.upscale, onCheckedChange = { vm.toggleUpscale() })
            Spacer(Modifier.width(8.dp))
            Text("Upscale x2")
        }

        Spacer(Modifier.height(8.dp))
        if (s.processing) CircularProgressIndicator(Modifier.size(28.dp))

        if (s.result != null) {
            Button(onClick = { pickFolder.launch(null) }) { Text("Enregistrer dans un dossier") }
        }
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
