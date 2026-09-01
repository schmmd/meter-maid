package com.schmmd.drumroll

import android.app.Application
import com.schmmd.drumroll.backup.ExportCoordinator
import com.schmmd.drumroll.backup.SafFile
import com.schmmd.drumroll.data.Repository
import com.schmmd.drumroll.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Six objects total, so they are wired by hand. A DI framework here would be more
 * ceremony than app.
 */
class DrumrollApp : Application() {
    lateinit var repository: Repository
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var export: ExportCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        settings = SettingsStore(this)
        // Outlives any one screen: the export has to survive leaving the settings tab.
        export = ExportCoordinator(
            scope = CoroutineScope(SupervisorJob()),
            repository = repository,
            settings = settings,
            saf = SafFile(this),
        ).also { it.start() }
    }
}
