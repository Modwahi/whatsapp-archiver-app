package com.modwahi.waarchiver.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modwahi.waarchiver.core.JObj
import com.modwahi.waarchiver.core.Naming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ======================= قائمة المحادثات المؤرشفة =======================

@Composable
fun ReaderListScreen(vm: AppViewModel, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        JobBanner(vm)
        vm.listError?.takeIf { vm.job == null }?.let { StatusLine(false, it); Spacer(Modifier.height(8.dp)) }
        if (vm.readable.isEmpty()) {
            AppCard {
                Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(Icons.Rounded.MenuBook, Tone.INFO, 64)
                    Text("اقرأ محادثاتك المؤرشفة هنا", style = MaterialTheme.typography.titleMedium)
                    Text("كل أرشفة جديدة تظهر هنا تلقائياً. لقراءة ما أُرشف سابقاً، نزّل نسخة القراءة من الوجهة (نصوص الرسائل فقط، حجمها صغير).",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Button(onClick = { vm.syncReadable() }, enabled = vm.job == null) {
                        Icon(Icons.Rounded.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text("تنزيل الأرشيف للقراءة")
                    }
                }
            }
            return@Column
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("بحث في أسماء المحادثات") }, singleLine = true, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.readable.size} محادثة مؤرشفة", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.syncReadable() }, enabled = vm.job == null) {
                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("مزامنة من الوجهة")
            }
        }
        val list = vm.readable.filter { query.isBlank() || it.folder.contains(query.trim(), ignoreCase = true) }
        AppCard(Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(list, key = { it.folder }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { open(c.folder) }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(c.folder, false, c.folder)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.folder, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("${c.days.size} يوم · آخر يوم ${ltr(c.lastDay ?: "")}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ======================= قراءة محادثة =======================

private sealed interface Item {
    data class Day(val date: String) : Item
    data class Msg(val date: String, val r: JObj) : Item
}

@Composable
fun ChatReaderScreen(vm: AppViewModel, folder: String) {
    val chat = vm.readable.firstOrNull { it.folder == folder }
    val allDays = chat?.days ?: emptyList()
    var shown by rememberSaveable(folder) { mutableIntStateOf(7) }
    var query by rememberSaveable(folder) { mutableStateOf("") }
    var searching by rememberSaveable(folder) { mutableStateOf(false) }
    val q = query.trim()

    val items by produceState<List<Item>?>(null, folder, shown, q, allDays) {
        value = withContext(Dispatchers.IO) {
            val days = if (q.isNotEmpty()) allDays else allDays.takeLast(shown)
            val out = ArrayList<Item>()
            for (d in days) {
                val recs = vm.dayMessages(folder, d).filter { r ->
                    q.isEmpty() || listOf("text", "sender", "quoted_text", "media_file").any { k ->
                        (r[k] as? String)?.contains(q, ignoreCase = true) == true
                    }
                }
                if (recs.isEmpty()) continue
                out.add(Item.Day(d))
                recs.forEach { out.add(Item.Msg(d, it)) }
            }
            out
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(items == null, q) {
        val n = items?.size ?: 0
        if (n > 0 && q.isEmpty() && shown == 7) listState.scrollToItem(n) // نبدأ من أحدث رسالة
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("بحث في كل الرسائل") }, shape = MaterialTheme.shapes.extraLarge,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Rounded.Close, "إغلاق") } },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                } else {
                    Text("${allDays.size} يوم مؤرشف", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "بحث") }
                }
            }
        }
        val list = items
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (q.isNotEmpty()) Text("${list.count { it is Item.Msg }} نتيجة", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), state = listState,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (q.isEmpty() && shown < allDays.size) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = { shown += 14 }) { Text("عرض أيام أقدم (${allDays.size - shown})") }
                }
            }
            items(list, key = { when (it) { is Item.Day -> "d" + it.date; is Item.Msg -> "m" + it.date + it.r["id"] } }) { item ->
                when (item) {
                    is Item.Day -> DayHeader(item.date)
                    is Item.Msg -> Bubble(vm, item.r, highlight = q)
                }
            }
            if (list.isEmpty()) item {
                Text(if (q.isNotEmpty()) "لا توجد نتائج" else "لا توجد رسائل", Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DayHeader(date: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(ltr(date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

private val senderColors = listOf(
    Color(0xFF0B7A66), Color(0xFF3F6FB5), Color(0xFF8E4FA8), Color(0xFFB4533B), Color(0xFF2F8A9E),
    Color(0xFF7A6A1E), Color(0xFFA23E6B), Color(0xFF4B7A2B),
)

@Composable
private fun Bubble(vm: AppViewModel, r: JObj, highlight: String) {
    val mine = r["from_me"] == true
    val sender = (r["sender"] as? String).orEmpty()
    val type = (r["type"] as? String).orEmpty()
    val text = (r["text"] as? String).orEmpty()
    val time = (r["time"] as? String)?.substringAfter('T')?.take(5) ?: ""
    val hasMedia = r["has_media"] == true
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest
    val shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = shape, color = bg, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!mine && sender.isNotEmpty()) Text(sender, style = MaterialTheme.typography.labelLarge,
                    color = senderColors[(sender.hashCode() and 0x7fffffff) % senderColors.size], maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r["forwarded"] == true) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Forward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("معاد توجيهها", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                (r["quoted_text"] as? String)?.let { qt ->
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
                        Box(Modifier.width(3.dp).heightIn(min = 32.dp).background(MaterialTheme.colorScheme.primary))
                        Text(qt.ifEmpty { "رسالة" }, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                }
                if (hasMedia) MediaPart(vm, r, type)
                val body = text.ifEmpty { if (hasMedia) "" else Naming.TYPE_LABEL[type] ?: if (type.startsWith("type_")) "[نوع غير معروف]" else "" }
                if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (highlight.isNotEmpty() && body.contains(highlight, ignoreCase = true)) FontWeight.SemiBold else null)
                Text(ltr(time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun MediaPart(vm: AppViewModel, r: JObj, type: String) {
    val status = r["media_status"] as? String
    val rel = r["media_path"] as? String
    val isImage = type in setOf("image", "sticker", "gif", "view_once_image") || (r["mime"] as? String)?.startsWith("image/") == true
    if (isImage && status == "saved" && !rel.isNullOrEmpty()) {
        val img by produceState<ImageBitmap?>(null, rel) { value = vm.thumbnail(rel) }
        val bmp = img
        if (bmp != null) {
            Image(bmp, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)))
            return
        }
    }
    val label = Naming.TYPE_LABEL[type] ?: "[وسيط]"
    val detail = (r["media_file"] as? String) ?: Naming.MEDIA_STATUS_LABEL[status] ?: ""
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (detail.isNotEmpty()) Text(if (r["media_file"] != null) ltr(detail) else detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
