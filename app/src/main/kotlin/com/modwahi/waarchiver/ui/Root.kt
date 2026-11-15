package com.modwahi.waarchiver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.modwahi.waarchiver.data.DRIVE_FOLDER_NAME
import com.modwahi.waarchiver.data.Mode
import kotlinx.coroutines.launch

enum class Route { HOME, CHATS, READ, SETTINGS, HISTORY, NAMES, READ_CHAT }

@Suppress("DEPRECATION")
@Composable
fun Root(vm: AppViewModel, a: Actions) {
    vm.message?.let { msg ->
        AlertDialog(onDismissRequest = { vm.message = null }, confirmButton = {
            TextButton(onClick = { vm.message = null }) { Text("حسناً") }
        }, text = { Text(msg) })
    }
    vm.crash?.let { text ->
        val clip = LocalClipboardManager.current
        AlertDialog(onDismissRequest = { }, title = { Text("توقف التطبيق في المرة السابقة") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("اضغط «نسخ» وأرسل النص للمطوّر (لا يحتوي المفتاح).")
                    Spacer(Modifier.height(8.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { clip.setText(AnnotatedString(text)); vm.message = "تم النسخ ✓" }) { Text("نسخ") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissCrash() }) { Text("إغلاق") } })
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!vm.setupDone) SetupWizard(vm, a) else MainScaffold(vm, a)
    }
}

// ======================= الهيكل الرئيسي =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(vm: AppViewModel, a: Actions) {
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var readerFolder by rememberSaveable { mutableStateOf("") }
    val tabs = listOf(Route.HOME to "الرئيسية", Route.CHATS to "المحادثات", Route.READ to "الرسائل", Route.SETTINGS to "الإعدادات")
    val sub = route == Route.HISTORY || route == Route.NAMES || route == Route.READ_CHAT
    val back = { route = when (route) { Route.NAMES -> Route.SETTINGS; Route.READ_CHAT -> Route.READ; else -> Route.HOME } }
    BackHandler(enabled = route != Route.HOME) { back() }
    val title = when (route) {
        Route.HOME -> "أرشيف واتساب"; Route.CHATS -> "المحادثات"; Route.SETTINGS -> "الإعدادات"
        Route.HISTORY -> "سجل التشغيلات"; Route.NAMES -> "أسماء الأرقام"
        Route.READ -> "الرسائل المؤرشفة"; Route.READ_CHAT -> readerFolder
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (sub) IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!sub) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                listOf(Icons.Rounded.Home, Icons.Rounded.Forum, Icons.Rounded.MenuBook, Icons.Rounded.Settings).forEachIndexed { i, icon ->
                    NavigationBarItem(selected = route == tabs[i].first, onClick = { route = tabs[i].first },
                        icon = { Icon(icon, null) }, label = { Text(tabs[i].second) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AnimatedContent(route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { r ->
                when (r) {
                    Route.HOME -> HomeScreen(vm, a, openHistory = { route = Route.HISTORY }, openChats = { route = Route.CHATS })
                    Route.CHATS -> ChatsScreen(vm, a)
                    Route.SETTINGS -> SettingsScreen(vm, a, openNames = { route = Route.NAMES }, openHistory = { route = Route.HISTORY })
                    Route.HISTORY -> HistoryScreen(vm)
                    Route.NAMES -> NamesScreen(vm, a)
                    Route.READ -> ReaderListScreen(vm) { readerFolder = it; route = Route.READ_CHAT }
                    Route.READ_CHAT -> ChatReaderScreen(vm, readerFolder)
                }
            }
        }
    }
}

// ======================= معالج الإعداد =======================

private data class Step(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val desc: String)

@Composable
fun SetupWizard(vm: AppViewModel, a: Actions) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = listOf(
        Step(Icons.Rounded.Inventory2, "أهلاً بك", "أرشفة محادثات واتساب التي تختارها إلى Google Drive تلقائياً كل ليلة."),
        Step(Icons.Rounded.FolderOpen, "مجلد واتساب", "اختر مجلد واتساب في ذاكرة التلفون حتى يقرأ التطبيق النسخة الاحتياطية."),
        Step(Icons.Rounded.Key, "مفتاح التشفير", "المفتاح المكوّن من 64 خانة الذي حفظته عند تفعيل النسخة الاحتياطية المشفّرة."),
        Step(Icons.Rounded.Cloud, "وجهة الحفظ", "أين تُحفظ المحادثات المؤرشفة؟"),
        Step(Icons.Rounded.Forum, "اختر المحادثات", "فقط المحادثات التي تختارها تُؤرشف. يمكنك تغييرها لاحقاً."),
    )
    BackHandler(enabled = step > 0) { step-- }
    val canNext = when (step) {
        1 -> vm.waUri != null
        2 -> vm.hasKey
        3 -> (vm.mode == Mode.DRIVE && vm.account != null) || (vm.mode == Mode.LOCAL && vm.localUri != null)
        4 -> vm.selectedCount > 0
        else -> true
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) OutlinedButton(onClick = { step-- }) { Text("السابق") }
                Spacer(Modifier.weight(1f))
                Text("الخطوة ${step + 1} من ${steps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { if (step < steps.lastIndex) step++ else { vm.finishSetup(); a.requestBatteryExemption() } },
                    enabled = canNext) { Text(if (step < steps.lastIndex) "التالي" else "ابدأ") }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // شريط الخطوات
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { i ->
                    Surface(Modifier.weight(1f).height(5.dp), shape = RoundedCornerShape(50),
                        color = if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {}
                }
            }
            val s = steps[step]
            val body: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    IconBadge(s.icon, Tone.INFO, 72)
                    Spacer(Modifier.height(14.dp))
                    Text(s.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(s.desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(20.dp))
                }
            }
            if (step == 4) {
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    body()
                    ChatsScreen(vm, a, embedded = true)
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    body()
                    when (step) {
                        0 -> WelcomeStep(a)
                        1 -> FolderStep(vm, a)
                        2 -> KeyEntry(vm)
                        3 -> DestinationPicker(vm, a)
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(a: Actions) {
    AppCard {
        Column {
            SettingRow(Icons.Rounded.PhoneAndroid, "يعمل على التلفون نفسه", "يفك النسخة الاحتياطية الليلية بمفتاحك، ثم يحذف النسخة المفكوكة دائماً.")
            SettingRow(Icons.Rounded.Lock, "قراءة فقط وآمن", "لا يعدّل أي ملف من ملفات واتساب، والمفتاح محفوظ مشفراً ولا يظهر في أي سجل.", Tone.OK)
            SettingRow(Icons.Rounded.Cloud, "رفع فقط", "يرفع إلى Google Drive ولا يحذف أي شيء أبداً. ولا يُرفع شيء من محادثة لم تخترها.", Tone.OK)
        }
    }
    FilledTonalButton(onClick = { a.requestNotifications() }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Notifications, null); Spacer(Modifier.size(8.dp)); Text("السماح بالإشعارات (لتنبيهك عند الفشل)")
    }
}

@Composable
private fun FolderStep(vm: AppViewModel, a: Actions) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("سيفتح منتقي الملفات على المجلد مباشرة. اضغط «استخدام هذا المجلد» ثم «سماح».")
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                Text("Android ← media ← com.whatsapp ← WhatsApp", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp).fillMaxWidth(), textAlign = TextAlign.Center)
            }
            Button(onClick = { a.pickWhatsAppFolder() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Folder, null); Spacer(Modifier.size(8.dp))
                Text(if (vm.waUri == null) "اختيار مجلد واتساب" else "تغيير المجلد")
            }
            if (vm.waUri != null) {
                val age = vm.backupAge
                if (age != null) StatusLine(true, "تم — عمر النسخة الاحتياطية ${hoursText(age)}")
                else StatusLine(false, "اخترت مجلداً، لكن لم أجد ملف النسخة الاحتياطية فيه", Tone.WARN)
            }
            Text("الصلاحية للقراءة فقط، وتبقى محفوظة دائماً.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun KeyEntry(vm: AppViewModel, onSaved: () -> Unit = {}) {
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val count = key.count { it.isLetterOrDigit() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = key, onValueChange = { key = it; error = null; saved = false },
            label = { Text("المفتاح") },
            supportingText = { Text("$count / 64 خانة") },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = "إظهار")
                }
            },
            modifier = Modifier.fillMaxWidth(), minLines = 2, shape = MaterialTheme.shapes.medium,
        )
        Button(enabled = !busy && key.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            scope.launch {
                error = vm.verifyAndSaveKey(key)
                busy = false
                if (error == null) { key = ""; saved = true; onSaved() }
            }
        }) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)) }
            Text(if (busy) "جارٍ الفك التجريبي…" else "تحقق واحفظ")
        }
        error?.let { StatusLine(false, it) }
        if ((saved || vm.hasKey) && error == null && !busy) StatusLine(true, "المفتاح محفوظ ومطابق للنسخة الاحتياطية")
    }
}

@Composable
fun DestinationPicker(vm: AppViewModel, a: Actions) {
    @Composable
    fun Option(selected: Boolean, enabled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String,
               desc: String, onSelect: () -> Unit, content: @Composable () -> Unit) {
        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon, if (selected) Tone.OK else Tone.INFO)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
                }
                content()
            }
        }
    }
    Option(vm.mode == Mode.DRIVE, vm.account != null, Icons.Rounded.Cloud, "Google Drive",
        "مجلد «$DRIVE_FOLDER_NAME» في الدرايف. يمكنك نقله لأي مكان لاحقاً وسيستمر التطبيق بالكتابة فيه.",
        { vm.useMode(Mode.DRIVE) }) {
        if (vm.account != null) Text("الحساب: ${vm.account}", style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = { a.signInGoogle() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.account == null) "تسجيل الدخول بحساب Google" else "تغيير الحساب")
        }
    }
    Option(vm.mode == Mode.LOCAL, vm.localUri != null, Icons.Rounded.PhoneAndroid, "حفظ محلي فقط",
        "نفس المجلدات والملفات في مجلد تختاره على التلفون، بدون Google.", { vm.useMode(Mode.LOCAL) }) {
        OutlinedButton(onClick = { a.pickLocalFolder() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.localUri == null) "اختيار مجلد الحفظ" else "تغيير مجلد الحفظ")
        }
    }
}

private fun mb(b: Long) = String.format(java.util.Locale.ROOT, "%.1f", b / 1048576.0)

private fun duration(secs: Long): String = when {
    secs < 60 -> "أقل من دقيقة"
    secs < 3600 -> arCount((secs + 59) / 60, "دقيقة", "دقيقتين", "دقائق")
    else -> "${secs / 3600} س ${(secs % 3600) / 60} د"
}

@Composable
fun JobBanner(vm: AppViewModel) {
    val j = vm.job ?: return
    val what = when (j.action) { "dry" -> "تجربة بدون رفع"; "list" -> "قراءة المحادثات"; "mirror" -> "تنزيل للقراءة"
        "scheduled" -> "أرشفة تلقائية"; else -> "أرشفة" }
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(what, style = MaterialTheme.typography.titleSmall)
                    if (j.phase > 0) Text("المرحلة ${j.phase} من 3 — ${j.stage}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    else Text(j.stage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (j.pct >= 0) Text("${j.pct}%", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                TextButton(onClick = { vm.stop() }) { Text("إيقاف") }
            }
            if (j.pct >= 0) LinearProgressIndicator(progress = { j.pct / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            if (j.phase > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("فك النسخة", "تجهيز", "رفع").forEachIndexed { i, t ->
                    val n = i + 1
                    Pill(if (j.phase > n) "✓ $t" else if (j.phase == n) "● $t" else t, if (j.phase > n) Tone.OK else Tone.INFO)
                }
            }
            if (j.phase == 3 && j.total > 0) {
                Text("المرفوع: ${ltr(mb(j.done))} من ${ltr(mb(j.total))} ميغابايت", style = MaterialTheme.typography.bodyMedium)
                if (j.speed > 0) Text("السرعة ${ltr(mb(j.speed))} ميغابايت/ث" + if (j.eta >= 0) " · المتبقي ${duration(j.eta)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (j.phase == 3 && j.action == "dry") {
                Text("التجربة لا ترفع شيئاً", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
