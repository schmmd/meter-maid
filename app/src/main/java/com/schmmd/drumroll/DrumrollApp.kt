package com.schmmd.drumroll

import android.app.Application
import com.schmmd.drumroll.data.Repository
import com.schmmd.drumroll.data.SettingsStore

/**
 * Six objects total, so they are wired by hand. A DI framework here would be more
 * ceremony than app.
 */
class DrumrollApp : Application() {
    lateinit var repository: Repository
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        settings = SettingsStore(this)
    }
}
