package com.rsd.downloader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Unpacks yt-dlp / Python / FFmpeg in the background (a few seconds on first launch).
        Engine.init(this)
    }
}
