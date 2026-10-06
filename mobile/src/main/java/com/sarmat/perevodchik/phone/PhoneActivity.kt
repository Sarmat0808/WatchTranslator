package com.sarmat.perevodchik.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sarmat.perevodchik.Languages
import com.sarmat.perevodchik.UiText
import com.sarmat.perevodchik.OfflineVoice
import com.sarmat.perevodchik.VoicePack

val ColorA = Color(0xFF1E88E5)
val ColorB = Color(0xFFFFB300)

class PhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ColorA,
                    secondary = ColorB,
                    background = Color(0xFF101418),
                    surface = Color(0xFF171C21)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PhoneRoot()
                }
            }
        }
    }
}

@Composable
fun PhoneRoot(vm: PhoneViewModel = viewModel()) {
    var showSettings by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    KeepScreenOn(vm.anyDownloading || vm.recordingSide != null || vm.recognizing)
    when {
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(vm) { showSettings = false }
        }
        showCamera -> CameraScreen(vm) { showCamera = false }
        else -> MainScreen(vm, openCamera = { showCamera = true }) { showSettings = true }
    }
}

@Composable
fun KeepScreenOn(on: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
fun MainScreen(vm: PhoneViewModel, openCamera: () -> Unit, openSettings: () -> Unit) {
    val context = LocalContext.current
    var pendingSide by remember { mutableStateOf<Side?>(null) }
    var picking by remember { mutableStateOf<Side?>(null) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        val side = pendingSide
        if (ok && side != null) vm.startListening(side)
        else if (!ok) vm.status = "Нужно разрешение на микрофон"
    }

    fun listen(side: Side) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startListening(side) else {
            pendingSide = side
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    picking?.let { side ->
        LanguagePicker(vm, side) { picking = null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 12.dp)
    ) {
        // Заголовок
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Переводчик",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.toggleFace() }) {
                Text(if (vm.faceToFace) "👥 Лицом" else "💬 Чат", fontSize = 15.sp)
            }
            TextButton(onClick = openCamera) { Text("📷", fontSize = 22.sp) }
            TextButton(onClick = openSettings) { Text("⚙", fontSize = 22.sp) }
        }

        // Языки
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LangButton(vm.langA, ColorA, Modifier.weight(1f)) { picking = Side.A }
            TextButton(onClick = { vm.swap() }) { Text("⇄", fontSize = 24.sp) }
            LangButton(vm.langB, ColorB, Modifier.weight(1f)) { picking = Side.B }
        }

        // Подсказка о скачивании
        if (!vm.hasMic() || !vm.hasVoice() || !vm.isLangReady(vm.langA) || !vm.isLangReady(vm.langB) ||
            (vm.useHelsinki && vm.mtSupported && !vm.mtReady)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF263238)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { openSettings() }
            ) {
                Text(
                    "📥 Для работы без интернета скачайте голос, микрофон и языки → нажмите здесь",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 14.sp
                )
            }
        }

        vm.status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { vm.status = null }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (vm.faceToFace) FaceToFace(vm, ::listen) else ChatList(vm)
        }

        // Запись / распознавание
        if (!vm.faceToFace) when {
            vm.recordingSide != null -> RecordingBar(vm)
            vm.recognizing || vm.busy -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (vm.recognizing) "Распознаю…" else "Перевожу…")
            }
        }

        if (!vm.faceToFace) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                MicButton("Я · ${Languages.name(vm.langA)}", ColorA, Modifier.weight(1f)) {
                    listen(Side.A)
                }
                Spacer(Modifier.width(8.dp))
                MicButton("${Languages.name(vm.langB)}", ColorB, Modifier.weight(1f)) {
                    listen(Side.B)
                }
            }
            TypeRow(vm)
        }
    }
}

@Composable
fun LangButton(code: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(Languages.name(code), color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MicButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.height(72.dp)
    ) {
        Text("🎤  $label", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
    }
}

@Composable
fun TypeRow(vm: PhoneViewModel) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Написать (${Languages.name(vm.langA)})") },
            modifier = Modifier.weight(1f),
            maxLines = 3
        )
        Spacer(Modifier.width(6.dp))
        Button(onClick = {
            vm.translateTyped(text)
            text = ""
        }) { Text("➤", fontSize = 18.sp) }
    }
}

@Composable
fun RecordingBar(vm: PhoneViewModel) {
    val color = if (vm.recordingSide == Side.A) ColorA else ColorB
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size((40 + 30 * vm.micLevel).dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) { Text("🎤", fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Text("Говорите…", fontSize = 18.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.cancelListening() }) { Text("Отмена") }
        Button(onClick = { vm.finishListening() }) { Text("Готово") }
    }
}

@Composable
fun ChatList(vm: PhoneViewModel) {
    val state = rememberLazyListState()
    LaunchedEffect(vm.exchanges.size) {
        if (vm.exchanges.isNotEmpty()) state.animateScrollToItem(vm.exchanges.size - 1)
    }
    if (vm.exchanges.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Нажмите 🎤 и говорите", fontSize = 18.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Text(
                "Всё работает без интернета.\nПеревод сразу звучит вслух.",
                fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center
            )
        }
        return
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.exchanges) { e -> Bubble(vm, e) }
    }
}

@Composable
fun Bubble(vm: PhoneViewModel, e: Exchange) {
    val color = if (e.side == Side.A) ColorA else ColorB
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (e.side == Side.A) 0.dp else 24.dp,
                end = if (e.side == Side.A) 24.dp else 0.dp
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                (if (e.fromWatch) "⌚ " else "") + e.text,
                fontSize = 14.sp,
                color = Color.LightGray
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.translation,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.speak(e.translation, e.tgt) }) { Text("🔊", fontSize = 20.sp) }
            }
        }
    }
}

/** Телефон лежит между собеседниками: верхняя половина повёрнута к собеседнику. */
@Composable
fun FaceToFace(vm: PhoneViewModel, listen: (Side) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).rotate(180f)) {
            HalfPanel(vm, Side.B) { listen(Side.B) }
        }
        HorizontalDivider(thickness = 2.dp)
        Box(modifier = Modifier.weight(1f)) {
            HalfPanel(vm, Side.A) { listen(Side.A) }
        }
    }
}

@Composable
fun HalfPanel(vm: PhoneViewModel, side: Side, onMic: () -> Unit) {
    // Всё на этой половине — на языке того, кто с этой стороны
    val lang = if (side == Side.A) vm.langA else vm.langB
    val color = if (side == Side.A) ColorA else ColorB
    val last = vm.exchanges.lastOrNull()
    val mine = vm.activeSide == side
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                mine && vm.recordingSide == side -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size((44 + 34 * vm.micLevel).dp)
                            .clip(CircleShape)
                            .background(color),
                        contentAlignment = Alignment.Center
                    ) { Text("🎤", fontSize = 22.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text(UiText.listening(lang), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.finishListening() }) { Text(UiText.done(lang)) }
                }
                mine && (vm.recognizing || vm.busy) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), color = color)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (vm.recognizing) UiText.recognizing(lang) else UiText.translating(lang),
                        fontSize = 20.sp
                    )
                }
                last == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Languages.nativeName(lang), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
                    Spacer(Modifier.height(6.dp))
                    Text(UiText.tapMic(lang), fontSize = 16.sp, color = Color.Gray, textAlign = TextAlign.Center)
                }
                last.side != side -> Text(
                    last.translation,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    textAlign = TextAlign.Center
                )
                else -> Text(
                    last.text,
                    fontSize = 16.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
        MicButton(Languages.nativeName(lang), color, Modifier.fillMaxWidth()) { onMic() }
    }
}

@Composable
fun LanguagePicker(vm: PhoneViewModel, side: Side, onDone: () -> Unit) {
    val current = if (side == Side.A) vm.langA else vm.langB
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text("Закрыть") } },
        title = { Text(if (side == Side.A) "Мой язык" else "Язык собеседника") },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(Languages.all) { code ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.setLang(side, code)
                                onDone()
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            Languages.name(code),
                            fontSize = 17.sp,
                            fontWeight = if (code == current) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            buildString {
                                if (vm.isLangReady(code)) append("✓ ")
                                if (OfflineVoice.languages.contains(code)) append("🔊")
                            },
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun SettingsScreen(vm: PhoneViewModel, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Section("Офлайн-пакеты (скачать один раз по Wi‑Fi)") }
        item { PackRow(vm, VoicePack.TTS, "Голос: озвучка на 31 языке") }
        item { PackRow(vm, VoicePack.ASR_SMALL, "Микрофон: точное распознавание (рекомендую)") }
        item { PackRow(vm, VoicePack.ASR_TURBO, "Микрофон: максимальная точность, медленнее") }
        item {
            Text(
                "Если скачаны оба микрофона, используется самый точный. " +
                    "Часы с «Переводчиком» тоже будут пользоваться ими через Bluetooth.",
                fontSize = 13.sp, color = Color.Gray
            )
        }

        item { Section("Точный перевод (Helsinki)") }
        item { MtRow(vm) }
        item { ToggleRow("Использовать точный перевод", vm.useHelsinki) { vm.toggleHelsinki() } }

        item { Section("Голос") }
        item { ToggleRow("Сразу озвучивать перевод", vm.autoSpeak) { vm.toggleAutoSpeak() } }
        item { ToggleRow("Медленная речь", vm.slowSpeech) { vm.toggleSlow() } }
        item { ToggleRow("Офлайн-голос (вместо голоса телефона)", vm.useOfflineVoice) { vm.toggleOfflineVoice() } }
        item {
            OutlinedButton(onClick = { vm.nextVoice() }, modifier = Modifier.fillMaxWidth()) {
                Text("🎙 Голос №${vm.voiceId + 1} — нажмите, чтобы сменить")
            }
        }
        item { ToggleRow("Режим «лицом к лицу»", vm.faceToFace) { vm.toggleFace() } }

        item { Section("Языки перевода") }
        items(Languages.all) { code -> LangRow(vm, code) }

        item {
            OutlinedButton(onClick = { vm.clearHistory() }, modifier = Modifier.fillMaxWidth()) {
                Text("Очистить историю")
            }
        }
        item { Section("Лицензии") }
        item {
            Text(
                "Перевод: модели Helsinki-NLP OPUS-MT, Университет Хельсинки (CC-BY 4.0) и Google ML Kit.\n" +
                    "Распознавание речи: OpenAI Whisper (MIT) на движке sherpa-onnx (Apache 2.0).\n" +
                    "Голос: Supertone Supertonic-3 (OpenRAIL-M — запрещено использовать для обмана, " +
                    "выдачи себя за другого человека и иного вредного применения).\n" +
                    "Распознавание текста с камеры: Google ML Kit. Движок перевода: transformers.js и ONNX Runtime (Apache 2.0/MIT).\n" +
                    "Весь звук и все фото обрабатываются только на телефоне и никуда не отправляются.",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
fun Section(title: String) {
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ColorA, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun ToggleRow(title: String, value: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 16.sp)
        Switch(checked = value, onCheckedChange = { onToggle() })
    }
}

@Composable
fun PackRow(vm: PhoneViewModel, pack: VoicePack, what: String) {
    val installed = vm.packInstalled[pack] == true
    val progress = vm.packProgress[pack]
    var confirm by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pack.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("$what · ~${pack.approxMb} МБ", fontSize = 13.sp, color = Color.Gray)
                }
                when {
                    progress != null -> Unit
                    !installed -> Button(onClick = { vm.downloadPack(pack) }) { Text("Скачать") }
                    confirm -> TextButton(onClick = {
                        vm.deletePack(pack)
                        confirm = false
                    }) { Text("Удалить?", color = Color(0xFFE57373)) }
                    else -> TextButton(onClick = { confirm = true }) { Text("✓ Есть") }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(vm.packProgressText[pack] ?: "", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun LangRow(vm: PhoneViewModel, code: String) {
    val ready = vm.isLangReady(code)
    val loading = code in vm.downloadingLangs
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(Languages.name(code), fontSize = 16.sp, modifier = Modifier.weight(1f))
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(22.dp))
            ready && code != "en" -> TextButton(onClick = { vm.deleteLang(code) }) { Text("✓ удалить") }
            ready -> Text("✓ встроен", color = Color.Gray)
            else -> TextButton(onClick = { vm.downloadLang(code) }) { Text("⬇ скачать") }
        }
    }
}


@Composable
fun MtRow(vm: PhoneViewModel) {
    val progress = vm.mtProgress
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${Languages.name(vm.langA)} ⇄ ${Languages.name(vm.langB)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        when {
                            !vm.mtSupported -> "Для этой пары — перевод Google ML Kit"
                            vm.mtReady -> "Скачан, работает без интернета"
                            else -> "Точнее, чем ML Kit · ~110–450 МБ"
                        },
                        fontSize = 13.sp, color = Color.Gray
                    )
                }
                when {
                    !vm.mtSupported -> Unit
                    progress != null -> Unit
                    vm.mtReady -> Text("✓", fontSize = 20.sp, color = ColorA)
                    else -> Button(onClick = { vm.downloadMt() }) { Text("Скачать") }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(vm.mtProgressText, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}
