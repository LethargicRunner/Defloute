package com.lethargicrunner.defloute

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ViewModel du défloutage : une image à la fois, avant/après. Deux modes : classique ou IA. */
class DeblurViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { CLASSIC, AI }

    data class State(
        val source: Bitmap? = null,
        val result: Bitmap? = null,
        val processing: Boolean = false,
        val error: String? = null,
        val mode: Mode = Mode.CLASSIC,
        val strength: Float = 0.5f,
        val upscale: Boolean = true,
        val denoise: Boolean = true,
        val tone: Boolean = true,
        val scoreBefore: Int = 0,
        val scoreAfter: Int = 0,
        val showAfter: Boolean = true,
        /** Progression 0..1 du traitement IA, et texte de l'étape en cours. */
        val progress: Float = 0f,
        val stage: String = "",
        /** Vrai quand le traitement IA est suspendu (bouton Pause). */
        val paused: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null

    /** Demande de pause (bouton) : consultée entre deux tuiles IA. */
    @Volatile
    private var pauseRequested = false

    // Image prétraitée (bruit + tons) réutilisée quand seul le curseur de force change.
    private var preparedKey: String? = null
    private var prepared: IntArray? = null

    /** Mise à jour atomique : appelée depuis plusieurs threads (tuiles IA, thread principal). */
    private fun update(f: (State) -> State) { _state.update(f) }

    fun setMode(m: Mode) {
        if (_state.value.mode == m) return
        job?.cancel()
        pauseRequested = false
        update { it.copy(mode = m, processing = false, error = null, result = null, progress = 0f, stage = "") }
        if (m == Mode.CLASSIC && _state.value.source != null) process()
    }

    fun setStrength(v: Float) {
        update { it.copy(strength = v) }
        if (_state.value.mode == Mode.CLASSIC && _state.value.source != null) process(debounceMs = 300)
    }

    fun toggleUpscale() {
        update { it.copy(upscale = !it.upscale) }
        if (_state.value.mode == Mode.CLASSIC && _state.value.source != null) process(debounceMs = 300)
    }

    fun toggleDenoise() {
        update { it.copy(denoise = !it.denoise) }
        if (_state.value.mode == Mode.CLASSIC && _state.value.source != null) process(debounceMs = 300)
    }

    fun toggleTone() {
        update { it.copy(tone = !it.tone) }
        if (_state.value.mode == Mode.CLASSIC && _state.value.source != null) process(debounceMs = 300)
    }

    fun toggleCompare() {
        update { it.copy(showAfter = !it.showAfter) }
    }

    fun cancel() {
        job?.cancel()
        pauseRequested = false
        update { it.copy(processing = false, paused = false, progress = 0f, stage = "") }
    }

    /** Suspend le traitement IA entre deux tuiles (rien n'est perdu). */
    fun pause() {
        pauseRequested = true
        update { it.copy(paused = true, stage = (if (it.stage.isEmpty()) "IA" else it.stage) + " — en pause") }
    }

    /** Reprend le traitement IA là où il s'était arrêté. */
    fun resume() {
        pauseRequested = false
        update { it.copy(paused = false, stage = it.stage.removeSuffix(" — en pause")) }
    }

    /**
     * Règle la force automatiquement selon le flou détecté dans l'image source :
     * plus l'image est floue, plus la force est élevée.
     */
    fun autoStrength() {
        val src = _state.value.source ?: return
        val score = scoreOf(src)
        val v = when {
            score < 20.0 -> 0.85f
            score < 60.0 -> 0.65f
            score < 150.0 -> 0.5f
            else -> 0.35f
        }
        setStrength(v)
    }

    fun load(uri: Uri) {
        job?.cancel()
        pauseRequested = false
        job = viewModelScope.launch {
            update { it.copy(processing = true, error = null, stage = "Chargement…") }
            try {
                val bmp = withContext(Dispatchers.IO) {
                    ImageLoader.decode(getApplication(), uri, maxDim = 1536)?.bitmap
                } ?: throw IllegalArgumentException("Image illisible")
                prepared = null; preparedKey = null
                update { it.copy(source = bmp, result = null, processing = false, stage = "") }
                if (_state.value.mode == Mode.CLASSIC) process()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                update { it.copy(processing = false, error = MEMORY_ERROR) }
            } catch (e: Exception) {
                update { it.copy(processing = false, error = e.message ?: "Erreur de chargement") }
            }
        }
    }

    /** Traitement classique (rapide) : bruit -> tons -> upscale + netteté. */
    fun process(debounceMs: Long = 0) {
        val src = _state.value.source ?: return
        if (_state.value.mode != Mode.CLASSIC) return
        val st = _state.value
        job?.cancel()
        update { it.copy(processing = true, error = null, progress = 0f, stage = "Traitement…") }
        job = viewModelScope.launch {
            try {
                if (debounceMs > 0) delay(debounceMs)
                val res = withContext(Dispatchers.Default) {
                    val (orig, w) = ImageLoader.toPixels(src)
                    val h = src.height
                    val base = prepare(orig, w, h, st.denoise, st.tone, "classic")
                    ensureActive()
                    val out = DeblurProcessor.process(base, w, h, DeblurProcessor.Params(st.strength, st.upscale))
                    val bmp = ImageLoader.fromPixels(out.pixels, out.width, out.height)
                    // scores comparables : mêmes dimensions réduites avant / après
                    Triple(bmp, scoreOf(src), scoreOf(bmp))
                }
                update {
                    it.copy(
                        result = res.first, processing = false, stage = "",
                        scoreBefore = BlurDetector.displayScore(res.second),
                        scoreAfter = BlurDetector.displayScore(res.third)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                update { it.copy(processing = false, error = MEMORY_ERROR) }
            } catch (e: Throwable) {
                update { it.copy(processing = false, error = e.message ?: "Erreur de traitement") }
            }
        }
    }

    /** Traitement IA (lent) : bruit -> tons -> Real-ESRGAN x4 par tuiles. Lancé par un bouton. */
    fun processAi() {
        val src = _state.value.source ?: return
        val st = _state.value
        val app = getApplication<Application>()
        if (!EsrganUpscaler.isAvailable(app)) {
            update { it.copy(error = "Modèle IA absent de l'application (fichier ${EsrganUpscaler.MODEL_ASSET}). Relance la compilation GitHub pour le générer.") }
            return
        }
        job?.cancel()
        update { it.copy(processing = true, error = null, progress = 0f, stage = "Préparation…") }
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    // 1) taille de travail : on limite la source pour que la sortie x4 reste raisonnable
                    val longest = maxOf(src.width, src.height)
                    val work = if (longest > EsrganUpscaler.MAX_INPUT_DIM) {
                        val k = EsrganUpscaler.MAX_INPUT_DIM.toFloat() / longest
                        Bitmap.createScaledBitmap(
                            src, maxOf(1, (src.width * k).toInt()), maxOf(1, (src.height * k).toInt()), true
                        )
                    } else src
                    val (orig, w) = ImageLoader.toPixels(work)
                    val h = work.height
                    ensureActive()

                    update { it.copy(stage = "Réduction du bruit et correction de la lumière…") }
                    val base = prepare(orig, w, h, st.denoise, st.tone, "ai")
                    ensureActive()

                    // 2) super-résolution par tuiles
                    val out = EsrganUpscaler.upscale(
                        app, base, w, h,
                        onTile = { done, total ->
                            update {
                                it.copy(
                                    progress = done.toFloat() / total,
                                    stage = "IA : tuile $done / $total"
                                )
                            }
                        },
                        checkCancelled = {
                            // Pause : on attend sans consommer de CPU tant que l'utilisateur a suspendu.
                            while (pauseRequested) delay(200)
                            ensureActive()
                        }
                    )
                    if (work !== src) work.recycle()

                    // 3) scores comparables : mêmes dimensions réduites avant / après
                    val before = scoreOf(src)
                    val after = scoreOf(out)
                    Triple(out, before, after)
                }
                update {
                    it.copy(
                        result = res.first, processing = false, progress = 1f, stage = "",
                        scoreBefore = BlurDetector.displayScore(res.second),
                        scoreAfter = BlurDetector.displayScore(res.third)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                pauseRequested = false
                update { it.copy(processing = false, paused = false, progress = 0f, stage = "", error = MEMORY_ERROR) }
            } catch (e: Throwable) {
                pauseRequested = false
                update { it.copy(processing = false, paused = false, progress = 0f, stage = "", error = "Erreur IA : " + (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** Bruit + tons, avec cache (valable tant que la source et les options ne changent pas). */
    private fun prepare(orig: IntArray, w: Int, h: Int, denoise: Boolean, tone: Boolean, tag: String): IntArray {
        val key = "$tag-$w-$h-$denoise-$tone"
        val cached = prepared
        if (cached != null && preparedKey == key) return cached
        var cur = orig
        if (denoise) cur = ImageEnhancer.denoise(cur, w, h, 0.6f)
        if (tone) cur = ImageEnhancer.tone(cur, w, h, ImageEnhancer.ToneParams())
        if (cur === orig) cur = orig.copyOf()
        prepared = cur; preparedKey = key
        return cur
    }

    /** Score de netteté sur une version réduite (≤ 512 px) : comparable entre deux tailles d'image. */
    private fun scoreOf(bmp: Bitmap): Double {
        val longest = maxOf(bmp.width, bmp.height)
        val small = if (longest > 512) {
            val k = 512f / longest
            Bitmap.createScaledBitmap(bmp, maxOf(3, (bmp.width * k).toInt()), maxOf(3, (bmp.height * k).toInt()), true)
        } else bmp
        val (px, w) = ImageLoader.toPixels(small)
        val s = BlurDetector.laplacianVariance(px, w, small.height)
        if (small !== bmp) small.recycle()
        return s
    }

    private companion object {
        const val MEMORY_ERROR =
            "Image trop grande pour la mémoire : désactive l'upscale x2 ou choisis une image plus petite"
    }
}

