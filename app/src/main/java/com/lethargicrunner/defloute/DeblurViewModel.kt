package com.lethargicrunner.defloute

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ViewModel du défloutage : une image à la fois, avant/après. */
class DeblurViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val source: Bitmap? = null,
        val result: Bitmap? = null,
        val processing: Boolean = false,
        val error: String? = null,
        val strength: Float = 0.5f,
        val upscale: Boolean = true,
        val scoreBefore: Int = 0,
        val scoreAfter: Int = 0,
        val showAfter: Boolean = true
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun setStrength(v: Float) {
        _state.value = _state.value.copy(strength = v)
        if (_state.value.source != null) process()
    }

    fun toggleUpscale() {
        _state.value = _state.value.copy(upscale = !_state.value.upscale)
        if (_state.value.source != null) process()
    }

    fun toggleCompare() {
        _state.value = _state.value.copy(showAfter = !_state.value.showAfter)
    }

    fun load(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(processing = true, error = null)
            try {
                val bmp = withContext(Dispatchers.IO) {
                    ImageLoader.decode(getApplication(), uri, maxDim = 1536)?.bitmap
                } ?: throw IllegalArgumentException("Image illisible")
                _state.value = _state.value.copy(source = bmp, result = null)
                process()
            } catch (e: Exception) {
                _state.value = _state.value.copy(processing = false, error = e.message ?: "Erreur de chargement")
            }
        }
    }

    fun process() {
        val src = _state.value.source ?: return
        val s = _state.value.strength
        val up = _state.value.upscale
        _state.value = _state.value.copy(processing = true, error = null)
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val (px, w) = ImageLoader.toPixels(src)
                    val out = DeblurProcessor.process(px, w, src.height, DeblurProcessor.Params(s, up))
                    val before = DeblurProcessor.sharpness(px, w, src.height)
                    val after = DeblurProcessor.sharpness(out.pixels, out.width, out.height)
                    Triple(ImageLoader.fromPixels(out.pixels, out.width, out.height), before, after)
                }
                _state.value = _state.value.copy(
                    result = res.first,
                    processing = false,
                    scoreBefore = BlurDetector.displayScore(res.second),
                    scoreAfter = BlurDetector.displayScore(res.third)
                )
            } catch (e: OutOfMemoryError) {
                _state.value = _state.value.copy(processing = false, error = "Image trop grande pour la mémoire : désactive l'upscale x2 ou choisis une image plus petite")
            } catch (e: Exception) {
                _state.value = _state.value.copy(processing = false, error = e.message ?: "Erreur de traitement")
            }
        }
    }
}
