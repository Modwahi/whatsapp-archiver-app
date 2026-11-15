package com.modwahi.waarchiver.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

/** اهتزاز خفيف (إن كان مفعّلاً في الإعدادات) */
@Composable
fun rememberHaptic(vm: AppViewModel): (Boolean) -> Unit {
    val h = LocalHapticFeedback.current
    return { strong -> if (vm.hapticsEnabled) h.performHapticFeedback(if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove) }
}

/** رقم يعدّ تصاعدياً من الصفر */
@Composable
private fun CountUp(target: Int, started: Boolean): Int {
    val v by animateIntAsState(if (started) target else 0, tween(1100, easing = FastOutSlowInEasing), label = "count")
    return v
}

/** شاشة الملخص بعد انتهاء الأرشفة: علامة ✓ متحركة وأرقام تعدّ، أو سبب الفشل. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CelebrationSheet(vm: AppViewModel, run: JSONObject, onDismiss: () -> Unit) {
    val ok = run.optBoolean("ok")
    val dry = run.optBoolean("dry")
    val (fg, bg) = toneColors(if (ok) Tone.OK else Tone.ERROR)
    val haptic = rememberHaptic(vm)
    val scale = remember { Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        haptic(true)
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); started = true }

    val chats = run.optJSONArray("chats")
    val list = (0 until (chats?.length() ?: 0)).map { chats!!.getJSONObject(it) }
    val msgs = list.sumOf { it.optInt("messages") }
    val media = list.sumOf { it.optInt("media") }
    val mbTenths = (run.optLong("bytes") / 104857.6).toInt() // ميغابايت × 10
    val secs = (run.optLong("duration") / 1000).toInt()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(96.dp).scale(scale.value).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Box(Modifier.size(68.dp).clip(CircleShape).background(fg), contentAlignment = Alignment.Center) {
                    Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(44.dp))
                }
            }
            Text(when {
                !ok -> "لم تكتمل الأرشفة"
                dry -> "اكتملت التجربة"
                msgs == 0 -> "الأرشيف محدّث ✓"
                else -> "تمت الأرشفة!"
            }, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)

            if (ok) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(fmtNum(CountUp(msgs, started).toLong()), "رسالة", Modifier.weight(1f), Tone.OK)
                    StatTile(fmtNum(CountUp(media, started).toLong()), "وسيط", Modifier.weight(1f))
                    if (!dry) StatTile(String.format(Locale.ROOT, "%.1f", CountUp(mbTenths, started) / 10.0), "ميغابايت", Modifier.weight(1f))
                }
                Text(if (secs < 60) "في $secs ثانية" else "في ${secs / 60} دقيقة و ${secs % 60} ثانية",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val top = list.filter { it.optInt("messages") > 0 }.sortedByDescending { it.optInt("messages") }.take(6)
                if (top.isNotEmpty()) AppCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        top.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChatAvatar(c.optString("name"), false, c.optString("name"))
                                Spacer(Modifier.size(10.dp))
                                Text(c.optString("name"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(ltr("+${c.optInt("messages")}"), style = MaterialTheme.typography.titleSmall, color = fg)
                            }
                        }
                    }
                }
                if (dry && !run.isNull("exported")) Text("الناتج في: ${run.optString("exported")}", style = MaterialTheme.typography.bodySmall)
            } else {
                val err = run.optJSONArray("errors")?.optString(0)?.takeIf { it.isNotEmpty() }
                    ?: list.firstOrNull { !it.isNull("error") }?.let { "${it.optString("name")}: ${it.optString("error")}" } ?: ""
                Text(err, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Text("التفاصيل في «آخر تشغيل» و«سجل التشغيلات».", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(if (ok) "رائع" else "حسناً") }
        }
    }
}
