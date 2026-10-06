package com.sarmat.perevodchik.phone

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Экран «Часы»: установка на часы, связь и синхронизация. */
@Composable
fun WatchScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<WatchInfo?>(null) }
    var link by remember { mutableStateOf(WatchLink.linkEnabled(ctx)) }
    var sync by remember { mutableStateOf(WatchLink.syncEnabled(ctx)) }
    val fromPlay = remember { WatchLink.fromPlay(ctx) }

    // Установка
    var step by remember { mutableStateOf(0) } // 0 — нет, 1 — инструкция/код, 2 — идёт установка
    var code by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var progressText by remember { mutableStateOf("") }
    var log by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch { info = WatchLink.status(ctx) }
    }
    LaunchedEffect(Unit) { refresh() }

    fun parseHost(): Pair<String?, Int?> {
        val t = manualHost.trim()
        if (!t.contains(':')) return null to null
        return t.substringBefore(':') to t.substringAfter(':').toIntOrNull()
    }

    fun doInstall(pairFirst: Boolean) {
        step = 2
        log = null
        scope.launch {
            try {
                progressText = "Скачиваю версию для часов…"
                progress = 0f
                val apk = WatchInstaller.downloadWatchApk(ctx) { d, t ->
                    if (t > 0) scope.launch { progress = d.toFloat() / t; progressText = "Скачиваю: ${d / 1_048_576} из ${t / 1_048_576} МБ" }
                }
                val (h, p) = parseHost()
                if (pairFirst) {
                    progressText = "Подключаюсь к часам (код)…"
                    progress = null
                    WatchInstaller.pair(ctx, code, h, p)
                }
                progressText = "Соединяюсь с часами…"
                WatchInstaller.connect(ctx)
                progressText = "Устанавливаю на часы…"
                progress = 0f
                val reply = WatchInstaller.install(ctx, apk) { d, t ->
                    scope.launch { progress = d.toFloat() / t; progressText = "Передаю на часы: ${d * 100 / t}%" }
                }
                WatchInstaller.disconnect(ctx)
                log = if (reply.contains("Success")) "✅ Готово! «Переводчик» установлен на часы. Отладку по Wi‑Fi на часах можно выключить."
                else "Часы ответили: $reply"
                ctx.getSharedPreferences("phone", 0).edit().putBoolean("adbPaired", true).apply()
            } catch (e: Exception) {
                log = "Не получилось: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                progress = null
                step = 1
                refresh()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                Text("⌚ Часы", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh() }) { Text("⟳", fontSize = 22.sp) }
            }
        }

        // ---- Состояние ----
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    val i = info
                    when {
                        i == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 10.dp).height(22.dp))
                            Text("Ищу часы…")
                        }
                        !i.connected -> {
                            Text("Часы не подключены", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("Включите Bluetooth и проверьте, что часы подключены в приложении Galaxy Wearable / Wear OS.",
                                color = Color.Gray, fontSize = 14.sp)
                        }
                        !i.appInstalled -> {
                            Text("${i.watchName}: подключены", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("«Переводчик» на часах ещё не установлен", color = ColorB, fontSize = 15.sp)
                        }
                        else -> {
                            Text("${i.watchName}: ✓ всё готово", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text("Переводчик на часах: версия ${i.appVersion.ifEmpty { "?" }}", color = Color.Gray, fontSize = 14.sp)
                            if (!i.watchUsesPhone) Text("На часах выключена «Помощь телефона» — часы работают сами", color = ColorB, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // ---- Связь ----
        item { Section("Как работают вместе") }
        item {
            LinkRow(
                "Помогать часам",
                "Часы записывают голос, телефон быстро и точно распознаёт и переводит (по Bluetooth, без интернета). Выключите — часы будут полностью самостоятельны.",
                link
            ) { link = it; WatchLink.setLinkEnabled(ctx, it) }
        }
        item {
            LinkRow(
                "Одинаковые языки",
                "Выбрали языки на телефоне — они сами появятся на часах.",
                sync
            ) { sync = it; WatchLink.setSyncEnabled(ctx, it) }
        }

        // ---- Установка ----
        item { Section(if (info?.appInstalled == true) "Обновить на часах" else "Установить на часы") }
        if (fromPlay) {
            item {
                Button(onClick = { scope.launch { WatchLink.openPlayOnWatch(ctx) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть Google Play на часах")
                }
            }
        } else {
            val paired = ctx.getSharedPreferences("phone", 0).getBoolean("adbPaired", false)
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Один раз на часах:", fontWeight = FontWeight.Bold)
                        Text("1. Настройки → О часах → Программное обеспечение → 7 раз нажмите «Версия ПО» (включится режим разработчика).")
                        Text("2. Настройки → Параметры разработчика → включите «Отладка ADB» и «Отладка по Wi‑Fi».")
                        Text("3. Часы и телефон — в одной сети Wi‑Fi.")
                        if (!paired) Text("4. На часах: «Отладка по Wi‑Fi» → «Подключить новое устройство». Появится 6-значный код — введите его ниже.")
                        else Text("Часы уже сопряжены с телефоном — просто нажмите «Установить».", color = ColorA)
                    }
                }
            }
            item {
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Wi‑Fi телефона") }
            }
            item {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Код с часов (6 цифр)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it.trim() },
                    label = { Text("Адрес с часов (необязательно), напр. 192.168.1.25:41234") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { doInstall(pairFirst = code.length == 6) },
                        enabled = step != 2 && (code.length == 6 || paired),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (info?.appInstalled == true) "⬆ Обновить" else "⌚ Установить") }
                }
            }
            if (step == 2 || progress != null) {
                item {
                    Column {
                        Text(progressText)
                        Spacer(Modifier.height(6.dp))
                        val p = progress
                        if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Держите экран часов включённым", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
            log?.let { item { Text(it, fontSize = 15.sp, color = if (it.startsWith("✅")) ColorA else ColorB) } }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun LinkRow(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(hint, color = Color.Gray, fontSize = 13.sp)
            }
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}
