package com.nexos.play

import android.app.Application
import com.nexos.play.net.AppDownloader
import com.nexos.play.util.Prefs
import org.schabi.newpipe.extractor.NewPipe

class NexosPlayApp : Application() {

    companion object {
        @Volatile var extractorReady = false
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
        if (!extractorReady) {
            NewPipe.init(AppDownloader.get())
            extractorReady = true
        }
    }
}
