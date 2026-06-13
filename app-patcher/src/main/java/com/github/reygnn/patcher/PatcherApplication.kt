package com.github.reygnn.patcher

import android.app.Application
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PatcherApplication : Application() {
    lateinit var settingsStore: SettingsStore
        private set

    /** App-lifetime scope for persistence that must survive a VM clear
     *  (e.g. pinning a freshly learned host key). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        SshSecurity.installBouncyCastle()
        settingsStore = SettingsStore(this)
    }
}
