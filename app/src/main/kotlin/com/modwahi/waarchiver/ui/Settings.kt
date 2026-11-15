package com.modwahi.waarchiver.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.modwahi.waarchiver.BuildConfig
import com.modwahi.waarchiver.data.DRIVE_FOLDER_NAME
import com.modwahi.waarchiver.data.Mode
import kotlinx.coroutines.launch

private enum class Dialog { NONE, KEY, DAYS, MEDIA, TZ, ME, DEST, CC, FOLDER }

@Composable
fun SettingsScreen(vm: AppViewModel, a: Actions, openNames: () -> Unit, openHistory: () -> Unit) {
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    val chevron: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {

        SectionTitle("المصدر والأمان")
        AppCard {
            Column {
                SettingRow(Icons.Rounded.Key, "مفتاح التشفير", if (vm.hasKey) "محفوظ مشفراً في Keystore — اضغط للتغيير" else "لا يوجد مفتاح",
                    if (vm.hasKey) Tone.OK else Tone.ERROR, onClick = { dialog = Dialog.KEY }, trailing = chevron)
                SettingRow(Icons.Rounded.FolderOpen, "مجلد واتساب",
                    vm.backupAge?.let { "النسخة الاحتياطية عمرها ${hoursText(it)}" } ?: "لم أجد ملف النسخة الاحتياطية",
                    if (vm.backupAge == null) Tone.ERROR else Tone.INFO, onClick = { a.pickWhatsAppFolder() }, trailing = chevron)
            }
        }

        SectionTitle("الوجهة")
        AppCard {
            Column {
                SettingRow(if (vm.mode == Mode.DRIVE) Icons.Rounded.Cloud else Icons.Rounded.PhoneAndroid, "وجهة الحفظ",
                    if (vm.mode == Mode.DRIVE) "Google Drive — ${vm.account ?: "لم تسجّل الدخول"}" else "مجلد محلي على التلفون",
                    onClick = { dialog = Dialog.DEST }, trailing = chevron)
                if (vm.mode == Mode.DRIVE && vm.account != null) SettingRow(Icons.Rounded.FolderOpen, "مجلد الأرشيف في Drive",
                    "اختر مجلدك السابق (مثلاً بعد إعادة تثبيت التطبيق) ليواصل فيه بدون تكرار",
                    onClick = { vm.loadArchiveFolders(); dialog = Dialog.FOLDER }, trailing = chevron)
                if (vm.mode == Mode.DRIVE && vm.driveFolderId != null) SettingRow(Icons.AutoMirrored.Rounded.OpenInNew,
                    "فتح مجلد الأرشيف", "«$DRIVE_FOLDER_NAME» — يمكنك نقله لأي مكان في الدرايف وسيستمر التطبيق بالكتابة فيه",
                    onClick = { a.openUrl("https://drive.google.com/drive/folders/${vm.driveFolderId}") })
            }
        }

        SectionTitle("التشغيل التلقائي")
        AppCard {
            Column {
                SettingRow(Icons.Rounded.Schedule, "تفعيل التشغيل التلقائي", "يفحص كل ساعة تقريباً ويؤرشف مرة بعد كل نسخة جديدة") {
                    Switch(vm.scheduleEnabled, { vm.updateSchedule(enabled = it) })
                }
                SettingRow(Icons.Rounded.Power, "فقط والتلفون على الشحن") {
                    Switch(vm.requireCharging, { vm.updateSchedule(charging = it) }, enabled = vm.scheduleEnabled)
                }
                if (vm.mode == Mode.DRIVE) SettingRow(Icons.Rounded.Wifi, "فقط على واي فاي") {
                    Switch(vm.requireWifi, { vm.updateSchedule(wifi = it) }, enabled = vm.scheduleEnabled)
                }
                SettingRow(Icons.Rounded.BatteryChargingFull, "العمل في الخلفية",
                    if (vm.batteryExempt) "مسموح ✓ (التطبيق مستثنى من تحسين البطارية)" else "غير مسموح — اضغط للسماح حتى لا يتوقف التشغيل الليلي",
                    if (vm.batteryExempt) Tone.OK else Tone.WARN, onClick = { a.requestBatteryExemption() })
                SettingRow(Icons.Rounded.PhoneAndroid, "إعدادات البطارية للتطبيق",
                    "في سامسونج: البطارية ← «غير مقيّد»، حتى لا يضع النظام التطبيق في وضع السكون", onClick = { a.openAppSettings() })
            }
        }

        SectionTitle("الاستخراج")
        AppCard {
            Column {
                SettingRow(Icons.Rounded.CalendarMonth, "أيام السحب أول مرة", "${vm.firstRunDays} يوم لكل محادثة جديدة",
                    onClick = { dialog = Dialog.DAYS }, trailing = chevron)
                SettingRow(Icons.Rounded.Photo, "الحد الأقصى لحجم الوسيط", "${vm.maxMediaMb} ميغابايت — الأكبر يُسجَّل بدون نسخه",
                    onClick = { dialog = Dialog.MEDIA }, trailing = chevron)
                SettingRow(Icons.Rounded.Language, "المنطقة الزمنية", "${vm.timezone} — لتجميع الرسائل حسب التاريخ",
                    onClick = { dialog = Dialog.TZ }, trailing = chevron)
                SettingRow(Icons.Rounded.Person, "اسمي في الرسائل", vm.meName, onClick = { dialog = Dialog.ME }, trailing = chevron)
                SettingRow(Icons.Rounded.Badge, "أسماء الأرقام",
                    "${vm.contacts.size} اسم مكتوب" + if (vm.importContacts) " + ${vm.phoneContactsCount} من جهات الاتصال" else "",
                    onClick = openNames, trailing = chevron)
            }
        }

        SectionTitle("الإشعارات والمظهر")
        AppCard {
            Column {
                SettingRow(Icons.Rounded.Notifications, "إشعار عند نجاح الأرشفة", "الأخطاء والنسخة القديمة يُنبَّه لها دائماً") {
                    Switch(vm.notifySuccess, { vm.updateNotifySuccess(it); if (it) a.requestNotifications() })
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Palette, Tone.INFO, 38)
                        Spacer(Modifier.width(14.dp))
                        Text("المظهر", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.size(10.dp))
                    val opts = listOf("حسب النظام", "فاتح", "داكن")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        opts.forEachIndexed { i, t ->
                            SegmentedButton(selected = vm.themeMode == i, onClick = { vm.updateTheme(i) },
                                shape = SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(t) }
                        }
                    }
                }
            }
        }

        SectionTitle("السجل وحول التطبيق")
        AppCard {
            Column {
                SettingRow(Icons.Rounded.History, "سجل التشغيلات", "آخر ${vm.history.size} تشغيلاً بالتفاصيل", onClick = openHistory, trailing = chevron)
                SettingRow(Icons.Rounded.Description, "تصدير السجل", "ملف نصي للمطوّر — لا يحتوي المفتاح أبداً", onClick = { a.exportLogs() })
                SettingRow(Icons.Rounded.Info, "الإصدار والتحديثات",
                    "الإصدار الحالي ${BuildConfig.VERSION_NAME}" + when {
                        vm.updateState == "checking" -> " — جارٍ الفحص…"
                        vm.update != null -> " — متوفر ${vm.update!!.version}، التحديث من الرئيسية"
                        else -> " — اضغط لفحص التحديثات"
                    }, if (vm.update != null) Tone.OK else Tone.INFO, onClick = { vm.checkUpdates(manual = true) })
            }
        }
        Spacer(Modifier.size(16.dp))
    }

    val close = { dialog = Dialog.NONE }
    when (dialog) {
        Dialog.KEY -> AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("إغلاق") } },
            title = { Text("تغيير المفتاح") },
            text = { KeyEntry(vm) { close(); vm.message = "تم حفظ المفتاح الجديد ✓" } })
        Dialog.DAYS -> NumberDialog("أيام السحب أول مرة", "عدد الأيام التي تُسحب من كل محادثة جديدة عند أول أرشفة لها.",
            vm.firstRunDays, 1..3650, close) { vm.updateNumbers(days = it) }
        Dialog.MEDIA -> NumberDialog("الحد الأقصى لحجم الوسيط (ميغابايت)", "الوسائط الأكبر تُسجَّل في الرسائل بـ «[وسيط أكبر من الحد]» بدون نسخها.",
            vm.maxMediaMb, 1..4096, close) { vm.updateNumbers(maxMb = it) }
        Dialog.ME -> TextDialog("اسمي في الرسائل", vm.meName, close) { vm.updateMeName(it); true }
        Dialog.TZ -> TimezoneDialog(vm, close)
        Dialog.DEST -> AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("تم") } },
            title = { Text("وجهة الحفظ") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { DestinationPicker(vm, a) } })
        Dialog.FOLDER -> FolderDialog(vm, close)
        Dialog.CC, Dialog.NONE -> {}
    }
}

@Composable
private fun FolderDialog(vm: AppViewModel, onClose: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun choose(v: String) {
        busy = true
        scope.launch {
            error = vm.chooseArchiveFolder(v)
            busy = false
            if (error == null) onClose()
        }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("مجلد الأرشيف في Drive") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("مجلدات الأرشيف التي أنشأها التطبيق (حتى لو نقلتها أو غيّرت اسمها):", style = MaterialTheme.typography.bodyMedium)
                val list = vm.archiveFolders
                when {
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("جارٍ البحث في Drive…")
                    }
                    list.isEmpty() -> Text(vm.archiveFoldersError ?: "لم أجد مجلدات أرشيف سابقة.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> list.forEach { f ->
                        val current = f.id == vm.driveFolderId
                        AppCard {
                            Row(Modifier.clickable(enabled = !busy && !current) { choose(f.id) }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = current, onClick = { if (!current) choose(f.id) }, enabled = !busy)
                                Column(Modifier.weight(1f)) {
                                    Text(f.name + if (current) "  (الحالي)" else "", style = MaterialTheme.typography.bodyLarge)
                                    Text("${f.chats} محادثة" +
                                        (if (f.lastRunMs > 0) " · آخر تشغيل ${fmtDate(f.lastRunMs)}" else "") +
                                        " · أُنشئ ${fmtDay(f.createdMs)}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                OutlinedTextField(value = link, onValueChange = { link = it; error = null }, singleLine = true,
                    label = { Text("أو الصق رابط المجلد من Drive") }, modifier = Modifier.fillMaxWidth())
                error?.let { StatusLine(false, it) }
                Text("عند التغيير يكمل التطبيق آخر ${vm.firstRunDays} يوم في المجلد المختار ويدمجها مع الموجود بدون تكرار. " +
                    "يمكنك زيادة «أيام السحب أول مرة» قبل التشغيل إن كان الانقطاع أطول.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = !busy && link.isNotBlank(), onClick = { choose(link) }) { Text("استخدم الرابط") } },
        dismissButton = { TextButton(onClick = onClose) { Text("إغلاق") } })
}

@Composable
private fun NumberDialog(title: String, desc: String, value: Int, range: IntRange, onClose: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    val n = text.toIntOrNull()
    val ok = n != null && n in range
    AlertDialog(onDismissRequest = onClose, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(desc, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = text, onValueChange = { t -> text = t.filter { it.isDigit() }.take(5) }, singleLine = true,
                    isError = !ok, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(n!!); onClose() }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } })
}

@Composable
private fun TextDialog(title: String, value: String, onClose: () -> Unit, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf(value) }
    var bad by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it; bad = false }, singleLine = true, isError = bad, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (onSave(text)) onClose() else bad = true }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } })
}

@Composable
private fun TimezoneDialog(vm: AppViewModel, onClose: () -> Unit) {
    val common = listOf("Asia/Aden" to "اليمن (عدن)", "Asia/Riyadh" to "السعودية", "Asia/Dubai" to "الإمارات وعُمان",
        "Asia/Qatar" to "قطر", "Asia/Kuwait" to "الكويت", "Africa/Cairo" to "مصر", "Asia/Amman" to "الأردن",
        "Europe/Istanbul" to "تركيا", "Asia/Kuala_Lumpur" to "ماليزيا", "Europe/London" to "بريطانيا")
    var custom by remember { mutableStateOf(if (common.any { it.first == vm.timezone }) "" else vm.timezone) }
    var bad by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text("المنطقة الزمنية") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                common.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().clickable { vm.updateTimezone(id); onClose() }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.timezone == id, onClick = { vm.updateTimezone(id); onClose() })
                        Text("$label  ", style = MaterialTheme.typography.bodyLarge)
                        Text(id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(value = custom, onValueChange = { custom = it; bad = false }, label = { Text("أخرى (مثل Asia/Baghdad)") },
                    isError = bad, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = custom.isNotBlank(), onClick = { if (vm.updateTimezone(custom)) onClose() else bad = true }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } })
}

// ======================= سجل التشغيلات =======================

@Suppress("DEPRECATION")
@Composable
fun HistoryScreen(vm: AppViewModel) {
    val clip = LocalClipboardManager.current
    if (vm.history.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            IconBadge(Icons.Rounded.History, Tone.INFO, 64)
            Spacer(Modifier.size(12.dp))
            Text("لا توجد تشغيلات بعد", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(vm.history, key = { it.optLong("time") }) { o ->
            var open by remember { mutableStateOf(false) }
            val ok = o.optBoolean("ok")
            val chats = o.optJSONArray("chats")
            val msgs = chats?.let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).optInt("messages") } } ?: 0
            val media = chats?.let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).optInt("media") } } ?: 0
            AppCard(Modifier.animateContentSize()) {
                Column(Modifier.clickable { open = !open }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null,
                            tint = toneColors(if (ok) Tone.OK else Tone.ERROR).first)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fmtDate(o.optLong("time")), style = MaterialTheme.typography.titleSmall)
                            Text("رسائل $msgs · وسائط $media" + when {
                                o.optBoolean("dry") -> " · تجربة"; o.optBoolean("scheduled") -> " · تلقائي"; else -> " · يدوي"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (open) IconButton(onClick = { clip.setText(AnnotatedString(o.optString("text"))); vm.message = "تم نسخ الملخص ✓" }) {
                            Icon(Icons.Rounded.ContentCopy, "نسخ")
                        }
                    }
                    if (open) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        RunDetails(o)
                        Text(o.optString("text"), style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ======================= أسماء الأرقام =======================

@Composable
fun NamesScreen(vm: AppViewModel, a: Actions) {
    var adding by remember { mutableStateOf<Pair<String, String>?>(null) }
    var ccDialog by remember { mutableStateOf(false) }
    androidx.compose.material3.Scaffold(containerColor = MaterialTheme.colorScheme.background, floatingActionButton = {
        FloatingActionButton(onClick = { adding = "" to "" }) { Icon(Icons.Rounded.Add, "إضافة") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AppCard {
                    Column {
                        SettingRow(Icons.Rounded.Contacts, "استخدام جهات الاتصال",
                            if (vm.importContacts) "${vm.phoneContactsCount} رقم من جهات الاتصال — الأسماء المكتوبة هنا لها الأولوية"
                            else "أسماء المرسلين من جهات اتصال التلفون (مثل contacts.json في أداة Termux)") {
                            Switch(vm.importContacts, { on -> if (on) a.requestContacts() else vm.updateImportContacts(false) })
                        }
                        if (vm.importContacts) SettingRow(Icons.Rounded.Language, "رمز الدولة للأرقام المحلية",
                            "${vm.countryCode} — مثلاً 0771234567 تصبح ${vm.countryCode}771234567", onClick = { ccDialog = true })
                    }
                }
            }
            item {
                Text("الاسم يظهر في الرسائل هكذا: أحمد (967771234567). الأرقام بدون اسم تظهر بالرقم فقط، أو بالاسم الذي وضعه الشخص لنفسه مسبوقاً بـ ~",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.contacts.isEmpty()) item {
                Text("لا توجد أسماء مكتوبة بعد. اضغط + لإضافة اسم.", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(vm.contacts.entries.sortedBy { it.value }, key = { it.key }) { (num, name) ->
                AppCard {
                    SettingRow(Icons.Rounded.Person, name, ltr("+$num"), onClick = { adding = num to name }) {
                        IconButton(onClick = { vm.setName(num, "") }) { Icon(Icons.Rounded.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    adding?.let { (n0, name0) ->
        var num by remember { mutableStateOf(n0) }
        var name by remember { mutableStateOf(name0) }
        AlertDialog(onDismissRequest = { adding = null }, title = { Text(if (n0.isEmpty()) "إضافة اسم" else "تعديل الاسم") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = num, onValueChange = { num = it }, label = { Text("الرقم الدولي (مثل 967771234567)") },
                        singleLine = true, enabled = n0.isEmpty(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("الاسم") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = num.any { it.isDigit() } && name.isNotBlank(), onClick = { vm.setName(num, name); adding = null }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("إلغاء") } })
    }
    if (ccDialog) {
        var cc by remember { mutableStateOf(vm.countryCode) }
        AlertDialog(onDismissRequest = { ccDialog = false }, title = { Text("رمز الدولة") },
            text = { OutlinedTextField(value = cc, onValueChange = { cc = it.filter { c -> c.isDigit() }.take(4) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = cc.isNotEmpty(), onClick = { vm.updateCountryCode(cc); ccDialog = false }) { Text("حفظ") } },
            dismissButton = { TextButton(onClick = { ccDialog = false }) { Text("إلغاء") } })
    }
}
