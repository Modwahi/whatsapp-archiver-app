package com.modwahi.waarchiver.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.modwahi.waarchiver.R
import com.modwahi.waarchiver.data.Prefs

/** أصوات قصيرة داخل التطبيق (لا تُشغَّل والتلفون على الصامت أو الاهتزاز، أو إن أوقفها المستخدم). */
object Sounds {
    enum class Snd(val res: Int) { START(R.raw.snd_start), DONE(R.raw.snd_done), ERROR(R.raw.snd_error), TICK(R.raw.snd_tick) }

    private var pool: SoundPool? = null
    private val ids = HashMap<Snd, Int>()

    private fun pool(context: Context): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build().also { p ->
            pool = p
            Snd.entries.forEach { s -> ids[s] = p.load(context.applicationContext, s.res, 1) }
        }

    /** تحميل مسبق حتى يُشغَّل الصوت الأول فوراً */
    fun warmUp(context: Context) { runCatching { pool(context) } }

    fun play(context: Context, s: Snd, volume: Float = 0.7f) {
        if (!Prefs(context).soundsEnabled) return
        val am = context.getSystemService(AudioManager::class.java)
        if (am?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        runCatching { ids[s]?.let { pool(context).play(it, volume, volume, 1, 0, 1f) } ?: pool(context) }
    }
}
