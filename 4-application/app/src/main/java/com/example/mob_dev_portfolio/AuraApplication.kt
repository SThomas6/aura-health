package com.example.mob_dev_portfolio

import android.app.Application

class AuraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        container = DefaultAppContainer(this)
    }
}
