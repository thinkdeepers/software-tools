package com.cursor.mobile

import android.app.Application
import com.cursor.mobile.di.AppContainer

class CursorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
