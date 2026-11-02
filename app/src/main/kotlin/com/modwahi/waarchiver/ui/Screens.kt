package com.modwahi.waarchiver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.modwahi.waarchiver.BuildConfig
import com.modwahi.waarchiver.data.DRIVE_FOLDER_NAME
import com.modwahi.waarchiver.data.KnownChat
import com.modwahi.waarchiver.data.Mode
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmt(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))

@Composable
fun Root(vm: AppViewModel, a: Actions) {
    vm.message?.let { msg ->
        AlertDialog(onDismissRequest = { vm.message = null }, confirmButton = {
            TextButton(onClick = { vm.message = null }) { Text("حسناً") }
        }, text = { Text(msg) })
    }
    vm.crash?.let { text ->
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(onDismissRequest = { }, title = { Text("توقف التطبيق في المرة السابقة") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("اضغط «نسخ» وأرسل النص للمطوّر (لا يحتوي المفتاح).")
                    Spacer(Modifier.height(8.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(text)); vm.message = "تم النسخ ✓" }) { Text("نسخ") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissCrash() }) { Text("إغلاق") } })
    }
    if (!vm.setupDone) SetupWizard(vm, a) else MainTabs(vm, a)
}

// ======================= معالج الإعداد =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizard(vm: AppViewModel, a: Actions) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("مرحباً", "١) مجلد واتساب", "٢) مفتاح التشفير", "٣) وجهة الحفظ", "٤) المحادثات")
    Scaffold(topBar = { TopAppBar(title = { Text("الإعداد — ${titles[step]}") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LinearProgressIndicator(progress = { step / 4f }, modifier = Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).padding(16.dp).let { if (step != 4) it.verticalScroll(rememberScrollState()) else it },
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    0 -> WelcomeStep(a)
                    1 -> FolderStep(vm, a)
                    2 -> KeyStep(vm)
                    3 -> DestinationStep(vm, a)
                    4 -> ChatsList(vm, compact = true)
                }
            }
            val canNext = when (step) {
                1 -> vm.waUri != null
                2 -> vm.hasKey
                3 -> (vm.mode == Mode.DRIVE && vm.account != null) || (vm.mode == Mode.LOCAL && vm.localUri != null)
                4 -> vm.selectedCount > 0
                else -> true
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { step-- }, enabled = step > 0) { Text("السابق") }
                Button(onClick = {
                    if (step < 4) step++ else { vm.finishSetup(); a.requestBatteryExemption() }
                }, enabled = canNext) { Text(if (step < 4) "التالي" else "إنهاء الإعداد") }
            }
        }
    }
}

@Composable
private fun WelcomeStep(a: Actions) {
    Text("هذا التطبيق يأخذ النسخة الاحتياطية المحلية لواتساب (التي يصنعها واتساب كل ليلة)، ويستخرج منها رسائل " +
        "المحادثات التي تختارها فقط مع صورها وملفاتها، ويرفعها إلى Google Drive تلقائياً.")
    Bullet("لا يعدّل أي ملف من ملفات واتساب. يقرأ فقط.")
    Bullet("المفتاح محفوظ مشفراً داخل التلفون ولا يظهر في أي سجل.")
    Bullet("يرفع فقط ولا يحذف أي شيء من Google Drive أبداً.")
    Bullet("لا يُرفع شيء من محادثة لم تخترها.")
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { a.requestNotifications() }) { Text("السماح بالإشعارات (لتنبيهك عند الفشل)") }
}

@Composable
private fun Bullet(t: String) = Row { Text("• "); Text(t) }

@Composable
private fun FolderStep(vm: AppViewModel, a: Actions) {
    Text("اختر مجلد واتساب في ذاكرة التلفون. سيفتح منتقي الملفات عليه مباشرة؛ اضغط «استخدام هذا المجلد» ثم «سماح».")
    Text("المسار: Android ← media ← com.whatsapp ← WhatsApp", fontWeight = FontWeight.Bold)
    Text("الصلاحية للقراءة فقط، وتبقى محفوظة دائماً.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { a.pickWhatsAppFolder() }) { Text(if (vm.waUri == null) "اختيار مجلد واتساب" else "تغيير المجلد") }
    if (vm.waUri != null) StatusLine(true, "تم اختيار المجلد" + (vm.backupAge?.let { " — عمر النسخة الاحتياطية ${"%.0f".format(Locale.ROOT, it)} ساعة" } ?: ""))
}

@Composable
private fun KeyStep(vm: AppViewModel) {
    Text("الصق مفتاح التشفير المكوّن من 64 خانة (الذي حفظته عند تفعيل «النسخة الاحتياطية المشفّرة تامّة الطرفين» في واتساب).")
    KeyEntry(vm)
}

@Composable
fun KeyEntry(vm: AppViewModel, onSaved: () -> Unit = {}) {
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    OutlinedTextField(
        value = key, onValueChange = { key = it; error = null },
        label = { Text("المفتاح (64 خانة)") },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "إظهار")
            }
        },
        modifier = Modifier.fillMaxWidth(), minLines = 2,
    )
    Button(enabled = !busy && key.isNotBlank(), onClick = {
        busy = true
        scope.launch {
            error = vm.verifyAndSaveKey(key)
            busy = false
            if (error == null) { key = ""; onSaved() }
        }
    }) { Text(if (busy) "جارٍ الفك التجريبي…" else "تحقق واحفظ") }
    error?.let { StatusLine(false, it) }
    if (vm.hasKey && error == null && !busy) StatusLine(true, "المفتاح محفوظ ومطابق للنسخة الاحتياطية")
}

@Composable
private fun DestinationStep(vm: AppViewModel, a: Actions) {
    Text("أين تُحفظ المحادثات؟")
    DestinationPicker(vm, a)
}

@Composable
fun DestinationPicker(vm: AppViewModel, a: Actions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google Drive (مستحسن)", fontWeight = FontWeight.Bold)
            Text("ينشئ التطبيق مجلد «$DRIVE_FOLDER_NAME» في جذر الدرايف ويكتب فيه فقط. يمكنك نقل المجلد لاحقاً لأي " +
                "مكان في الدرايف وسيستمر التطبيق بالكتابة فيه.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { a.signInGoogle() }) {
                Text(if (vm.account == null) "تسجيل الدخول بحساب Google" else "تغيير الحساب")
            }
            if (vm.account != null) StatusLine(vm.mode == Mode.DRIVE, "الحساب: ${vm.account}" +
                if (vm.mode == Mode.DRIVE) " (الوجهة الحالية)" else "")
            if (vm.account != null && vm.mode != Mode.DRIVE) TextButton(onClick = { vm.useMode(Mode.DRIVE) }) { Text("استخدم Google Drive") }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("حفظ محلي فقط", fontWeight = FontWeight.Bold)
            Text("بدون Google: يحفظ نفس المجلدات والملفات في مجلد تختاره على التلفون.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { a.pickLocalFolder() }) { Text(if (vm.localUri == null) "اختيار مجلد الحفظ" else "تغيير مجلد الحفظ") }
            if (vm.localUri != null) StatusLine(vm.mode == Mode.LOCAL, "تم اختيار مجلد" + if (vm.mode == Mode.LOCAL) " (الوجهة الحالية)" else "")
            if (vm.localUri != null && vm.mode != Mode.LOCAL) TextButton(onClick = { vm.useMode(Mode.LOCAL) }) { Text("استخدم الحفظ المحلي") }
        }
    }
}

@Composable
fun StatusLine(ok: Boolean, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Error, null,
        tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
    Spacer(Modifier.width(6.dp))
    Text(text, color = if (ok) Color.Unspecified else MaterialTheme.colorScheme.error)
}

// ======================= التبويبات =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabs(vm: AppViewModel, a: Actions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("الرئيسية", "المحادثات", "الإعدادات")
    Scaffold(
        topBar = { TopAppBar(title = { Text(titles[tab]) }) },
        bottomBar = {
            NavigationBar {
                listOf(Icons.Default.Home, Icons.Default.Forum, Icons.Default.Settings).forEachIndexed { i, icon ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(titles[i]) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(vm, a)
                1 -> Column(Modifier.padding(horizontal = 16.dp)) { ChatsList(vm, compact = false) }
                2 -> SettingsScreen(vm, a)
            }
        }
    }
}

@Composable
private fun JobCard(vm: AppViewModel) {
    val j = vm.job ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val what = when (j.action) { "dry" -> "تجربة بدون رفع"; "list" -> "قراءة المحادثات"; "scheduled" -> "تشغيل مجدول"; else -> "تشغيل" }
            Text("$what: ${j.stage}" + if (j.pct >= 0) " ${j.pct}%" else "")
            if (j.pct >= 0) LinearProgressIndicator(progress = { j.pct / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel, a: Actions) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JobCard(vm)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("النسخة الاحتياطية", fontWeight = FontWeight.Bold)
                val age = vm.backupAge
                if (age == null) StatusLine(false, "لم أجد ملف النسخة الاحتياطية في مجلد واتساب")
                else StatusLine(age <= 36, "عمرها ${"%.1f".format(Locale.ROOT, age)} ساعة" +
                    if (age > 36) " — قديمة! تأكد أن النسخ الاحتياطي اليومي مفعّل في واتساب" else "")
                Text("المحادثات المختارة: ${vm.selectedCount}")
                Text("الوجهة: " + if (vm.mode == Mode.DRIVE) "Google Drive (${vm.account ?: "—"})" else "مجلد محلي على التلفون")
                Text("التشغيل التلقائي: " + if (!vm.scheduleEnabled) "متوقف" else "كل ساعة تقريباً" +
                    (if (vm.requireCharging) "، على الشحن" else "") + (if (vm.requireWifi && vm.mode == Mode.DRIVE) "، على واي فاي" else "") +
                    " — يعمل فعلياً مرة بعد كل نسخة جديدة", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runNow() }, enabled = vm.job == null && vm.selectedCount > 0) { Text("شغّل الآن") }
            OutlinedButton(onClick = { a.requestLegacyStorage(); vm.dryRun() }, enabled = vm.job == null && vm.selectedCount > 0) {
                Text("تجربة بدون رفع")
            }
        }
        Text("«تجربة بدون رفع» تحفظ الناتج في مجلد Download ولا تغيّر حالة التشغيل.", style = MaterialTheme.typography.bodySmall)
        LastRunCard(vm.lastRun)
        if (vm.mode == Mode.DRIVE && vm.driveFolderId != null) {
            TextButton(onClick = { a.openUrl("https://drive.google.com/drive/folders/${vm.driveFolderId}") }) {
                Text("فتح مجلد الأرشيف في Google Drive")
            }
        }
    }
}

@Composable
private fun LastRunCard(o: JSONObject?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("آخر تشغيل", fontWeight = FontWeight.Bold)
            if (o == null) { Text("لم يتم أي تشغيل بعد."); return@Column }
            val ok = o.optBoolean("ok")
            StatusLine(ok, fmt(o.optLong("time")) + (if (o.optBoolean("dry")) " (تجربة)" else "") + " — " + if (ok) "ناجح" else "فيه أخطاء")
            val chats = o.optJSONArray("chats")
            if (chats != null) for (i in 0 until chats.length()) {
                val c = chats.getJSONObject(i)
                val err = c.optString("error").takeIf { !c.isNull("error") }
                HorizontalDivider()
                Text(c.optString("name"), fontWeight = FontWeight.SemiBold)
                if (err != null) Text("خطأ: $err", color = MaterialTheme.colorScheme.error)
                else {
                    val extra = buildList {
                        if (c.optInt("missing") > 0) add("غير منزّلة ${c.optInt("missing")}")
                        if (c.optInt("large") > 0) add("أكبر من الحد ${c.optInt("large")}")
                        if (c.optInt("recovered") > 0) add("مستردة ${c.optInt("recovered")}")
                    }
                    Text("رسائل ${c.optInt("messages")} | وسائط ${c.optInt("media")}" + extra.joinToString("") { " | $it" })
                }
            }
            val errs = o.optJSONArray("errors")
            if (errs != null) for (i in 0 until errs.length()) Text("✗ ${errs.getString(i)}", color = MaterialTheme.colorScheme.error)
            val warns = o.optJSONArray("warnings")
            if (warns != null) for (i in 0 until warns.length()) Text("⚠️ ${warns.getString(i)}")
            if (!o.isNull("exported")) Text("الناتج في: ${o.optString("exported")}")
        }
    }
}

// ======================= المحادثات =======================

@Composable
fun ChatsList(vm: AppViewModel, compact: Boolean) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 الكل، 1 مجموعات، 2 خاص، 3 المختارة
    JobCard(vm)
    if (vm.known.isEmpty()) {
        Text("لقراءة قائمة المحادثات يفك التطبيق النسخة الاحتياطية مرة (قد يستغرق دقيقة أو أكثر)، ثم يحذف النسخة المفكوكة.")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.refreshChats() }, enabled = vm.job == null) {
            Text(if (vm.known.isEmpty()) "اقرأ المحادثات من النسخة" else "تحديث القائمة")
        }
        if (vm.knownAt > 0) Text("  آخر تحديث ${fmt(vm.knownAt)}", style = MaterialTheme.typography.bodySmall)
    }
    vm.listError?.let { StatusLine(false, it) }
    if (vm.known.isEmpty()) return
    OutlinedTextField(value = query, onValueChange = { query = it }, leadingIcon = { Icon(Icons.Default.Search, null) },
        placeholder = { Text("بحث بالاسم أو الرقم") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("الكل", "مجموعات", "خاص", "المختارة (${vm.selectedCount})").forEachIndexed { i, t ->
            FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(t) })
        }
    }
    val q = query.trim()
    val list = vm.known.filter { c ->
        val sel = vm.choices[c.jid]?.selected == true
        (filter == 0 || (filter == 1 && c.isGroup) || (filter == 2 && !c.isGroup) || (filter == 3 && sel)) &&
            (q.isEmpty() || vm.displayName(c).contains(q, ignoreCase = true) || c.jid.contains(q))
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.jid }) { c -> ChatRow(vm, c) }
    }
}

@Composable
private fun ChatRow(vm: AppViewModel, c: KnownChat) {
    val choice = vm.choices[c.jid]
    val selected = choice?.selected == true
    Column(Modifier.fillMaxWidth().clickable { vm.toggle(c.jid, !selected) }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { vm.toggle(c.jid, it) })
            Column(Modifier.weight(1f)) {
                Text(vm.displayName(c), fontWeight = FontWeight.SemiBold)
                Text((if (c.isGroup) "مجموعة" else "خاص") + " · " +
                    (if (c.lastTs > 0) "آخر رسالة ${fmt(c.lastTs * 1000)}" else "لا رسائل") + " · ${c.count} رسالة",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (selected) {
            Column(Modifier.padding(start = 48.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!c.isGroup) {
                    var owner by remember(c.jid) { mutableStateOf(vm.contacts[com.modwahi.waarchiver.core.Archiver.numberOf(c.jid)] ?: "") }
                    OutlinedTextField(value = owner, onValueChange = { owner = it; vm.setOwnerName(c.jid, it) },
                        label = { Text("اسم صاحب الرقم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                var folder by remember(c.jid) { mutableStateOf(choice.folder) }
                OutlinedTextField(value = folder, onValueChange = { folder = it; vm.setFolder(c.jid, it) },
                    label = { Text("اسم المجلد") }, placeholder = { Text(vm.defaultFolder(c)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

// ======================= الإعدادات =======================

@Composable
fun SettingsScreen(vm: AppViewModel, a: Actions) {
    var keyDialog by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("مفتاح التشفير") {
            StatusLine(vm.hasKey, if (vm.hasKey) "محفوظ مشفراً في Keystore" else "لا يوجد مفتاح")
            OutlinedButton(onClick = { keyDialog = true }) { Text("تغيير المفتاح") }
        }
        Section("مجلد واتساب") {
            OutlinedButton(onClick = { a.pickWhatsAppFolder() }) { Text("اختيار مجلد واتساب من جديد") }
        }
        Section("وجهة الحفظ والحساب") { DestinationPicker(vm, a) }
        Section("التشغيل التلقائي") {
            SwitchRow("تفعيل التشغيل التلقائي (كل ساعة تقريباً)", vm.scheduleEnabled) { vm.updateSchedule(enabled = it) }
            SwitchRow("فقط والتلفون على الشحن", vm.requireCharging) { vm.updateSchedule(charging = it) }
            SwitchRow("فقط على واي فاي", vm.requireWifi) { vm.updateSchedule(wifi = it) }
            Text("يعمل فعلياً مرة واحدة بعد كل نسخة احتياطية جديدة (حسب وقت تعديل ملف النسخة).", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { a.requestBatteryExemption() }) { Text("السماح بالعمل في الخلفية (إيقاف تحسين البطارية)") }
        }
        Section("الاستخراج") {
            NumberField("أيام السحب أول مرة لكل محادثة", vm.firstRunDays) { vm.updateNumbers(days = it) }
            NumberField("الحد الأقصى لحجم الوسيط (ميغابايت)", vm.maxMediaMb) { vm.updateNumbers(maxMb = it) }
            TextSetting("المنطقة الزمنية (لتجميع الرسائل حسب التاريخ)", vm.timezone) { vm.updateTimezone(it) }
            TextSetting("اسمي في الرسائل", vm.meName) { vm.updateMeName(it); true }
        }
        Section("السجل") {
            OutlinedButton(onClick = { a.exportLogs() }) { Text("تصدير السجل") }
            Text("السجل لا يحتوي المفتاح أبداً.", style = MaterialTheme.typography.bodySmall)
        }
        Text("الإصدار ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
    }
    if (keyDialog) {
        AlertDialog(onDismissRequest = { keyDialog = false }, confirmButton = {
            TextButton(onClick = { keyDialog = false }) { Text("إغلاق") }
        }, title = { Text("تغيير المفتاح") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { KeyEntry(vm) { keyDialog = false; vm.message = "تم حفظ المفتاح الجديد ✓" } }
        })
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Int, onSave: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { t ->
        text = t.filter { it.isDigit() }.take(5)
        text.toIntOrNull()?.let(onSave)
    }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun TextSetting(label: String, value: String, onSave: (String) -> Boolean) {
    var text by remember(value) { mutableStateOf(value) }
    var bad by remember { mutableStateOf(false) }
    OutlinedTextField(value = text, onValueChange = { text = it; bad = !onSave(it) }, label = { Text(label) },
        isError = bad, singleLine = true, modifier = Modifier.fillMaxWidth())
}
