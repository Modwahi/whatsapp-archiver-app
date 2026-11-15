package com.modwahi.waarchiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.modwahi.waarchiver.data.Mode
import org.json.JSONObject

private data class Hero(val tone: Tone, val icon: ImageVector, val title: String, val subtitle: String)

@Composable
fun HomeScreen(vm: AppViewModel, a: Actions, openHistory: () -> Unit, openChats: () -> Unit) {
    val last = vm.lastRun
    val age = vm.backupAge
    val lastOk = vm.history.firstOrNull { it.optBoolean("ok") && !it.optBoolean("dry") }
    val hero = when {
        vm.job != null -> Hero(Tone.INFO, Icons.Rounded.Sync, "جارٍ العمل…", vm.job!!.stage)
        vm.selectedCount == 0 -> Hero(Tone.WARN, Icons.Rounded.Warning, "اختر المحادثات", "لم تختر أي محادثة للأرشفة بعد")
        age == null -> Hero(Tone.ERROR, Icons.Rounded.Error, "لا توجد نسخة احتياطية", "لم أجد ملف النسخة في مجلد واتساب")
        last != null && !last.optBoolean("ok") -> Hero(Tone.ERROR, Icons.Rounded.Error, "آخر تشغيل فيه أخطاء",
            firstError(last) ?: "افتح التفاصيل أدناه")
        age > 36 -> Hero(Tone.WARN, Icons.Rounded.Warning, "النسخة الاحتياطية قديمة",
            "عمرها ${hoursText(age)}. تأكد أن النسخ الاحتياطي اليومي مفعّل في واتساب")
        lastOk != null -> Hero(Tone.OK, Icons.Rounded.CheckCircle, "كل شيء يعمل", "آخر أرشفة ناجحة ${ago(lastOk.optLong("time"))}")
        else -> Hero(Tone.INFO, Icons.Rounded.CloudDone, "جاهز", "اضغط «شغّل الآن» لأول أرشفة، أو انتظر التشغيل الليلي")
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(hero)
        JobBanner(vm)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(age?.let { hoursText(it) } ?: "—", "عمر النسخة", Modifier.weight(1f),
                if (age == null) Tone.ERROR else if (age > 36) Tone.WARN else Tone.OK)
            StatTile("${vm.selectedCount}", "محادثات مختارة", Modifier.weight(1f))
            val msgs = last?.optJSONArray("chats")?.let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).optInt("messages") } }
            StatTile(msgs?.let { fmtNum(it.toLong()) } ?: "—", "رسائل آخر تشغيل", Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.runNow() }, enabled = vm.job == null && vm.selectedCount > 0, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("شغّل الآن")
            }
            OutlinedButton(onClick = { a.requestLegacyStorage(); vm.dryRun() }, enabled = vm.job == null && vm.selectedCount > 0,
                modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Science, null); Spacer(Modifier.width(6.dp)); Text("تجربة بدون رفع")
            }
        }
        if (vm.selectedCount == 0) TextButton(onClick = openChats) { Text("اختيار المحادثات") }

        ScheduleCard(vm, a)
        LastRunCard(last, openHistory, a)
        DestinationCard(vm, a)
        Spacer(Modifier.size(8.dp))
    }
}

private fun firstError(o: JSONObject): String? {
    o.optJSONArray("errors")?.let { if (it.length() > 0) return it.getString(0) }
    val chats = o.optJSONArray("chats") ?: return null
    for (i in 0 until chats.length()) {
        val c = chats.getJSONObject(i)
        if (!c.isNull("error")) return "${c.optString("name")}: ${c.optString("error")}"
    }
    return null
}

@Composable
private fun HeroCard(h: Hero) {
    val (fg, _) = toneColors(h.tone)
    val bg = Brush.linearGradient(listOf(fg, fg.copy(alpha = 0.78f)))
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(bg).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(h.icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(h.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(h.subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
            }
        }
    }
}

@Composable
private fun ScheduleCard(vm: AppViewModel, a: Actions) {
    AppCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            SettingRow(Icons.Rounded.Schedule, "التشغيل التلقائي",
                if (!vm.scheduleEnabled) "متوقف — فعّله من الإعدادات"
                else "يفحص كل ساعة تقريباً، ويؤرشف مرة واحدة بعد كل نسخة احتياطية جديدة" +
                    (vm.nextCheckAt?.let { "\nالفحص القادم حوالي ${fmtTime(it)}" } ?: ""),
                if (vm.scheduleEnabled) Tone.OK else Tone.WARN)
            if (vm.scheduleEnabled) Row(Modifier.padding(start = 68.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.requireCharging) Pill("على الشحن")
                if (vm.requireWifi && vm.mode == Mode.DRIVE) Pill("واي فاي")
            }
            vm.lastAuto?.let { o ->
                val tone = when (o.optString("tone")) { "ok" -> Tone.OK; "warn" -> Tone.WARN; "error" -> Tone.ERROR; else -> Tone.INFO }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("آخر فحص تلقائي: ${ago(o.optLong("time"))}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(4.dp))
                    StatusLine(tone != Tone.ERROR, o.optString("text"), tone)
                }
            }
            if (vm.scheduleEnabled && !vm.batteryExempt) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusLine(false, "أندرويد قد يوقف التشغيل التلقائي: التطبيق غير مسموح له بالعمل في الخلفية", Tone.WARN)
                    Text("١) اضغط «السماح» واختر «سماح».\n٢) في سامسونج أيضاً: «إعدادات التطبيق» ← البطارية ← «غير مقيّد».",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { a.requestBatteryExemption() }) { Text("السماح") }
                        OutlinedButton(onClick = { a.openAppSettings() }) { Text("إعدادات التطبيق") }
                    }
                }
            }
        }
    }
}

@Composable
fun RunDetails(o: JSONObject) {
    val chats = o.optJSONArray("chats")
    if (chats != null) for (i in 0 until chats.length()) {
        val c = chats.getJSONObject(i)
        val err = if (c.isNull("error")) null else c.optString("error")
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (err == null) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null,
                tint = toneColors(if (err == null) Tone.OK else Tone.ERROR).first, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(c.optString("name"), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                if (err != null) Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else {
                    val extra = buildList {
                        if (c.optInt("missing") > 0) add("غير منزّلة ${c.optInt("missing")}")
                        if (c.optInt("large") > 0) add("أكبر من الحد ${c.optInt("large")}")
                        if (c.optInt("recovered") > 0) add("مستردة ${c.optInt("recovered")}")
                    }
                    Text("رسائل ${c.optInt("messages")} · وسائط ${c.optInt("media")}" + extra.joinToString("") { " · $it" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    o.optJSONArray("errors")?.let { e -> for (i in 0 until e.length()) StatusLine(false, e.getString(i)) }
    o.optJSONArray("warnings")?.let { w -> for (i in 0 until w.length()) StatusLine(false, w.getString(i), Tone.WARN) }
}

@Composable
private fun LastRunCard(o: JSONObject?, openHistory: () -> Unit, a: Actions) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("آخر تشغيل", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = openHistory) { Icon(Icons.Rounded.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("السجل") }
            }
            if (o == null) { Text("لم يتم أي تشغيل بعد.", color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (o.optBoolean("ok")) "ناجح" else "فيه أخطاء", if (o.optBoolean("ok")) Tone.OK else Tone.ERROR)
                if (o.optBoolean("dry")) Pill("تجربة")
                Text("${fmtDate(o.optLong("time"))} · ${ago(o.optLong("time"))}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            RunDetails(o)
            if (!o.isNull("exported")) TextButton(onClick = { a.openDownloads() }) { Text("فتح مجلد التنزيلات (${o.optString("exported")})") }
        }
    }
}

@Composable
private fun DestinationCard(vm: AppViewModel, a: Actions) {
    AppCard {
        if (vm.mode == Mode.DRIVE) {
            SettingRow(Icons.Rounded.CloudDone, "Google Drive", vm.account ?: "لم تسجّل الدخول", Tone.INFO,
                onClick = vm.driveFolderId?.let { id -> { a.openUrl("https://drive.google.com/drive/folders/$id") } }) {
                if (vm.driveFolderId != null) Icon(Icons.AutoMirrored.Rounded.OpenInNew, "فتح", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            SettingRow(Icons.Rounded.PhoneAndroid, "حفظ محلي على التلفون", "في المجلد الذي اخترته")
        }
    }
}
