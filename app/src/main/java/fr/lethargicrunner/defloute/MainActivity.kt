package fr.lethargicrunner.defloute

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DeflouteApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeflouteApp() {
    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Defloute") }) }) { padding ->
            AppBody(Modifier.padding(padding))
        }
    }
}

@Composable
private fun AppBody(modifier: Modifier = Modifier) {
    val vm: ScanViewModel = viewModel()
    val state by vm.state.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setFolder(uri)
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Detection d'images floues", style = MaterialTheme.typography.titleMedium)

        Button(
            onClick = { picker.launch(null) },
            enabled = !state.scanning
        ) {
            Text(if (state.treeUri == null) "Choisir un dossier" else "Changer de dossier")
        }

        state.folderName?.let { Text("Dossier : " + it) }

        Button(
            onClick = { vm.startScan() },
            enabled = state.treeUri != null && !state.scanning
        ) {
            Text(if (state.scanning) "Analyse..." else "Analyser le dossier")
        }

        if (state.scanning || state.analysed > 0) {
            LinearProgressIndicator(
                progress = { if (state.total > 0) state.analysed.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Text("" + state.analysed + " / " + state.total + " images")
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.results.isNotEmpty()) {
            // ---- Reglages des seuils : effet immediat, pas de re-scan ----
            Text("Reglages", style = MaterialTheme.typography.titleSmall)
            Text(
                "Seuil de score : " + state.fineThreshold.toInt() +
                    "  (en dessous = floue)",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = state.fineThreshold.toFloat(),
                onValueChange = { vm.setFineThreshold(it.toDouble()) },
                valueRange = 0f..500f
            )
            Text(
                "Seuil de ratio : " + String.format("%.2f", state.ratioThreshold) +
                    "  (au-dessus = floue, cible le flou bruite)",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = state.ratioThreshold.toFloat(),
                onValueChange = { vm.setRatioThreshold(it.toDouble()) },
                valueRange = 0f..1.5f
            )

            val blurryCount = state.results.count { it.isBlurry(state.fineThreshold, state.ratioThreshold) }
            val total = state.results.size
            Text("" + blurryCount + " floues / " + total + " images (tri : plus floues d'abord)")

            LazyColumn {
                items(state.results) { r ->
                    val label = if (r.isBlurry(state.fineThreshold, state.ratioThreshold)) "FLOUE" else "nette"
                    val scoreTxt = "%.0f".format(r.fineScore)
                    val ratioTxt = if (r.fineScore > 0) "%.2f".format(r.ratio) else "n/a"
                    ListItem(
                        headlineContent = { Text(r.name) },
                        supportingContent = {
                            Text(label + " — score " + scoreTxt + " (ratio " + ratioTxt + ") — " + r.sizeBytes / 1024 + " Ko")
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
