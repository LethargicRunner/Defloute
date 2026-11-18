package com.lethargicrunner.defloute

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : ComponentActivity() {

    /** Même instance que celle de l'écran Déflouter (ViewModel rattaché à l'activité). */
    private val deblurVm: DeblurViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientationLock(this, AppSettings.lockRotation(this))
        setContent {
            DeflouteTheme {
                var tab by remember { mutableIntStateOf(0) }
                var showHelp by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                Scaffold(
                    topBar = { AppTopBar(onHelp = { showHelp = true }, onSettings = { showSettings = true }) },
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
                if (showSettings) SettingsDialog(
                    onClose = { showSettings = false },
                    onLockChanged = { applyOrientationLock(this@MainActivity, it) }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Écran en veille ou retour à l'accueil : applique le réglage choisi (pause, annulation…).
        deblurVm.onAppHidden(AppSettings.bgPolicy(this))
    }

    override fun onStart() {
        super.onStart()
        deblurVm.onAppVisible()
    }
}

// ------------------------------------------------------------------ Thème (teal, comme l'icône)

object DeflouteColors {
    val Teal = Color(0xFF00897B)
    val TealDark = Color(0xFF00695C)
    val TealLight = Color(0xFF4DB6AC)
    val Amber = Color(0xFFFFC107)
    val Surface = Color(0xFF101417)
    val SurfaceCard = Color(0xFF1A2126)
}

@Composable
fun DeflouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DeflouteColors.Teal,
            onPrimary = Color.White,
            primaryContainer = DeflouteColors.TealDark,
            onPrimaryContainer = Color.White,
            secondary = DeflouteColors.TealLight,
            tertiary = DeflouteColors.Amber,
            background = DeflouteColors.Surface,
            surface = DeflouteColors.Surface,
            surfaceVariant = DeflouteColors.SurfaceCard,
            surfaceContainer = DeflouteColors.SurfaceCard,
            surfaceContainerHigh = DeflouteColors.SurfaceCard
        ),
        shapes = Shapes(
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp)
        ),
        content = content
    )
}

// ------------------------------------------------------------------ Barre du haut + mode d'emploi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(onHelp: () -> Unit, onSettings: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Version de l'application installée (permet de vérifier quelle compilation tourne sur le téléphone).
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Défloute", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("v$version", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = DeflouteColors.TealDark, titleContentColor = Color.White),
        actions = {
            IconButton(onClick = onSettings) {
                Text("⚙", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
            IconButton(onClick = onHelp) {
                Text("?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
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
                        "2. Le mode « Auto » (par défaut) analyse la photo (flou, bruit, JPEG, exposition), affiche son diagnostic et applique seul les bonnes corrections. « Manuel » et « IA » donnent la main sur chaque réglage.\n" +
                        "   Autres modes :\n" +
                        "• Manuel : rapide (quelques secondes), le résultat se met à jour quand tu bouges les réglages.\n" +
                        "• IA Real-ESRGAN : plus lent (1 à 5 minutes) mais bien plus détaillé. Appuie sur « Lancer l'amélioration IA » ; tu peux mettre en pause, reprendre ou annuler à tout moment.\n" +
                        "3. Compare : le curseur au milieu de l'image montre l'AVANT à gauche et l'APRÈS à droite, glisse-le où tu veux. La vue « Côte à côte » montre les deux versions zoomées en même temps.\n" +
                        "4. Appuie sur « Galerie » (dossier Pictures/Defloute, sans rien choisir) ou « Dossier… » pour choisir où enregistrer : la photo améliorée est ajoutée sans jamais écraser l'original, en JPEG ou en PNG sans perte. Le nom du fichier contient l'heure de lancement du traitement."
                )
                HelpSection(
                    "Zoom et loupe",
                    "• En vue « Avant » ou « Après », le curseur « Zoom » agrandit la vue (ou pince avec deux doigts).\n" +
                        "• En mode zoom, glisse un doigt pour te déplacer dans l'image, double-tape pour revenir à 100 %.\n" +
                        "• Active « Loupe » : glisse ton doigt sur l'image, une loupe circulaire agrandit la zone sous le doigt (pratique pour juger la netteté des détails)."
                )
                HelpSection(
                    "Le mode Auto",
                    "L'application mesure elle-même le bruit, la compression JPEG, le flou (flou de bougé avec sa direction et sa longueur, ou flou de mise au point) et l'exposition, puis n'applique que les corrections utiles.\n" +
                        "• Si la photo est nette et correcte, elle n'est pas modifiée.\n" +
                        "• Le défloutage est évité quand la compression JPEG est forte : il ferait ressortir les blocs.\n" +
                        "• Le diagnostic et les étapes appliquées sont affichés sous l'image.\n" +
                        "• « Compléter avec l'IA » enchaîne l'agrandissement IA sur la photo déjà corrigée ; l'app te le conseille quand la photo est abîmée (JPEG marqué, flou important, bruit fort)."
                )
                HelpSection(
                    "Les réglages",
                    "• Force : intensité de la netteté (mode Manuel). Le bouton « Auto » la règle selon le flou détecté. Au-delà de 70 %, des halos peuvent apparaître.\n" +
                        "• Upscale x2 : double la taille de l'image avant le traitement (mode Manuel).\n" +
                        "• Réduire le bruit : adoucit le grain sans effacer les contours (filtre bilatéral).\n" +
                        "• Bruit fort (NLM) : filtre non-local means, plus lent mais bien plus efficace sur le gros grain des photos de nuit. Prévu pour les images jusqu'à ~1024 px.\n" +
                        "• Flou de mouvement : détecte la direction du bougé (photo prise en bougeant) et la déconvolue. Désactivé automatiquement si aucune direction dominante n'est trouvée.\n" +
                        "• Micro-contraste : passe finale très douce qui redonne du relief aux micro-détails.\n" +
                        "• Corriger lumière et contraste : étire les contrastes, éclaircit les ombres et tasse un peu les zones très claires.\n" +
                        "• Netteté : le score avant → après. Plus il est élevé, plus l'image est nette."
                )
                HelpSection(
                    "Pause et reprise (mode IA)",
                    "Pendant le traitement IA, « Pause » suspend le travail entre deux tuiles (rien n'est perdu), « Reprendre » continue, et « Annuler » arrête définitivement. Pratique quand le téléphone chauffe ou que la batterie baisse."
                )
                HelpSection(
                    "Onglet « Scanner »",
                    "Choisis un dossier : toutes les images (sous-dossiers compris) sont analysées et les plus floues apparaissent en premier. Les deux curseurs règlent la sévérité :\n" +
                        "• Seuil score : en dessous, l'image est jugée floue.\n" +
                        "• Seuil ratio : repère les images floues mais bruitées. Baisse-le pour être plus sévère.\n" +
                        "Le scan ne modifie ni ne supprime jamais tes photos."
                )
                HelpSection(
                    "Réglages ⚙",
                    "• Verrouiller la rotation : l'écran garde sa position actuelle, même si tu tournes le téléphone. Décoche pour la libérer.\n" +
                        "• Quand l'app passe en arrière-plan ou que l'écran s'éteint pendant un traitement :\n" +
                        "  – Continuer : le traitement se poursuit grâce à une notification (consomme de la batterie) ;\n" +
                        "  – Mettre en pause (par défaut) : il s'arrête et reprend à ton retour, rien n'est perdu ;\n" +
                        "  – Annuler : le traitement est abandonné automatiquement ;\n" +
                        "  – Ne rien faire : Android peut arrêter l'application, et le travail est alors perdu."
                )
                HelpSection(
                    "Ce qu'il faut savoir",
                    "• Aucune méthode ne retrouve un détail complètement perdu : l'IA le réinvente de façon plausible. Un visage ou un texte très flou peut sembler net mais être légèrement différent de la réalité.\n" +
                        "• Les zones entièrement blanches (surexposées) ou noires ne se récupèrent pas : un avertissement ⚠ s'affiche quand elles dépassent 8 % de l'image.\n" +
                        "• Pendant le traitement IA, l'heure de lancement et l'heure de fin estimée sont affichées.\n" +
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
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(14.dp))
}

// ------------------------------------------------------------------ Comparateur avant/après à curseur

/**
 * L'AVANT est affiché à gauche du curseur, l'APRÈS à droite. Glisse la poignée
 * (ou tape ailleurs sur l'image) pour déplacer la frontière.
 */
@Composable
fun CompareSlider(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    var split by remember { mutableFloatStateOf(0.5f) }

    Box(modifier) {
        Image(
            bitmap = after.asImageBitmap(),
            contentDescription = "Après",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            bitmap = before.asImageBitmap(),
            contentDescription = "Avant",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                }
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        split = (change.position.x / size.width).coerceIn(0.02f, 0.98f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        split = (pos.x / size.width).coerceIn(0.02f, 0.98f)
                    }
                }
        ) {
            val x = size.width * split
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
            drawCircle(Color.White, radius = 22f, center = Offset(x, size.height / 2f))
            drawCircle(DeflouteColors.Teal, radius = 17f, center = Offset(x, size.height / 2f))
            drawLine(Color.White, Offset(x - 8f, size.height / 2f), Offset(x - 3f, size.height / 2f), strokeWidth = 3f)
            drawLine(Color.White, Offset(x + 3f, size.height / 2f), Offset(x + 8f, size.height / 2f), strokeWidth = 3f)
        }
        Text(
            "AVANT",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Text(
            "APRÈS",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ------------------------------------------------------------------ Côte à côte (zoom synchronisé)

@Composable
fun SideBySide(before: Bitmap, after: Bitmap, zoom: Float, onZoomChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier) {
        ZoomableImage(
            bitmap = before, contentDescription = "Avant",
            zoom = zoom, onZoomChange = onZoomChange, loupeEnabled = false,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        Spacer(Modifier.width(2.dp).background(Color(0xFF0A0D0F)))
        ZoomableImage(
            bitmap = after, contentDescription = "Après",
            zoom = zoom, onZoomChange = onZoomChange, loupeEnabled = false,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

// ------------------------------------------------------------------ Zoom + loupe

/**
 * Image zoomable : pincement à deux doigts ou curseur de zoom, glissement pour se déplacer,
 * double-tap pour revenir à 100 %. Mode loupe : une loupe circulaire suit le doigt.
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    loupeEnabled: Boolean
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    var loupePos by remember { mutableStateOf<Offset?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val loupeRadiusPx = with(density) { 72.dp.toPx() }
    val loupeZoom = 3f

    Box(modifier) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .clip(RectangleShape)
                .then(
                    if (loupeEnabled) Modifier.pointerInput(image) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            loupePos = down.position
                            drag(down.id) { loupePos = it.position }
                            loupePos = null
                        }
                    } else Modifier.pointerInput(image) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            onZoomChange((zoom * gestureZoom).coerceIn(1f, 8f))
                            if (zoom > 1f || gestureZoom > 1f) offset += pan
                        }
                    }
                )
                .graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = offset.x; translationY = offset.y
                }
                .pointerInput(image) {
                    detectTapGestures(onDoubleTap = {
                        onZoomChange(1f)
                        offset = Offset.Zero
                    })
                }
        )

        // Loupe circulaire au-dessus de l'image
        loupePos?.let { pos ->
            Canvas(Modifier.fillMaxSize()) {
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                if (viewSize.width == 0 || viewSize.height == 0) return@Canvas
                val k = min(viewSize.width / bw, viewSize.height / bh)
                val dw = bw * k
                val dh = bh * k
                val ox = (viewSize.width - dw) / 2f + offset.x
                val oy = (viewSize.height - dh) / 2f + offset.y
                val bx = (pos.x - ox) / (k * zoom)
                val by = (pos.y - oy) / (k * zoom)
                val srcHalf = loupeRadiusPx / (loupeZoom * k * zoom)
                val sx = (bx - srcHalf).toInt().coerceIn(0, (bw - 2 * srcHalf).toInt().coerceAtLeast(0))
                val sy = (by - srcHalf).toInt().coerceIn(0, (bh - 2 * srcHalf).toInt().coerceAtLeast(0))
                val sSize = (2 * srcHalf).toInt().coerceAtLeast(1)
                val cx = pos.x - loupeRadiusPx
                val cy = pos.y - loupeRadiusPx
                val d = loupeRadiusPx * 2f
                drawCircle(color = Color.White, radius = loupeRadiusPx + 3f, center = Offset(pos.x, pos.y))
                clipCircle(Offset(pos.x, pos.y), loupeRadiusPx) {
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(sx, sy),
                        srcSize = IntSize(sSize, sSize),
                        dstOffset = IntOffset(cx.toInt(), cy.toInt()),
                        dstSize = IntSize(d.toInt(), d.toInt())
                    )
                }
                drawCircle(
                    color = DeflouteColors.TealLight,
                    radius = loupeRadiusPx,
                    center = Offset(pos.x, pos.y),
                    style = Stroke(width = 4f)
                )
            }
        }

        // Indicateur de zoom + bouton reset
        if (zoom > 1.01f && !loupeEnabled) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${(zoom * 100).toInt()} %", style = MaterialTheme.typography.labelSmall,
                    color = Color.White, modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                Spacer(Modifier.width(6.dp))
                SmallFloatingActionButton(onClick = { onZoomChange(1f); offset = Offset.Zero }) {
                    Text("⟲", color = Color.White)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.clipCircle(
    center: Offset,
    radius: Float,
    block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    val path = androidx.compose.ui.graphics.Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
    }
    clipPath(path) { block() }
}

// ------------------------------------------------------------------ Déflouter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeblurScreen(vm: DeblurViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.load(it) }
    }
    val isAi = s.mode == DeblurViewModel.Mode.AI
    val isAuto = s.mode == DeblurViewModel.Mode.AUTO
    /** Interface de suivi IA (progression, pause) : en mode IA, ou quand l'IA est lancée depuis le mode Auto. */
    val showAiUi = isAi || s.aiActive
    val appCtx = vm.getApplication<android.app.Application>()
    // Autorisation d'afficher la notification (Android 13+) : demandée seulement si « Continuer en arrière-plan ».
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        vm.processAi(keepAlive = AppSettings.bgPolicy(appCtx) == AppSettings.BgPolicy.CONTINUE)
    }
    val startAi: () -> Unit = {
        val keep = AppSettings.bgPolicy(appCtx) == AppSettings.BgPolicy.CONTINUE
        if (keep && Build.VERSION.SDK_INT >= 33 &&
            appCtx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.processAi(keepAlive = keep)
        }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var loupe by remember { mutableStateOf(false) }
    var savePng by remember { mutableStateOf(false) }
    // 0 = comparateur à curseur, 1 = avant, 2 = après, 3 = côte à côte
    var viewMode by remember { mutableIntStateOf(0) }
    var savedMsg by remember { mutableStateOf<String?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { saveUri ->
            val ctx = vm.getApplication<android.app.Application>()
            s.result?.let { bmp ->
                val done = ImageSaver.saveToTree(ctx, saveUri, bmp, "photo", png = savePng, stamp = ImageSaver.stampOf(s.startedAt))
                savedMsg = if (done != null) "Enregistré dans le dossier choisi." else "Échec de l'enregistrement dans ce dossier."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- choix du mode (segmented)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = isAuto, enabled = !s.processing,
                onClick = { vm.setMode(DeblurViewModel.Mode.AUTO) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
            ) { Text("Auto") }
            SegmentedButton(
                selected = s.mode == DeblurViewModel.Mode.CLASSIC, enabled = !s.processing,
                onClick = { vm.setMode(DeblurViewModel.Mode.CLASSIC) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
            ) { Text("Manuel") }
            SegmentedButton(
                selected = isAi, enabled = !s.processing,
                onClick = { vm.setMode(DeblurViewModel.Mode.AI) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
            ) { Text("IA") }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = { pickImage.launch(arrayOf("image/*")) }, enabled = !s.processing) {
            Text("Choisir une image")
        }

        s.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        s.notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }

        if (s.source != null && (s.clipWhite > 0.08f || s.clipBlack > 0.08f)) {
            Spacer(Modifier.height(8.dp))
            val parts = buildList {
                if (s.clipWhite > 0.08f) add("${(s.clipWhite * 100).toInt()} % de l'image est blanc pur (surexposé)")
                if (s.clipBlack > 0.08f) add("${(s.clipBlack * 100).toInt()} % est noir pur")
            }
            Text(
                "⚠ " + parts.joinToString(" et ") + " : ces zones ne contiennent plus aucune information, ni le mode classique ni l'IA ne pourront les récupérer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(Modifier.height(12.dp))

        val sourceBmp = s.source
        val resultBmp = s.result
        if (sourceBmp != null) {
            val canCompare = resultBmp != null && !s.processing
            val showCompare = viewMode == 0 && canCompare
            val showSideBySide = viewMode == 3 && canCompare

            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    showCompare -> CompareSlider(
                        before = sourceBmp,
                        after = resultBmp,
                        modifier = Modifier.fillMaxWidth().height(380.dp).background(Color(0xFF0A0D0F))
                    )
                    showSideBySide -> SideBySide(
                        before = sourceBmp,
                        after = resultBmp,
                        zoom = zoom,
                        onZoomChange = { zoom = it },
                        modifier = Modifier.fillMaxWidth().height(380.dp).background(Color(0xFF0A0D0F))
                    )
                    else -> {
                        val bmp = if (viewMode == 2 && resultBmp != null) resultBmp else sourceBmp
                        ZoomableImage(
                            bitmap = bmp,
                            contentDescription = if (viewMode == 2) "Après" else "Avant",
                            zoom = zoom,
                            onZoomChange = { zoom = it },
                            loupeEnabled = loupe,
                            modifier = Modifier.fillMaxWidth().height(380.dp).background(Color(0xFF0A0D0F))
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Sélecteur de vue + loupe
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = viewMode == 0, enabled = canCompare, onClick = { viewMode = 0 }, label = { Text("Comparer") })
                Spacer(Modifier.width(5.dp))
                FilterChip(selected = viewMode == 1, onClick = { viewMode = 1 }, label = { Text("Avant") })
                Spacer(Modifier.width(5.dp))
                FilterChip(selected = viewMode == 2, enabled = resultBmp != null, onClick = { viewMode = 2 }, label = { Text("Après") })
                Spacer(Modifier.width(5.dp))
                FilterChip(selected = viewMode == 3, enabled = canCompare, onClick = { viewMode = 3 }, label = { Text("2 vues") })
                Spacer(Modifier.width(5.dp))
                FilterChip(
                    selected = loupe,
                    onClick = { loupe = !loupe; if (loupe) { zoom = 1f; if (viewMode != 1 && viewMode != 2) viewMode = 1 } },
                    label = { Text(if (loupe) "Loupe ON" else "Loupe") }
                )
            }

            when {
                showCompare -> Text(
                    "Glisse le curseur sur l'image : AVANT à gauche, APRÈS à droite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                showSideBySide -> {
                    Text(
                        "Zoom synchronisé sur les deux vues : ${(zoom * 100).toInt()} %",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..8f)
                }
                loupe -> Text(
                    "Glisse le doigt sur l'image : la loupe agrandit la zone sous le doigt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                else -> {
                    Text("Zoom : ${(zoom * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..8f)
                    Text(
                        "Astuce : pince aussi l'image à deux doigts, double-tape pour revenir à 100 %.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (resultBmp != null) {
                Spacer(Modifier.height(4.dp))
                Text("Netteté : ${s.scoreBefore} → ${s.scoreAfter}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- réglages
        SettingsCard {
            if (!isAuto) {
                SwitchRow("Réduire le bruit", s.denoise, enabled = !s.processing) { vm.toggleDenoise() }
                SwitchRow("Bruit fort (NLM, plus lent)", s.nlm, enabled = !s.processing) { vm.toggleNlm() }
                SwitchRow("Flou de mouvement", s.motion, enabled = !s.processing) { vm.toggleMotion() }
                SwitchRow("Micro-contraste", s.micro, enabled = !s.processing) { vm.toggleMicro() }
                SwitchRow("Corriger lumière et contraste", s.tone, enabled = !s.processing) { vm.toggleTone() }
            }

            if (isAuto) {
                AutoPanel(s, onAi = { startAi() })
            } else if (s.mode == DeblurViewModel.Mode.CLASSIC) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Force : ${(s.strength * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.autoStrength() }, enabled = s.source != null && !s.processing) {
                        Text("Auto")
                    }
                }
                Slider(value = s.strength, onValueChange = { vm.setStrength(it) }, valueRange = 0f..1f)
                SwitchRow("Upscale x2", s.upscale, enabled = !s.processing) { vm.toggleUpscale() }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "L'IA agrandit l'image 4 fois (1024 px max en entrée) et dessine les détails manquants. Compte 1 à 5 minutes selon le téléphone.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { startAi() }, enabled = s.source != null && !s.processing) {
                    Text("Lancer l'amélioration IA")
                }
            }
        }

        // --- progression
        Spacer(Modifier.height(8.dp))
        if (s.processing) {
            if (showAiUi && s.progress > 0f) {
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            if (s.stage.isNotEmpty()) Text(s.stage, style = MaterialTheme.typography.bodySmall)
            if (showAiUi && s.startedAt > 0L) {
                val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
                val eta = if (s.etaAt > 0L) " · fin estimée ≈ " + hm.format(Date(s.etaAt)) else ""
                Text("Lancé à " + hm.format(Date(s.startedAt)) + eta, style = MaterialTheme.typography.bodySmall)
            }
            if (showAiUi) {
                Spacer(Modifier.height(4.dp))
                Row {
                    Button(onClick = { if (s.paused) vm.resume() else vm.pause() }) {
                        Text(if (s.paused) "Reprendre" else "Pause")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.cancel() }) { Text("Annuler") }
                }
            }
        }

        if (s.result != null && !s.processing) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !savePng, onClick = { savePng = false }, label = { Text("JPEG") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = savePng, onClick = { savePng = true }, label = { Text("PNG (sans perte)") })
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ImageSaver.galleryAvailable) {
                    Button(onClick = {
                        val ctx = vm.getApplication<android.app.Application>()
                        s.result?.let { bmp ->
                            val done = ImageSaver.saveToGallery(ctx, bmp, "photo", png = savePng, stamp = ImageSaver.stampOf(s.startedAt))
                            savedMsg = if (done != null) "Enregistré dans la galerie (Pictures/Defloute)." else "Échec de l'enregistrement."
                        }
                    }) { Text("Galerie") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Dossier…") }
                } else {
                    Button(onClick = { pickFolder.launch(null) }) { Text("Enregistrer") }
                }
            }
            savedMsg?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

/** Mode Auto : affiche le diagnostic de la photo, ce qui a été appliqué, et propose de compléter avec l'IA. */
@Composable
private fun AutoPanel(s: DeblurViewModel.State, onAi: () -> Unit) {
    val d = s.diagnosis
    Text("Diagnostic automatique", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    if (d == null) {
        Text(
            if (s.source == null) "Choisis une image : l'analyse et le traitement sont entièrement automatiques."
            else "Analyse en cours…",
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        d.summary().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        Text("Traitement appliqué", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        if (s.autoSteps.isNotEmpty()) {
            s.autoSteps.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
        } else if (!s.processing) {
            Text("Aucune correction nécessaire : la photo est laissée telle quelle.", style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(10.dp))
    Button(onClick = onAi, enabled = s.source != null && !s.processing) {
        Text("Compléter avec l'IA (×4, plus lent)")
    }
    Text(
        "L'IA reprend la photo corrigée et redessine les détails. Compte 1 à 5 minutes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
        Button(onClick = { pickFolder.launch(null) }, enabled = !s.scanning) {
            Text(if (s.scanning) "Scan en cours…" else "Choisir un dossier")
        }
        if (s.scanning && s.total > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { s.progress.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
            Text("${s.progress}/${s.total}", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        SettingsCard {
            Text("Sévérité de la détection", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text("Seuil score : ${s.settings.threshold.toInt()}", style = MaterialTheme.typography.bodySmall)
            Slider(value = s.settings.threshold.toFloat(), onValueChange = { vm.setThreshold(it) }, valueRange = 0f..500f)
            Text("Seuil ratio : ${"%.2f".format(s.settings.ratioThreshold)}", style = MaterialTheme.typography.bodySmall)
            Slider(value = s.settings.ratioThreshold, onValueChange = { vm.setRatio(it) }, valueRange = 0f..1.5f)
        }

        Spacer(Modifier.height(12.dp))
        val sorted = s.items.sortedWith(
            compareByDescending<ImageScanner.ScanItem> { it.isBlurry(s.settings) }.thenBy { it.fineScore }
        )
        sorted.forEach { item ->
            val blurry = item.isBlurry(s.settings)
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(
                            if (blurry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
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
}

// ------------------------------------------------------------------ Réglages

/** Verrouille l'écran dans sa position actuelle (ou libère la rotation). */
fun applyOrientationLock(activity: Activity, lock: Boolean) {
    activity.requestedOrientation =
        if (lock) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

@Composable
fun SettingsDialog(onClose: () -> Unit, onLockChanged: (Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var lock by remember { mutableStateOf(AppSettings.lockRotation(ctx)) }
    var policy by remember { mutableStateOf(AppSettings.bgPolicy(ctx)) }
    val choices = listOf(
        AppSettings.BgPolicy.CONTINUE to "Continuer en arrière-plan (notification, consomme de la batterie)",
        AppSettings.BgPolicy.PAUSE to "Mettre en pause, reprendre au retour (recommandé)",
        AppSettings.BgPolicy.CANCEL to "Annuler automatiquement le traitement",
        AppSettings.BgPolicy.NOTHING to "Ne rien faire (Android peut arrêter l'app)"
    )
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } },
        title = { Text("Réglages") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = lock, onCheckedChange = {
                        lock = it
                        AppSettings.setLockRotation(ctx, it)
                        onLockChanged(it)
                    })
                    Spacer(Modifier.width(10.dp))
                    Text("Verrouiller la rotation de l'écran")
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Si l'app passe en arrière-plan ou si l'écran se met en veille pendant un traitement :",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                choices.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            policy = value
                            AppSettings.setBgPolicy(ctx, value)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = policy == value, onClick = {
                            policy = value
                            AppSettings.setBgPolicy(ctx, value)
                        })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    )
}
