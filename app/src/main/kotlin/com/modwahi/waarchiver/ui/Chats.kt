package com.modwahi.waarchiver.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.modwahi.waarchiver.data.KnownChat

@Composable
fun ChatsScreen(vm: AppViewModel, embedded: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 الكل، 1 المختارة، 2 مجموعات، 3 خاص
    var sort by rememberSaveable { mutableStateOf(SortBy.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<KnownChat?>(null) }

    Column(Modifier.fillMaxSize().let { if (embedded) it else it.padding(horizontal = 16.dp) }) {
        JobBanner(vm)
        if (vm.job != null) Spacer(Modifier.height(8.dp))
        vm.listError?.let { StatusLine(false, it); Spacer(Modifier.height(8.dp)) }

        if (vm.known.isEmpty()) {
            EmptyChats(vm)
            return@Column
        }

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "مسح") } },
            placeholder = { Text("بحث بالاسم أو الرقم") }, singleLine = true, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val labels = listOf("الكل", "المختارة ${vm.selectedCount}", "مجموعات", "خاص")
            items(labels.size) { i -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(labels[i]) }) }
        }

        val q = query.trim()
        val list = remember(vm.known, vm.choices, vm.contacts, q, filter, sort) {
            vm.known.filter { c ->
                val sel = vm.choices[c.jid]?.selected == true
                (filter == 0 || (filter == 1 && sel) || (filter == 2 && c.isGroup) || (filter == 3 && !c.isGroup)) &&
                    (q.isEmpty() || vm.displayName(c).contains(q, ignoreCase = true) || c.jid.contains(q))
            }.let { l ->
                when (sort) {
                    SortBy.RECENT -> l
                    SortBy.NAME -> l.sortedBy { vm.displayName(it) }
                    SortBy.MESSAGES -> l.sortedByDescending { it.count }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.size} محادثة" + if (vm.knownAt > 0) " · حُدّثت ${ago(vm.knownAt)}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (q.isNotEmpty() || filter >= 2) {
                val allSel = list.isNotEmpty() && list.all { vm.choices[it.jid]?.selected == true }
                TextButton(onClick = { vm.selectAll(list.map { it.jid }, !allSel) }) { Text(if (allSel) "إلغاء الكل" else "تحديد الكل") }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "ترتيب") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf(SortBy.RECENT to "الأحدث", SortBy.NAME to "الاسم", SortBy.MESSAGES to "عدد الرسائل").forEach { (s, t) ->
                        DropdownMenuItem(text = { Text(t, fontWeight = if (s == sort) FontWeight.Bold else null) },
                            onClick = { sort = s; sortMenu = false })
                    }
                }
            }
            IconButton(onClick = { vm.refreshChats() }, enabled = vm.job == null) { Icon(Icons.Rounded.Refresh, "تحديث من النسخة") }
        }
        AppCard(Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(list, key = { it.jid }) { c ->
                    ChatRow(vm, c, onEdit = { editing = c })
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                if (list.isEmpty()) item {
                    Text("لا توجد نتائج", Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    editing?.let { c -> EditChatSheet(vm, c) { editing = null } }
}

@Composable
private fun EmptyChats(vm: AppViewModel) {
    AppCard {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Rounded.Forum, Tone.INFO, 64)
            Text("اقرأ قائمة المحادثات", style = MaterialTheme.typography.titleMedium)
            Text("يفك التطبيق النسخة الاحتياطية مرة واحدة (دقيقة أو أكثر) ويعرض كل المجموعات والمحادثات الخاصة، ثم يحذف النسخة المفكوكة. " +
                "بعد ذلك تتحدث القائمة تلقائياً مع كل أرشفة.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = { vm.refreshChats() }, enabled = vm.job == null) { Text("اقرأ المحادثات من النسخة") }
        }
    }
}

@Composable
private fun ChatRow(vm: AppViewModel, c: KnownChat, onEdit: () -> Unit) {
    val selected = vm.choices[c.jid]?.selected == true
    val name = vm.displayName(c)
    val prog = vm.progressByChat[c.jid]
    Row(Modifier.fillMaxWidth().clickable { vm.toggle(c.jid, !selected) }.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(name, c.isGroup, c.jid)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((if (c.isGroup) "مجموعة" else "خاص") + " · ${fmtNum(c.count)} رسالة" +
                (if (c.lastTs > 0) " · ${fmtDay(c.lastTs * 1000)}" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (selected) {
                val archived = prog?.lastTimestamp?.takeIf { it > 0 }
                Text("📁 ${vm.folderFor(c)}" + (archived?.let { " · مؤرشفة حتى ${fmtDate(it * 1000)}" } ?: " · لم تُؤرشف بعد") +
                    (prog?.pendingMedia?.takeIf { it > 0 }?.let { " · $it وسيط بانتظار التنزيل" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2)
            }
        }
        if (selected) IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "تعديل", tint = MaterialTheme.colorScheme.primary) }
        Checkbox(checked = selected, onCheckedChange = { vm.toggle(c.jid, it) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditChatSheet(vm: AppViewModel, c: KnownChat, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val num = com.modwahi.waarchiver.core.Archiver.numberOf(c.jid)
    var owner by remember { mutableStateOf(vm.contacts[num] ?: "") }
    var folder by remember { mutableStateOf(vm.choices[c.jid]?.folder ?: "") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(vm.displayName(c), c.isGroup, c.jid)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(vm.displayName(c), style = MaterialTheme.typography.titleMedium)
                    Text(if (c.isGroup) "مجموعة" else ltr("+$num"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!c.isGroup) OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("اسم صاحب الرقم") },
                supportingText = { Text("يظهر في الرسائل بدل الرقم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = folder, onValueChange = { folder = it }, label = { Text("اسم المجلد") },
                placeholder = { Text(vm.defaultFolder(c)) },
                supportingText = { Text("اتركه فارغاً لاستخدام: ${vm.defaultFolder(c)}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!c.isGroup) vm.setOwnerName(c.jid, owner)
                    vm.setFolder(c.jid, folder)
                    onDismiss()
                }, modifier = Modifier.weight(1f)) { Text("حفظ") }
                TextButton(onClick = { vm.toggle(c.jid, false); onDismiss() }) {
                    Text("إلغاء الاختيار", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}
