package com.modwahi.waarchiver

import android.app.Application
import com.modwahi.waarchiver.work.ArchiveWorker
import com.modwahi.waarchiver.work.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        ArchiveWorker.schedule(this)
    }
}
