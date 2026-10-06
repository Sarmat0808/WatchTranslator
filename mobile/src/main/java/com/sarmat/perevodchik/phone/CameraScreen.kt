package com.sarmat.perevodchik.phone

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sarmat.perevodchik.Languages
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.max

/** Фрагмент текста на фото: где он, что написано и перевод. */
data class TextPiece(
    val box: Rect,
    val text: String,
    val lines: Int,
    var translation: String = "",
    var lang: String = ""
)

/** Языки, которые камера умеет читать офлайн (латиница). */
val CAMERA_LATIN = setOf(
    "en", "fi", "et", "sv", "de", "es", "fr", "it", "pt", "pl", "tr", "nl", "ro", "cs", "da",
    "hu", "vi", "id"
)

@Composable
fun CameraScreen(vm: PhoneViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    // Текст на фото — на языке собеседника (страны), перевод — на мой язык
    // "auto" — язык текста определяется сам; перевод всегда на мой язык
    var src by remember { mutableStateOf("auto") }
    val tgt = vm.langA
    var detected by remember { mutableStateOf<String?>(null) }

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var pieces by remember { mutableStateOf<List<TextPiece>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(0) }
    var fullScreen by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf(0) }
    var fontSize by remember { mutableStateOf(22) }
    var showOriginal by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }

    fun analyze(bmp: Bitmap) {
        working = true
        message = null
        scope.launch {
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                val found = result.textBlocks.mapNotNull { b ->
                    val box = b.boundingBox ?: return@mapNotNull null
                    val t = b.lines.joinToString(" ") { it.text }.trim()
                    if (t.count { it.isLetter() } < 3) null else TextPiece(box, t, max(1, b.lines.size))
                }.sortedWith(compareBy({ it.box.top / 40 }, { it.box.left }))
                if (found.isEmpty()) {
                    message = "Текст не найден — поднесите ближе и держите ровно"
                } else {
                    working = true
                    if (src == "auto") {
                        // Определяем язык каждого куска текста (вывеска может быть на двух языках)
                        val all = found.joinToString(" ") { it.text }
                        val main = detectLanguage(all, null) ?: vm.langB
                        detected = main
                        for (p in found) p.lang = detectLanguage(p.text, main) ?: main
                    } else {
                        detected = null
                        for (p in found) p.lang = src
                    }
                    // Сразу показываем фото, перевод появляется по кусочкам
                    tab = 0
                    pieces = found.map { it.copy(translation = "") }
                    done = 0
                    total = found.size
                    // Сначала длинные фразы (главное), потом короткие подписи
                    val order = found.indices.sortedByDescending { found[it].text.length }
                    for (i in order) {
                        val p = found[i]
                        val tr = when {
                            p.lang == tgt -> p.text
                            // короткие подписи — быстрым переводчиком, фразы — точным
                            p.text.split(' ').size <= 3 -> vm.translateFast(p.text, p.lang, tgt)
                            else -> vm.translateForCamera(p.text, p.lang, tgt)
                        }
                        pieces = pieces.toMutableList().also { it[i] = p.copy(translation = tr) }
                        done++
                        working = false
                    }
                }
            } catch (e: Exception) {
                message = "Ошибка: ${e.localizedMessage ?: ""}"
            } finally {
                working = false
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bmp = runCatching { loadBitmap(context, uri) }.getOrNull()
            if (bmp != null) {
                photo = bmp
                analyze(bmp)
            } else message = "Не удалось открыть фото"
        }
    }

    fun shoot() {
        working = true
        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = image.toUprightBitmap()
                image.close()
                photo = bmp
                analyze(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
                working = false
                message = "Камера: ${exception.localizedMessage ?: ""}"
            }
        })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 6.dp)
    ) {
        // Компактная шапка: назад + язык текста в одну строку — больше места для фото
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.size(40.dp)) { Text("←", fontSize = 22.sp) }
            androidx.compose.foundation.lazy.LazyRow(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                val options = listOf("auto") + listOf(vm.langB, "en", "fi", "sv", "de").distinct().filter { it in CAMERA_LATIN && it != tgt }
                items(options) { code ->
                    val sel = code == src
                    OutlinedButton(
                        onClick = { src = code; photo?.let { analyze(it) } },
                        colors = if (sel) ButtonDefaults.outlinedButtonColors(containerColor = ColorB, contentColor = Color.Black)
                        else ButtonDefaults.outlinedButtonColors(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.padding(end = 4.dp).height(36.dp)
                    ) { Text(if (code == "auto") "🔍 Авто" else Languages.name(code), fontSize = 14.sp) }
                }
            }
        }
        Text(
            buildString {
                if (src == "auto" && detected != null) append("${Languages.name(detected!!)} → ") else append("→ ")
                append(Languages.name(tgt))
                if (done < total) append("   · перевожу $done из $total")
            },
            color = if (done < total) ColorB else Color.Gray, fontSize = 12.sp, maxLines = 1
        )
        Spacer(Modifier.height(4.dp))

        if (!granted) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
            }
            return@Column
        }

        val shown = photo
        if (shown != null && pieces.isNotEmpty()) {
            // Результат: фото занимает почти весь экран
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) {
                    ZoomablePhoto(shown, pieces) { fullScreen = true }
                } else {
                    ResultText(vm, pieces, tgt, fontSize, showOriginal)
                }
            }
            // Одна нижняя панель: вкладки + действия
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val small = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                for ((i, label) in listOf("🖼", "📄").withIndex()) {
                    Button(
                        onClick = { tab = i },
                        contentPadding = small,
                        colors = if (tab == i) ButtonDefaults.buttonColors(containerColor = ColorA)
                        else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = Color.White),
                        modifier = Modifier.height(44.dp).padding(end = 4.dp)
                    ) { Text(if (i == 0) "$label Фото" else "$label Текст", fontSize = 14.sp) }
                }
                if (tab == 1) {
                    TextButton(onClick = { fontSize = (fontSize - 3).coerceAtLeast(14) }, contentPadding = small) { Text("A−", fontSize = 16.sp) }
                    TextButton(onClick = { fontSize = (fontSize + 3).coerceAtMost(44) }, contentPadding = small) { Text("A+", fontSize = 19.sp) }
                } else {
                    TextButton(onClick = { fullScreen = true }, contentPadding = small) { Text("⛶", fontSize = 22.sp) }
                }
                TextButton(onClick = { copyText(context, fullText(pieces, showOriginal)) }, contentPadding = small) { Text("📋", fontSize = 20.sp) }
                TextButton(onClick = {
                    if (tab == 1) shareText(context, fullText(pieces, showOriginal))
                    else sharePhoto(context, shown, pieces)
                }, contentPadding = small) { Text("📤", fontSize = 20.sp) }
                TextButton(onClick = { photo = null; pieces = emptyList(); message = null; tab = 0 }, contentPadding = small) {
                    Text("📷", fontSize = 22.sp)
                }
            }
            if (tab == 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "Скрыть оригинал" else "Показать оригинал") }
                    TextButton(onClick = { vm.speak(pieces.joinToString(". ") { it.translation }, tgt) }) { Text("🔊 Прочитать") }
                }
            }
            if (fullScreen) {
                FullScreenPhoto(shown, pieces) { fullScreen = false }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black)
            ) {
                if (shown == null) {
                    // Живое изображение с камеры
                    AndroidView(
                        factory = { ctx ->
                            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                            }, ContextCompat.getMainExecutor(ctx))
                            view
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(bitmap = shown.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                if (working) {
                    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ColorB)
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp)) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (shown == null) {
                    OutlinedButton(onClick = {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("🖼 Галерея") }
                    Button(
                        onClick = { shoot() },
                        enabled = !working,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = ColorB, contentColor = Color.Black),
                        modifier = Modifier.size(78.dp)
                    ) { Text("📷", fontSize = 28.sp) }
                    Spacer(Modifier.width(90.dp))
                } else {
                    Button(onClick = { photo = null; pieces = emptyList(); message = null }) { Text("📷 Новое фото") }
                }
            }
        }
    }
}

/** Снимок с камеры, повёрнутый правильно. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val raw = toBitmap()
    val deg = imageInfo.rotationDegrees
    if (deg == 0) return raw
    val m = Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}


/** Весь перевод одним текстом (по порядку чтения). */
fun fullText(pieces: List<TextPiece>, withOriginal: Boolean): String =
    pieces.joinToString("\n\n") { if (withOriginal) "${it.text}\n→ ${it.translation}" else it.translation }

/** Крупный текст перевода: можно выделить пальцем и скопировать любую часть. */
@Composable
private fun ResultText(vm: PhoneViewModel, pieces: List<TextPiece>, tgt: String, fontSize: Int, showOriginal: Boolean) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(pieces) { p ->
                Column {
                    if (showOriginal) {
                        Text(p.text, color = Color.Gray, fontSize = (fontSize * 0.7f).sp, lineHeight = (fontSize * 0.9f).sp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        p.translation,
                        color = Color.White,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.3f).sp
                    )
                }
            }
        }
    }
}

/** Фото с переводом поверх текста. Увеличение двумя пальцами, двойное касание — сброс. */
@Composable
private fun ZoomablePhoto(bmp: Bitmap, pieces: List<TextPiece>, rounded: Boolean = true, onExpand: (() -> Unit)? = null) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(if (rounded) 18.dp else 0.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (zoom > 1.1f) { zoom = 1f; pan = androidx.compose.ui.geometry.Offset.Zero } else zoom = 2.5f
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, p, z, _ ->
                    zoom = (zoom * z).coerceIn(1f, 6f)
                    pan = if (zoom == 1f) androidx.compose.ui.geometry.Offset.Zero else pan + p
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
        ) {
            val rendered = remember(bmp, pieces) { renderOverlay(bmp, pieces) }
            Image(
                bitmap = rendered.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (zoom == 1f) {
            Text(
                "Увеличьте двумя пальцами",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Перевод", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

/** Отправить текст в любое приложение: WhatsApp, Telegram, почта… */
private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Отправить перевод"))
}

/**
 * Рисует перевод прямо на фото: плашка закрывает исходный текст, шрифт подбирается так,
 * чтобы перевод поместился целиком (при нехватке места плашка растёт вниз).
 */
fun renderOverlay(bmp: Bitmap, pieces: List<TextPiece>): Bitmap {
    val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val bg = android.graphics.Paint().apply { color = android.graphics.Color.argb(242, 255, 255, 255) }
    val border = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(160, 30, 136, 229)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = (bmp.width / 600f).coerceAtLeast(1.5f)
    }
    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    for (p in pieces) {
        if (p.translation.isBlank()) continue
        val pad = (p.box.height() / p.lines * 0.12f).coerceAtLeast(2f)
        val w = (p.box.width() + 2 * pad).toInt().coerceAtLeast(24)
        val lineH = p.box.height().toFloat() / p.lines
        val minSize = (lineH * 0.42f).coerceAtLeast(9f)
        var size = lineH * 0.78f
        var layout: android.text.StaticLayout
        while (true) {
            paint.textSize = size
            layout = android.text.StaticLayout.Builder
                .obtain(p.translation, 0, p.translation.length, paint, (w - 2 * pad).toInt().coerceAtLeast(10))
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()
            if (layout.height <= p.box.height() + 2 * pad || size <= minSize) break
            size *= 0.92f
        }
        val left = p.box.left - pad
        val top = p.box.top - pad
        val h = maxOf(p.box.height() + 2 * pad, layout.height + 2 * pad)
        val r = android.graphics.RectF(left, top, left + w, top + h)
        canvas.drawRoundRect(r, pad * 1.5f, pad * 1.5f, bg)
        canvas.drawRoundRect(r, pad * 1.5f, pad * 1.5f, border)
        canvas.save()
        canvas.translate(left + pad, top + pad)
        layout.draw(canvas)
        canvas.restore()
    }
    return out
}

/** Отправить фото с нарисованным поверх переводом. */
private fun sharePhoto(context: Context, bmp: Bitmap, pieces: List<TextPiece>) {
    val out = renderOverlay(bmp, pieces)
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, "perevod.jpg")
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, fullText(pieces, false))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Отправить фото с переводом"))
}

/** Фото из галереи (уменьшенное, правильно повёрнутое). */
private fun loadBitmap(context: Context, uri: android.net.Uri): Bitmap {
    val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
    return android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val maxSide = maxOf(info.size.width, info.size.height)
        if (maxSide > 2400) {
            val k = 2400f / maxSide
            decoder.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
        }
        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
    }
}


private val languageId by lazy {
    com.google.mlkit.nl.languageid.LanguageIdentification.getClient(
        com.google.mlkit.nl.languageid.LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.3f)
            .build()
    )
}

/**
 * Офлайн-определение языка текста. Для коротких надписей (кнопки, адреса) доверяем
 * только уверенному ответу, иначе берём язык всего фото (fallback).
 */
suspend fun detectLanguage(text: String, fallback: String?): String? {
    val clean = text.replace(Regex("[0-9/:._%?=&#@-]+"), " ").trim()
    if (clean.count { it.isLetter() } < 4) return fallback
    return try {
        val options = languageId.identifyPossibleLanguages(clean).await()
        val best = options
            .map { (if (it.languageTag == "nb" || it.languageTag == "nn") "no" else it.languageTag) to it.confidence }
            .firstOrNull { (tag, _) -> tag in Languages.all && tag != "und" }
        when {
            best == null -> fallback
            fallback != null && clean.length < 25 && best.second < 0.7f -> fallback
            else -> best.first
        }
    } catch (e: Exception) {
        fallback
    }
}


/** Фото на весь экран (без шапки и кнопок) — удобно читать. */
@Composable
private fun FullScreenPhoto(bmp: Bitmap, pieces: List<TextPiece>, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ZoomablePhoto(bmp, pieces, rounded = false)
            Button(
                onClick = onClose,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xAA000000), contentColor = Color.White),
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(10.dp).size(52.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) { Text("✕", fontSize = 22.sp) }
        }
    }
}
