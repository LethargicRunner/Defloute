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

data class ScanState(
    val treeUri: Uri? = null,
    val folderName: String? = null,
    val scanning: Boolean = false,
    val analysed: Int = 0,
    val total: Int = 0,
    val results: List<ImageResult> = emptyList(),
    val error: String? = null,
    /** Seuils reglables en direct, persistes entre les sessions. */
    val fineThreshold: Double = BlurDetector.DEFAULT_BLUR_THRESHOLD,
    val ratioThreshold: Double = BlurDetector.DEFAULT_RATIO_THRESHOLD
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("defloute_settings", Application.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        ScanState(
            fineThreshold = prefs.getFloat(KEY_FINE, BlurDetector.DEFAULT_BLUR_THRESHOLD.toFloat()).toDouble(),
            ratioThreshold = prefs.getFloat(KEY_RATIO, BlurDetector.DEFAULT_RATIO_THRESHOLD.toFloat()).toDouble()
        )
    )
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun setFolder(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        val name = DocumentFile.fromTreeUri(getApplication(), uri)?.name
        _state.update { ScanState(treeUri = uri, folderName = name, fineThreshold = it.fineThreshold, ratioThreshold = it.ratioThreshold) }
    }

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

    /** Seuil absolu : pas de re-scan necessaire, la liste se re-trie en direct. */
    fun setFineThreshold(value: Double) {
        _state.update { it.copy(fineThreshold = value) }
        prefs.edit().putFloat(KEY_FINE, value.toFloat()).apply()
    }

    /** Seuil ratio : idem. */
    fun setRatioThreshold(value: Double) {
        _state.update { it.copy(ratioThreshold = value) }
        prefs.edit().putFloat(KEY_RATIO, value.toFloat()).apply()
    }

    companion object {
        private const val KEY_FINE = "fine_threshold"
        private const val KEY_RATIO = "ratio_threshold"
    }
}
