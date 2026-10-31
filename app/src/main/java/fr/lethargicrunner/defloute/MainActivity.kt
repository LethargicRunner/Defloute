package fr.lethargicrunner.defloute

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var results by remember { mutableStateOf<List<ImageResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            treeUri = uri
            results = emptyList()
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Detection d'images floues", style = MaterialTheme.typography.titleMedium)

        Button(
            onClick = { picker.launch(null) },
            enabled = !scanning
        ) {
            Text(if (treeUri == null) "Choisir un dossier" else "Changer de dossier")
        }

        treeUri?.let {
            Text("Dossier : " + (DocumentFile.fromTreeUri(context, it)?.name ?: "?"))
        }

        Button(
            onClick = {
                val root = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
                if (root == null) {
                    error = "Dossier invalide"
                } else {
                    scanning = true
                    error = null
                    results = emptyList()
                    scope.launch {
                        try {
                            results = withContext(Dispatchers.IO) {
                                ImageScanner.scan(context, root) { done, total -> progress = done to total }
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "Erreur inconnue"
                        } finally {
                            scanning = false
                            progress = null
                        }
                    }
                }
            },
            enabled = treeUri != null && !scanning
        ) {
            Text(if (scanning) "Analyse..." else "Analyser le dossier")
        }

        progress?.let { (done, total) ->
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Text("" + done + " / " + total + " images")
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (results.isNotEmpty()) {
            val blurryCount = results.count { it.blurry }
            val total = results.size
            Text("" + blurryCount + " floues / " + total + " images (tri : plus floues d'abord)")
            LazyColumn {
                items(results) { r ->
                    ListItem(
                        headlineContent = { Text(r.name) },
                        supportingContent = {
                            val label = if (r.blurry) "FLOUE" else "nette"
                            val scoreTxt = "%.0f".format(r.score)
                            Text(label + " — score " + scoreTxt + " — " + r.sizeBytes / 1024 + " Ko")
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
