package fr.lethargicrunner.defloute

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Etat du scan. Vit dans le ViewModel : SURVIT A LA ROTATION de l'ecran,
 * le scan continue pendant que l'interface est reconstruite.
 */
data class ScanState(
    val treeUri: Uri? = null,
    val folderName: String? = null,
    val scanning: Boolean = false,
    val analysed: Int = 0,
    val total: Int = 0,
    val results: List<ImageResult> = emptyList(),
    val error: String? = null
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var scanJob: Job? = null

    /** Enregistre le dossier choisi (permission persistante pour ne pas la reperdre apres redemarrage). */
    fun setFolder(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        val name = DocumentFile.fromTreeUri(getApplication(), uri)?.name
        _state.update { ScanState(treeUri = uri, folderName = name) }
    }

    /** Lance le scan ; sans effet si deja en cours. L'ancien scan est annule si on change de dossier. */
    fun startScan() {
        val current = _state.value
        val root = current.treeUri?.let { DocumentFile.fromTreeUri(getApplication(), it) }
        if (root == null || current.scanning) return

        scanJob?.cancel()
        _state.update { it.copy(scanning = true, analysed = 0, total = 0, results = emptyList(), error = null) }

        scanJob = viewModelScope.launch {
            try {
                val scanned = withContext(Dispatchers.IO) {
                    ImageScanner.scan(getApplication(), root) { done, total ->
                        _state.update { s -> s.copy(analysed = done, total = total) }
                    }
                }
                _state.update { it.copy(results = scanned) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Erreur inconnue") }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }
}
