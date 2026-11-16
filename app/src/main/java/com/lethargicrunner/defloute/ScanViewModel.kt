package com.lethargicrunner.defloute

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ViewModel du scan (survit à la rotation, cf. V0.2.0). */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val scanning: Boolean = false,
        val progress: Int = 0,
        val total: Int = 0,
        val items: List<ImageScanner.ScanItem> = emptyList(),
        val settings: BlurDetector.Settings = BlurDetector.Settings()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val prefs = app.getSharedPreferences("defloute_settings", Application.MODE_PRIVATE)

    init {
        _state.value = _state.value.copy(
            settings = BlurDetector.Settings(
                threshold = prefs.getFloat("threshold", 100f).toDouble(),
                ratioThreshold = prefs.getFloat("ratio", 0.5f)
            )
        )
    }

    fun setThreshold(v: Float) {
        prefs.edit().putFloat("threshold", v).apply()
        _state.value = _state.value.copy(settings = _state.value.settings.copy(threshold = v.toDouble()))
    }

    fun setRatio(v: Float) {
        prefs.edit().putFloat("ratio", v).apply()
        _state.value = _state.value.copy(settings = _state.value.settings.copy(ratioThreshold = v))
    }

    fun scan(treeUri: Uri) {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, items = emptyList(), progress = 0, total = 0)
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val items = withContext(Dispatchers.IO) {
                ImageScanner.scanFolder(ctx, treeUri) { p, t ->
                    _state.value = _state.value.copy(progress = p, total = t)
                }
            }
            _state.value = _state.value.copy(scanning = false, items = items)
        }
    }
}
