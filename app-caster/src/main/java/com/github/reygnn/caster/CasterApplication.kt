package com.github.reygnn.caster

import android.app.Application
import com.github.reygnn.core.data.SettingsStore
import com.github.reygnn.core.ssh.SshSecurity

class CasterApplication : Application() {
    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        SshSecurity.installBouncyCastle()
        settingsStore = SettingsStore(this)
    }
}
