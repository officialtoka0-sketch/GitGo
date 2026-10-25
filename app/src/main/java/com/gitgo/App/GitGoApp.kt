package com.gitgo.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class GitGoApp : Application() {
    lateinit var prefs: Prefs

    init { instance = this }

    override fun onCreate() {
        super.onCreate()
        // JGit ищет домашний каталог пользователя — на Android его нет.
        System.setProperty("user.home", filesDir.absolutePath)
        prefs = Prefs(this)
        Thread({ prefs.warmUp() }, "prefs-warmup").start()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        lateinit var instance: GitGoApp
            private set
        const val CHANNEL_PROGRESS = "gitgo_progress"
        const val CHANNEL_DONE = "gitgo_done"
    }
}
