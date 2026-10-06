package com.sarmat.perevodchik.phone

import android.Manifest
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
data class TextPiece(val box: Rect, val text: String, val lines: Int, var translation: String = "")

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
    var src by remember { mutableStateOf(if (vm.langB in CAMERA_LATIN) vm.langB else "en") }
    val tgt = if (src == vm.langA) vm.langB else vm.langA

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var pieces by remember { mutableStateOf<List<TextPiece>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
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
                    if (t.length < 2) null else TextPiece(box, t, max(1, b.lines.size))
                }
                if (found.isEmpty()) {
                    message = "Текст не найден — поднесите ближе и держите ровно"
                } else {
                    for (p in found) p.translation = vm.translateForCamera(p.text, src, tgt)
                    pieces = found
                }
            } catch (e: Exception) {
                message = "Ошибка: ${e.localizedMessage ?: ""}"
            } finally {
                working = false
            }
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
            .padding(horizontal = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Text("Перевод с камеры", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        // Направление: с какого языка читаем
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Текст на: ", color = Color.Gray)
            for (code in listOf(vm.langB, vm.langA, "en").distinct().filter { it in CAMERA_LATIN }) {
                val sel = code == src
                OutlinedButton(
                    onClick = { src = code; photo?.let { analyze(it) } },
                    colors = if (sel) ButtonDefaults.outlinedButtonColors(containerColor = ColorB, contentColor = Color.Black)
                    else ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.padding(end = 6.dp)
                ) { Text(Languages.name(code)) }
            }
        }
        Text("→ перевод на: ${Languages.name(tgt)}", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))

        if (!granted) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
            }
            return@Column
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
        ) {
            val shown = photo
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
                PhotoWithOverlay(shown, pieces)
            }
            if (working) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ColorB)
                }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp)) }

        if (photo != null && pieces.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.height(160.dp).padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(pieces) { p ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(p.text, color = Color.Gray, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.translation, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                    color = ColorA, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.speak(p.translation, tgt) }) { Text("🔊") }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            if (photo == null) {
                Button(
                    onClick = { shoot() },
                    enabled = !working,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = ColorB, contentColor = Color.Black),
                    modifier = Modifier.size(78.dp)
                ) { Text("📷", fontSize = 28.sp) }
            } else {
                Button(onClick = { photo = null; pieces = emptyList(); message = null }) {
                    Text("📷 Новое фото")
                }
            }
        }
    }
}

/** Фото, на котором поверх найденного текста написан перевод. */
@Composable
private fun PhotoWithOverlay(bmp: Bitmap, pieces: List<TextPiece>) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val scale = minOf(boxW / bmp.width, boxH / bmp.height)
        val imgW = bmp.width * scale
        val imgH = bmp.height * scale
        val left = (boxW - imgW) / 2
        val top = (boxH - imgH) / 2
        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            for (p in pieces) {
                val x = left + p.box.left * scale
                val y = top + p.box.top * scale
                val w = p.box.width() * scale
                val h = p.box.height() * scale
                val fontPx = (h / p.lines) * 0.62f
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x.toInt(), y.toInt()) }
                        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                        .background(Color(0xE6FFFFFF), RoundedCornerShape(4.dp))
                        .padding(2.dp)
                ) {
                    Text(
                        p.translation,
                        color = Color.Black,
                        fontSize = with(density) { fontPx.coerceIn(8f, 64f).toSp() },
                        lineHeight = with(density) { (fontPx * 1.1f).coerceIn(9f, 70f).toSp() },
                        fontWeight = FontWeight.SemiBold
                    )
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
