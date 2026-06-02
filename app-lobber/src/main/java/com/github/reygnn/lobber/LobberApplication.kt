package com.github.reygnn.lobber

import android.app.Application
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LobberApplication : Application() {
    lateinit var settingsStore: SettingsStore
        private set

    /** App-lebenslanger Scope für Persistenz, die einen VM-Clear überleben muss
     *  (z. B. das Pinnen eines frisch gelernten Host-Keys). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        SshSecurity.installBouncyCastle()
        settingsStore = SettingsStore(this)
    }
}
