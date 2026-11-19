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

    enum class Mode { AUTO, CLASSIC, AI }

    data class State(
        val source: Bitmap? = null,
        val result: Bitmap? = null,
        val processing: Boolean = false,
        val error: String? = null,
        val mode: Mode = Mode.AUTO,
        val strength: Float = 0.5f,
        val upscale: Boolean = true,
        val denoise: Boolean = true,
        val tone: Boolean = true,
        /** Défloutage directionnel (flou de mouvement), les deux modes. */
        val motion: Boolean = false,
        /** Réduction de bruit renforcée (non-local means), les deux modes. */
        val nlm: Boolean = false,
        /** Passe micro-contraste finale, les deux modes. */
        val micro: Boolean = false,
        val scoreBefore: Int = 0,
        val scoreAfter: Int = 0,
        val showAfter: Boolean = true,
        /** Progression 0..1 du traitement IA, et texte de l'étape en cours. */
        val progress: Float = 0f,
        val stage: String = "",
        /** Vrai quand le traitement IA est suspendu (bouton Pause). */
        val paused: Boolean = false,
        /** Heure de lancement du traitement (ms) : sert au nom du fichier enregistré. */
        val startedAt: Long = 0L,
        /** Heure de fin estimée du traitement IA (ms), 0 tant qu'elle n'est pas calculable. */
        val etaAt: Long = 0L,
        /** Part (0..1) de pixels blancs / noirs purs dans la source : zones non récupérables. */
        val clipWhite: Float = 0f,
        val clipBlack: Float = 0f,
        /** Information affichée à l'utilisateur (ex. pause ou annulation automatique). */
        val notice: String? = null,
        /** Diagnostic automatique de la photo source (mode Auto). */
        val diagnosis: PhotoDiagnosis.Diagnosis? = null,
        /** Étapes appliquées par le traitement automatique, en clair. */
        val autoSteps: List<String> = emptyList(),
        /** Vrai pendant un traitement IA, quel que soit le mode (Auto ou IA). */
        val aiActive: Boolean = false,
        /** Date de prise de vue, GPS, appareil de la photo d'origine (recopiés dans le fichier enregistré). */
        val sourceMeta: ImageSaver.SourceMeta? = null
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
        update { it.copy(mode = m, processing = false, error = null, result = null, progress = 0f, stage = "", paused = false) }
        if (_state.value.source != null) when (m) {
            Mode.CLASSIC -> process()
            Mode.AUTO -> processAuto()
            else -> Unit
        }
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

    fun toggleMotion() {
        update { it.copy(motion = !it.motion) }
        if (_state.value.mode == Mode.CLASSIC && _state.value.source != null) process(debounceMs = 300)
    }

    fun toggleNlm() {
        update { it.copy(nlm = !it.nlm) }
        if (_state.value.mode == Mode.CLASSIC && _state.value.source != null) process(debounceMs = 300)
    }

    fun toggleMicro() {
        update { it.copy(micro = !it.micro) }
        if (_state.value.mode == Mode.CLASSIC && _state.value.source != null) process(debounceMs = 300)
    }

    fun toggleCompare() {
        update { it.copy(showAfter = !it.showAfter) }
    }

    fun cancel() {
        job?.cancel()
        pauseRequested = false
        autoPaused = false
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

    /** Vrai si la pause en cours a été déclenchée automatiquement (app en arrière-plan). */
    private var autoPaused = false

    /** L'app n'est plus visible (accueil, écran en veille) : applique le réglage choisi par l'utilisateur. */
    fun onAppHidden(policy: AppSettings.BgPolicy) {
        val st = _state.value
        if (!st.processing) return
        when (policy) {
            AppSettings.BgPolicy.PAUSE -> if ((st.mode == Mode.AI || st.aiActive) && !st.paused) {
                pause()
                autoPaused = true
                update { it.copy(notice = "Traitement mis en pause automatiquement : il reprendra à ton retour dans l'application.") }
            }
            AppSettings.BgPolicy.CANCEL -> {
                cancel()
                update { it.copy(notice = "Traitement annulé automatiquement : l'application est passée en arrière-plan ou l'écran s'est éteint.") }
            }
            else -> Unit // CONTINUE (service) ou NOTHING
        }
    }

    /** L'app est de nouveau visible : reprend une pause automatique. */
    fun onAppVisible() {
        if (autoPaused) {
            autoPaused = false
            resume()
            update { it.copy(notice = null) }
        }
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
                val loaded = withContext(Dispatchers.IO) {
                    // Auto et IA décodent jusqu'à 2048 px (plus de détails) ; le mode Manuel reste à 1536 (il agrandit ×2)
                    ImageLoader.decode(getApplication(), uri, maxDim = if (_state.value.mode == Mode.CLASSIC) 1536 else 2048)?.bitmap?.let { b ->
                        Pair(b, ImageEnhancer.clippedFractions(ImageLoader.toPixels(b).first))
                    }
                } ?: throw IllegalArgumentException("Image illisible")
                val bmp = loaded.first
                val meta = withContext(Dispatchers.IO) { ImageSaver.readMeta(getApplication(), uri) }
                prepared = null; preparedKey = null
                update {
                    it.copy(
                        source = bmp, sourceMeta = meta, result = null, processing = false, stage = "",
                        clipWhite = loaded.second.first, clipBlack = loaded.second.second
                    )
                }
                update { it.copy(diagnosis = null, autoSteps = emptyList()) }
                when (_state.value.mode) {
                    Mode.CLASSIC -> process()
                    Mode.AUTO -> processAuto()
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                CrashLog.log(getApplication<Application>(), "Mémoire insuffisante (mode ${_state.value.mode})", e)
                update { it.copy(processing = false, error = MEMORY_ERROR) }
            } catch (e: Exception) {
                CrashLog.log(getApplication<Application>(), "Erreur de chargement", e)
                update { it.copy(processing = false, error = e.message ?: "Erreur de chargement") }
            }
        }
    }

    /** Traitement classique (rapide) : bruit -> tons -> mouvement -> upscale + netteté -> micro. */
    fun process(debounceMs: Long = 0) {
        val src = _state.value.source ?: return
        if (_state.value.mode != Mode.CLASSIC) return
        val st = _state.value
        job?.cancel()
        update { it.copy(processing = true, error = null, progress = 0f, stage = "Traitement…", startedAt = System.currentTimeMillis(), etaAt = 0L, notice = null) }
        job = viewModelScope.launch {
            try {
                if (debounceMs > 0) delay(debounceMs)
                val res = withContext(Dispatchers.Default) {
                    // le mode Manuel agrandit x2 : on limite la source à 1536 px pour rester dans la mémoire
                    val srcW = limitSize(src, 1536)
                    val (orig, w) = ImageLoader.toPixels(srcW)
                    val h = srcW.height
                    ensureActive()
                    var base = prepare(orig, w, h, st.denoise, st.tone, st.nlm, "classic")
                    if (st.motion) {
                        update { it.copy(stage = "Défloutage directionnel…") }
                        base = MotionDeblur.autoDeblur(base, w, h, strength = 0.8f)
                        ensureActive()
                    }
                    val out = DeblurProcessor.process(base, w, h, DeblurProcessor.Params(st.strength, st.upscale))
                    var outPx = out.pixels
                    var outW = out.width
                    var outH = out.height
                    if (st.micro) {
                        update { it.copy(stage = "Micro-contraste…") }
                        outPx = EnhanceExtras.microContrast(outPx, outW, outH, 0.4f)
                    }
                    val bmp = ImageLoader.fromPixels(outPx, outW, outH)
                    // scores comparables : mêmes dimensions réduites avant / après
                    Triple(bmp, scoreOf(srcW), scoreOf(bmp))
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
                CrashLog.log(getApplication<Application>(), "Mémoire insuffisante (mode ${_state.value.mode})", e)
                update { it.copy(processing = false, error = MEMORY_ERROR) }
            } catch (e: Throwable) {
                CrashLog.log(getApplication<Application>(), "Erreur de traitement (mode ${_state.value.mode})", e)
                update { it.copy(processing = false, error = e.message ?: "Erreur de traitement") }
            }
        }
    }

    /** Traitement IA (lent) : bruit -> tons -> mouvement -> Real-ESRGAN x4 par tuiles -> micro. */
    fun processAi(keepAlive: Boolean = false) {
        val src = _state.value.source ?: return
        val st = _state.value
        val app = getApplication<Application>()
        if (!EsrganUpscaler.isAvailable(app)) {
            update { it.copy(error = "Modèle IA absent de l'application (fichier ${EsrganUpscaler.MODEL_ASSET}). Relance la compilation GitHub pour le générer.") }
            return
        }
        job?.cancel()
        pauseRequested = false
        update { it.copy(processing = true, aiActive = true, error = null, paused = false, progress = 0f, stage = "Préparation…", startedAt = System.currentTimeMillis(), etaAt = 0L, notice = null) }
        if (keepAlive) KeepAliveService.start(app)
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
                    var base = if (st.mode == Mode.AUTO) {
                        // Mode Auto : diagnostic + corrections adaptées avant l'agrandissement IA
                        AutoRestore.run(orig, w, h, onStage = { s -> update { it.copy(stage = s) } },
                            checkCancelled = { ensureActive() }).pixels
                    } else prepare(orig, w, h, st.denoise, st.tone, st.nlm, "ai")
                    if (st.motion && st.mode != Mode.AUTO) {
                        update { it.copy(stage = "Défloutage directionnel…") }
                        base = MotionDeblur.autoDeblur(base, w, h, strength = 0.8f)
                        ensureActive()
                    }

                    // 2) super-résolution par tuiles (avec heure de fin estimée)
                    val tilesStart = System.currentTimeMillis()
                    val out = EsrganUpscaler.upscale(
                        app, base, w, h,
                        onTile = { done, total ->
                            val elapsed = System.currentTimeMillis() - tilesStart
                            val eta = tilesStart + elapsed * total / done
                            if (keepAlive) KeepAliveService.update(app, done, total, eta)
                            update {
                                it.copy(
                                    progress = done.toFloat() / total,
                                    stage = "IA (${EsrganUpscaler.backend}) : tuile $done / $total",
                                    etaAt = eta
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

                    // 3) micro-contraste final
                    var outBmp = out
                    if (st.micro) {
                        update { it.copy(stage = "Micro-contraste…") }
                        val (opx, ow) = ImageLoader.toPixels(out)
                        val fixed = EnhanceExtras.microContrast(opx, ow, out.height, 0.4f)
                        outBmp = ImageLoader.fromPixels(fixed, ow, out.height)
                        out.recycle()
                    }

                    // 4) scores comparables : mêmes dimensions réduites avant / après
                    val before = scoreOf(src)
                    val after = scoreOf(outBmp)
                    Triple(outBmp, before, after)
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
                CrashLog.log(getApplication<Application>(), "Mémoire insuffisante (mode ${_state.value.mode})", e)
                pauseRequested = false
                update { it.copy(processing = false, paused = false, progress = 0f, stage = "", error = MEMORY_ERROR) }
            } catch (e: Throwable) {
                CrashLog.log(getApplication<Application>(), "Erreur de traitement (mode ${_state.value.mode})", e)
                pauseRequested = false
                update { it.copy(processing = false, paused = false, progress = 0f, stage = "", error = "Erreur IA : " + (e.message ?: e.javaClass.simpleName)) }
            } finally {
                update { it.copy(aiActive = false) }
                if (keepAlive) KeepAliveService.stop(app)
            }
        }
    }

    /** Mode Auto : diagnostic de la photo, puis corrections adaptées (flou, bruit, JPEG, exposition). */
    fun processAuto() {
        val src = _state.value.source ?: return
        if (_state.value.mode != Mode.AUTO) return
        job?.cancel()
        update {
            it.copy(
                processing = true, error = null, progress = 0f, stage = "Analyse de la photo…",
                startedAt = System.currentTimeMillis(), etaAt = 0L, notice = null, autoSteps = emptyList()
            )
        }
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    val (px, w) = ImageLoader.toPixels(src)
                    val h = src.height
                    val diag = PhotoDiagnosis.analyse(px, w, h)
                    update { it.copy(diagnosis = diag, stage = "Traitement…") }
                    ensureActive()
                    val out = AutoRestore.run(px, w, h, diag,
                        onStage = { s -> update { it.copy(stage = s) } },
                        checkCancelled = { ensureActive() })
                    val bmp = ImageLoader.fromPixels(out.pixels, w, h)
                    Triple(bmp, out, Pair(scoreOf(src), scoreOf(bmp)))
                }
                update {
                    it.copy(
                        result = res.first, processing = false, stage = "",
                        autoSteps = res.second.steps,
                        scoreBefore = BlurDetector.displayScore(res.third.first),
                        scoreAfter = BlurDetector.displayScore(res.third.second)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                CrashLog.log(getApplication<Application>(), "Mémoire insuffisante (mode ${_state.value.mode})", e)
                update { it.copy(processing = false, error = MEMORY_ERROR) }
            } catch (e: Throwable) {
                CrashLog.log(getApplication<Application>(), "Erreur de traitement (mode ${_state.value.mode})", e)
                update { it.copy(processing = false, error = e.message ?: "Erreur de traitement") }
            }
        }
    }

    /** Bruit + tons, avec cache (valable tant que la source et les options ne changent pas). */
    private fun prepare(orig: IntArray, w: Int, h: Int, denoise: Boolean, tone: Boolean, nlm: Boolean, tag: String): IntArray {
        val key = "$tag-$w-$h-$denoise-$tone-$nlm"
        val cached = prepared
        if (cached != null && preparedKey == key) return cached
        var cur = orig
        if (denoise) cur = ImageEnhancer.denoise(cur, w, h, 0.6f)
        if (nlm) cur = NlmDenoise.denoise(cur, w, h, strength = 0.7f)
        if (tone) cur = ImageEnhancer.tone(cur, w, h, ImageEnhancer.ToneParams())
        if (cur === orig) cur = orig.copyOf()
        prepared = cur; preparedKey = key
        return cur
    }

    /** Version de [b] dont le plus grand côté ne dépasse pas [maxDim] (la même image si elle est déjà assez petite). */
    private fun limitSize(b: Bitmap, maxDim: Int): Bitmap {
        val l = maxOf(b.width, b.height)
        if (l <= maxDim) return b
        val k = maxDim.toFloat() / l
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
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
