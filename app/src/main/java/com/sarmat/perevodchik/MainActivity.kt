package com.sarmat.perevodchik

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App(vm: TranslatorViewModel = viewModel()) {
    MaterialTheme {
        val nav = rememberSwipeDismissableNavController()
        KeepScreenOn(vm.anyPackDownloading || vm.recording || vm.recognizing)
        SwipeDismissableNavHost(navController = nav, startDestination = "main") {
            composable("main") {
                MainScreen(
                    vm = vm,
                    onPick = { forSource -> nav.navigate(if (forSource) "pick_src" else "pick_tgt") },
                    onModels = { nav.navigate("models") },
                    onHistory = { nav.navigate("history") },
                    onListen = { nav.navigate("listen") }
                )
            }
            composable("pick_src") { PickScreen(vm, forSource = true) { nav.popBackStack() } }
            composable("pick_tgt") { PickScreen(vm, forSource = false) { nav.popBackStack() } }
            composable("models") { ModelsScreen(vm) }
            composable("listen") { ListenScreen(vm) { nav.popBackStack() } }
            composable("history") { HistoryScreen(vm) { nav.popBackStack() } }
        }
    }
}

/** Общий каркас экрана со списком, который крутится пальцем/безелем. */
@Composable
fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) }
    ) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

@Composable
fun MainScreen(
    vm: TranslatorViewModel,
    onPick: (Boolean) -> Unit,
    onModels: () -> Unit,
    onHistory: () -> Unit,
    onListen: () -> Unit
) {
    val context = LocalContext.current

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.startListening()
            onListen()
        } else {
            vm.status = "Нужно разрешение на микрофон"
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) vm.translate(text)
        }
    }
    val keyboardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence("text")?.toString()
            if (!text.isNullOrBlank()) vm.translate(text)
        }
    }

    fun startSystemVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Languages.speechTag(vm.source))
            putExtra(RecognizerIntent.EXTRA_PROMPT, Languages.name(vm.source))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.status = "Распознавание речи недоступно — используйте «Написать»"
        }
    }

    fun startVoice() {
        if (vm.canListenOffline()) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                vm.startListening()
                onListen()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        startSystemVoice()
    }

    fun startKeyboard() {
        val input = RemoteInput.Builder("text")
            .setLabel(Languages.name(vm.source))
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        try {
            keyboardLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.status = "Клавиатура недоступна"
        }
    }

    fun openVoiceInstall() {
        try {
            context.startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    ListScreen {
        // Выбор языков: [RU] ⇄ [FI]
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactChip(
                    onClick = { onPick(true) },
                    label = { Text(Languages.short(vm.source), fontWeight = FontWeight.Bold) }
                )
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { vm.swapLanguages() },
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                    colors = ButtonDefaults.secondaryButtonColors()
                ) { Text("⇄", fontSize = 18.sp) }
                Spacer(Modifier.width(4.dp))
                CompactChip(
                    onClick = { onPick(false) },
                    label = { Text(Languages.short(vm.target), fontWeight = FontWeight.Bold) }
                )
            }
        }

        item {
            Chip(
                onClick = { startVoice() },
                label = { Text("Сказать") },
                secondaryLabel = { Text(Languages.name(vm.source) + " → " + Languages.name(vm.target)) },
                icon = { Text("🎤", fontSize = 20.sp) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = { startKeyboard() },
                label = { Text("Написать") },
                icon = { Text("⌨", fontSize = 20.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (!vm.ttsInstalled || !vm.asrInstalled) {
            item {
                Chip(
                    onClick = onModels,
                    label = { Text("Голос без интернета") },
                    secondaryLabel = {
                        Text(
                            when {
                                !vm.ttsInstalled && !vm.asrInstalled -> "Скачать голос и микрофон"
                                !vm.ttsInstalled -> "Скачать офлайн голос"
                                else -> "Скачать офлайн микрофон"
                            }
                        )
                    },
                    icon = { Text("📥", fontSize = 18.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (!vm.isReady(vm.source) || !vm.isReady(vm.target)) {
            item {
                Chip(
                    onClick = {
                        vm.download(vm.source)
                        vm.download(vm.target)
                    },
                    label = { Text("Скачать для офлайна") },
                    secondaryLabel = { Text("Один раз, по Wi‑Fi") },
                    icon = { Text("⬇", fontSize = 18.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (vm.busy || vm.speaking) {
            item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
        }

        vm.status?.let { msg ->
            item {
                Text(
                    msg,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.secondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }

        if (vm.missingVoice != null) {
            item {
                Chip(
                    onClick = { openVoiceInstall() },
                    label = { Text("Установить голос") },
                    icon = { Text("🔈", fontSize = 18.sp) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (vm.outputText.isNotEmpty()) {
            item {
                Text(
                    vm.inputText,
                    textAlign = TextAlign.Center,
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            item {
                Text(
                    vm.outputText,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 26.sp,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.speakLast() }) { Text("🔊", fontSize = 20.sp) }
                    Spacer(Modifier.width(10.dp))
                    // Собеседник отвечает: языки меняются местами и сразу включается микрофон
                    Button(
                        onClick = {
                            vm.swapLanguages()
                            startVoice()
                        },
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) { Text("↩🎤", fontSize = 16.sp) }
                }
            }
            item {
                Text("↩🎤 — ответ собеседника", fontSize = 11.sp, color = Color.Gray)
            }
        }

        item {
            Chip(
                onClick = { vm.toggleAutoSpeak() },
                label = { Text("Озвучивать сразу") },
                secondaryLabel = { Text(if (vm.autoSpeak) "Вкл" else "Выкл") },
                icon = { Text("🔊", fontSize = 16.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = { vm.toggleSlow() },
                label = { Text("Медленная речь") },
                secondaryLabel = { Text(if (vm.slowSpeech) "Вкл" else "Выкл") },
                icon = { Text("🐢", fontSize = 16.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (vm.ttsInstalled) {
            item {
                Chip(
                    onClick = { vm.toggleOfflineVoice() },
                    label = { Text("Офлайн голос") },
                    secondaryLabel = { Text(if (vm.useOfflineVoice) "Вкл" else "Выкл (голос часов)") },
                    icon = { Text("🗣", fontSize = 16.sp) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (vm.useOfflineVoice) {
                item {
                    Chip(
                        onClick = { vm.nextVoice() },
                        label = { Text("Голос №${vm.voiceId + 1}") },
                        secondaryLabel = { Text("Нажмите — следующий") },
                        icon = { Text("🎙", fontSize = 16.sp) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        item {
            Chip(
                onClick = { vm.toggleUsePhone() },
                label = { Text("Помощь телефона") },
                secondaryLabel = {
                    Text(
                        when {
                            !vm.usePhone -> "Выкл — всё на часах"
                            vm.phoneConnected -> "✓ Телефон рядом"
                            else -> "Вкл · телефон не найден"
                        }
                    )
                },
                icon = { Text("📱", fontSize = 16.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onHistory,
                label = { Text("История") },
                secondaryLabel = { Text("${vm.history.size} переводов") },
                icon = { Text("🕘", fontSize = 16.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onModels,
                label = { Text("Языки офлайн") },
                secondaryLabel = { Text("Скачано: ${vm.downloaded.size}") },
                icon = { Text("🌍", fontSize = 16.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun PickScreen(vm: TranslatorViewModel, forSource: Boolean, onDone: () -> Unit) {
    val current = if (forSource) vm.source else vm.target
    ListScreen {
        item {
            Text(
                if (forSource) "Я говорю на…" else "Перевести на…",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        items(Languages.all) { code ->
            Chip(
                onClick = {
                    vm.setLanguage(forSource, code)
                    vm.download(code)
                    onDone()
                },
                label = { Text(Languages.name(code)) },
                secondaryLabel = {
                    Text(if (vm.isReady(code)) "✓ офлайн" else "нужно скачать")
                },
                colors = if (code == current) ChipDefaults.primaryChipColors()
                else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ModelsScreen(vm: TranslatorViewModel) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    ListScreen {
        item { Text("Голос офлайн", fontWeight = FontWeight.Bold) }
        item { PackChip(vm, VoicePack.TTS, "Озвучка, 31 язык") }
        item { PackChip(vm, VoicePack.ASR, "Распознавание речи, ~60 языков") }
        item {
            Text(
                "Скачиваются один раз по Wi‑Fi. Держите часы на зарядке.",
                fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        item { Text("Языки перевода", fontWeight = FontWeight.Bold) }
        item {
            Chip(
                onClick = { listOf("ru", "fi", "bg").forEach { vm.download(it) } },
                label = { Text("Скачать RU + FI + BG") },
                secondaryLabel = { Text("≈ 30 МБ на язык, Wi‑Fi") },
                icon = { Text("⬇", fontSize = 18.sp) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        items(Languages.all) { code ->
            val ready = vm.isReady(code)
            val loading = code in vm.downloading
            Chip(
                onClick = {
                    when {
                        loading -> Unit
                        !ready -> vm.download(code)
                        confirmDelete == code -> {
                            vm.delete(code)
                            confirmDelete = null
                        }
                        else -> confirmDelete = code
                    }
                },
                label = { Text(Languages.name(code)) },
                secondaryLabel = {
                    Text(
                        when {
                            loading -> "⏳ скачивается…"
                            confirmDelete == code -> "Нажмите ещё раз — удалить"
                            ready -> "✓ скачан"
                            else -> "⬇ скачать"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = if (ready) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        vm.status?.let { msg ->
            item { Text(msg, fontSize = 12.sp, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
fun HistoryScreen(vm: TranslatorViewModel, onDone: () -> Unit) {
    ListScreen {
        item { Text("История", fontWeight = FontWeight.Bold) }
        if (vm.history.isEmpty()) {
            item { Text("Пока пусто", color = Color.Gray) }
        }
        items(vm.history.toList()) { h ->
            Chip(
                onClick = {
                    vm.useHistory(h)
                    onDone()
                },
                label = { Text(h.output, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = {
                    Text(
                        Languages.short(h.src) + "→" + Languages.short(h.tgt) + ": " + h.input,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (vm.history.isNotEmpty()) {
            item {
                CompactChip(
                    onClick = { vm.clearHistory() },
                    label = { Text("Очистить") }
                )
            }
        }
    }
}


@Composable
fun KeepScreenOn(on: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
fun PackChip(vm: TranslatorViewModel, pack: VoicePack, what: String) {
    var confirmDelete by remember { mutableStateOf(false) }
    val installed = if (pack == VoicePack.TTS) vm.ttsInstalled else vm.asrInstalled
    val progress = if (pack == VoicePack.TTS) vm.ttsProgress else vm.asrProgress
    val progressText = if (pack == VoicePack.TTS) vm.ttsProgressText else vm.asrProgressText
    Chip(
        onClick = {
            when {
                progress != null -> Unit
                !installed -> vm.downloadPack(pack)
                confirmDelete -> {
                    vm.deletePack(pack)
                    confirmDelete = false
                }
                else -> confirmDelete = true
            }
        },
        label = { Text(pack.title) },
        secondaryLabel = {
            Text(
                when {
                    progress != null -> "⏳ ${(progress * 100).toInt()}% · $progressText"
                    confirmDelete -> "Нажмите ещё раз — удалить"
                    installed -> "✓ $what"
                    else -> "⬇ $what · ~${pack.approxMb} МБ"
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        icon = { Text(if (pack == VoicePack.TTS) "🔊" else "🎤", fontSize = 18.sp) },
        colors = if (installed) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Экран записи: говорите, часы сами поймут, когда вы закончили. */
@Composable
fun ListenScreen(vm: TranslatorViewModel, onDone: () -> Unit) {
    LaunchedEffect(vm.recording, vm.recognizing) {
        if (!vm.recording && !vm.recognizing) onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (vm.recognizing) "Распознаю…" else "Говорите",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(Languages.name(vm.source), fontSize = 13.sp, color = Color.LightGray)
            Spacer(Modifier.size(10.dp))
            if (vm.recognizing) {
                CircularProgressIndicator(modifier = Modifier.size(56.dp))
            } else {
                val d = (56 + 50 * vm.micLevel).dp
                Box(
                    modifier = Modifier
                        .size(d)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935)),
                    contentAlignment = Alignment.Center
                ) { Text("🎤", fontSize = 26.sp) }
            }
            Spacer(Modifier.size(10.dp))
            if (vm.recording) {
                Row {
                    CompactChip(
                        onClick = { vm.cancelListening() },
                        label = { Text("Отмена") },
                        colors = ChipDefaults.secondaryChipColors()
                    )
                    Spacer(Modifier.width(6.dp))
                    CompactChip(
                        onClick = { vm.finishListening() },
                        label = { Text("Готово") }
                    )
                }
            }
        }
    }
}
