package com.lockpath.vegs

import android.app.Application
import com.lockpath.vegs.data.AppRepository

class LockPathApp : Application() {
    lateinit var repository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
    }
}
