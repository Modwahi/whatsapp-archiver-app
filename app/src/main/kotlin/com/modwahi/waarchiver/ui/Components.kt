package com.modwahi.waarchiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** يعزل نصاً من اليسار لليمين (تاريخ، رقم هاتف) داخل جملة عربية حتى لا ينقلب ترتيبه */
fun ltr(s: String): String = "\u2066$s\u2069"

fun fmtDate(ms: Long): String = ltr(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms)))
fun fmtDay(ms: Long): String = ltr(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ms)))
fun fmtTime(ms: Long): String = ltr(SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms)))
fun fmtNum(n: Long): String = String.format(Locale.ROOT, "%,d", n)

/** «منذ 5 دقائق» / «منذ 3 ساعات» / «منذ يومين» */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((now - ms) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "الآن"
        m < 60 -> "منذ ${arCount(m, "دقيقة", "دقيقتين", "دقائق")}"
        m < 48 * 60 -> "منذ ${arCount(m / 60, "ساعة", "ساعتين", "ساعات")}"
        else -> "منذ ${arCount(m / 1440, "يوم", "يومين", "أيام")}"
    }
}

fun hoursText(h: Double): String = when {
    h < 1 -> "أقل من ساعة"
    h < 48 -> arCount(h.toLong(), "ساعة", "ساعتين", "ساعات")
    else -> arCount((h / 24).toLong(), "يوم", "يومين", "أيام")
}

fun arCount(n: Long, one: String, two: String, few: String): String = when {
    n == 1L -> one
    n == 2L -> two
    n in 3..10 -> "$n $few"
    else -> "$n $one"
}

enum class Tone { OK, WARN, ERROR, INFO }

@Composable
fun toneColors(t: Tone): Pair<Color, Color> {
    val s = LocalStatus.current
    val c = MaterialTheme.colorScheme
    return when (t) {
        Tone.OK -> s.ok to s.okContainer
        Tone.WARN -> s.warn to s.warnContainer
        Tone.ERROR -> c.error to c.errorContainer
        Tone.INFO -> c.primary to c.primaryContainer
    }
}

@Composable
fun StatusLine(ok: Boolean, text: String, tone: Tone = if (ok) Tone.OK else Tone.ERROR) {
    val (fg, _) = toneColors(tone)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(when (tone) { Tone.OK -> Icons.Rounded.CheckCircle; Tone.WARN -> Icons.Rounded.Warning; Tone.INFO -> Icons.Rounded.Info; else -> Icons.Rounded.Error },
            null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (tone == Tone.ERROR) fg else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

/** أيقونة داخل دائرة ملوّنة */
@Composable
fun IconBadge(icon: ImageVector, tone: Tone = Tone.INFO, size: Int = 40) {
    val (fg, bg) = toneColors(tone)
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size((size * 0.55).dp))
    }
}

private val avatarColors = listOf(
    Color(0xFF0B7A66), Color(0xFF3F6FB5), Color(0xFF8E4FA8), Color(0xFFB4533B), Color(0xFF2F8A9E),
    Color(0xFF7A6A1E), Color(0xFFA23E6B), Color(0xFF4B7A2B),
)

@Composable
fun ChatAvatar(name: String, isGroup: Boolean, key: String) {
    val color = avatarColors[(key.hashCode() and 0x7fffffff) % avatarColors.size]
    Box(Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        if (isGroup) Icon(Icons.Rounded.Groups, null, tint = color, modifier = Modifier.size(24.dp))
        else Text(name.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "؟", color = color, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) { content() }
}

/** صف إعداد: أيقونة + عنوان + وصف + عنصر في الطرف */
@Composable
fun SettingRow(
    icon: ImageVector, title: String, subtitle: String? = null, tone: Tone = Tone.INFO,
    onClick: (() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tone, 38)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, tone: Tone = Tone.INFO) {
    val (fg, _) = toneColors(tone)
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun Pill(text: String, tone: Tone = Tone.INFO) {
    val (fg, bg) = toneColors(tone)
    Surface(shape = CircleShape, color = bg) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
