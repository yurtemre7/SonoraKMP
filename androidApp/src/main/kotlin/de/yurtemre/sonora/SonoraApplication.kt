package de.yurtemre.sonora

import android.app.Application
import de.yurtemre.sonora.di.appModule
import de.yurtemre.sonora.shared.di.sharedModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class SonoraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SonoraApplication)
            modules(sharedModule, appModule)
        }
    }
}
